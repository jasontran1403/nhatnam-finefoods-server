package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.supply.OfficeSupplyDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeSupplyService {

    private final OfficeSupplyRequestRepository requestRepo;
    private final OfficeSupplyOrderRepository   orderRepo;
    private final SupplyWarehouseRepository     warehouseRepo;
    private final SupplyItemRepository          itemRepo;
    private final UserRepository                userRepo;
    private final ObjectMapper                  objectMapper;
    // supplyItemService KHÔNG inject qua constructor để tránh vòng phụ thuộc
    // (SupplyItemService không phụ thuộc OfficeSupplyService nhưng để mở nếu
    // tương lai có mở rộng); truyền vào chính method admin* nhằm mục đích rõ.

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Người dùng không tồn tại"));
    }

    private SupplyWarehouse warehouse(Long warehouseId) {
        return warehouseRepo.findById(warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Văn phòng không tồn tại"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Danh mục vật dụng (với thống kê)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Danh sách vật dụng kèm thống kê (số lần mua, ngày mua gần nhất)
     * theo văn phòng. Dùng cho trang đăng ký của nhân viên và trang kho của owner.
     */
    @Transactional(readOnly = true)
    public List<OfficeItemDto> listItems(Long warehouseId) {
        List<SupplyItem> items = itemRepo.findByDeletedAtIsNullOrderByNameAsc();

        // Thống kê theo kho
        Map<Long, Object[]> statsMap = new HashMap<>();
        if (warehouseId != null) {
            orderRepo.statsByItemAndWarehouse(warehouseId)
                    .forEach(row -> statsMap.put((Long) row[0], row));
        }

        return items.stream().map(it -> {
            Object[] stats = statsMap.get(it.getId());
            return OfficeItemDto.builder()
                    .id(it.getId())
                    .name(it.getName())
                    .specification(it.getSpecification())
                    .unit(it.getUnit())
                    .orderCount(stats != null ? (Long) stats[1] : 0L)
                    .lastOrderedAt(stats != null ? (Long) stats[2] : null)
                    .totalQuantity(stats != null ? (BigDecimal) stats[3] : BigDecimal.ZERO)
                    .build();
        }).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phiếu đăng ký của nhân viên
    // ─────────────────────────────────────────────────────────────────────────

    /** Lấy phiếu đăng ký hiện tại của user (tất cả văn phòng). */
    @Transactional(readOnly = true)
    public List<MyRequestDto> myRequests() {
        User user = currentUser();
        return requestRepo.findAllByUserIdWithItems(user.getId()).stream()
                .map(this::toMyRequestDto)
                .collect(Collectors.toList());
    }

    /** Lưu / cập nhật phiếu đăng ký của user cho 1 văn phòng. */
    @Transactional
    public MyRequestDto saveRequest(SaveRequestBody body) {
        User user = currentUser();
        SupplyWarehouse wh = warehouse(body.getWarehouseId());

        OfficeSupplyRequest req = requestRepo
                .findByUserIdAndWarehouseId(user.getId(), wh.getId())
                .orElseGet(() -> OfficeSupplyRequest.builder()
                        .user(user).warehouse(wh).build());

        req.getItems().clear();

        if (body.getItems() != null) {
            for (RequestItemLine line : body.getItems()) {
                if (line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0)
                    continue;
                SupplyItem item = itemRepo.findById(line.getSupplyItemId())
                        .orElseThrow(() -> new ResourceNotFoundException("Vật dụng không tồn tại"));
                req.getItems().add(OfficeSupplyRequestItem.builder()
                        .request(req)
                        .supplyItem(item)
                        .quantity(line.getQuantity())
                        .note(line.getNote())
                        .build());
            }
        }

        requestRepo.save(req);
        return toMyRequestDto(req);
    }

    /** Xóa phiếu đăng ký của user cho 1 văn phòng. */
    @Transactional
    public void deleteRequest(Long warehouseId) {
        User user = currentUser();
        requestRepo.findByUserIdAndWarehouseId(user.getId(), warehouseId)
                .ifPresent(requestRepo::delete);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OWNER: tổng hợp + đặt hàng
    // ─────────────────────────────────────────────────────────────────────────

    /** Tổng hợp toàn bộ phiếu đăng ký của 1 văn phòng. */
    @Transactional(readOnly = true)
    public OrderSummaryDto summary(Long warehouseId) {
        SupplyWarehouse wh = warehouse(warehouseId);
        List<OfficeSupplyRequest> requests = requestRepo.findAllByWarehouseIdWithItems(warehouseId);

        // Sheet 1: tổng hợp theo vật dụng
        Map<Long, SummaryRowDto> summaryMap = new LinkedHashMap<>();
        // Sheet 2: chi tiết theo nhân viên × vật dụng
        List<DetailRowDto> details = new ArrayList<>();
        int stt2 = 1;

        for (OfficeSupplyRequest req : requests) {
            String name     = req.getUser().getFullName();
            String position = req.getUser().getPosition() != null ? req.getUser().getPosition() : "";

            for (OfficeSupplyRequestItem line : req.getItems()) {
                SupplyItem si = line.getSupplyItem();

                summaryMap.merge(si.getId(),
                        SummaryRowDto.builder()
                                .stt(0)
                                .supplyItemId(si.getId())
                                .name(si.getName())
                                .specification(si.getSpecification())
                                .unit(si.getUnit())
                                .totalQuantity(line.getQuantity())
                                .build(),
                        (existing, newRow) -> {
                            existing.setTotalQuantity(existing.getTotalQuantity().add(newRow.getTotalQuantity()));
                            return existing;
                        });

                details.add(DetailRowDto.builder()
                        .stt(stt2)
                        .userId(req.getUser().getId())
                        .userFullName(name)
                        .userPosition(position)
                        .userDepartment(req.getUser().getDepartment())
                        .itemName(si.getName())
                        .unit(si.getUnit())
                        .quantity(line.getQuantity())
                        .note(line.getNote())
                        .build());
            }
        }

        // Đánh số STT cho sheet 1
        List<SummaryRowDto> summaryList = new ArrayList<>(summaryMap.values());
        for (int i = 0; i < summaryList.size(); i++) summaryList.get(i).setStt(i + 1);

        return OrderSummaryDto.builder()
                .warehouseId(wh.getId())
                .warehouseName(wh.getName())
                .summary(summaryList)
                .detail(details)
                .employeeCount(requests.size())
                .build();
    }

    /**
     * OWNER bấm "Đặt hàng" — yêu cầu đã nhập GIÁ cho tất cả vật dụng:
     * <ol>
     *   <li>Tổng hợp tất cả request của văn phòng theo vật dụng → ra tổng số lượng.</li>
     *   <li>Với mỗi vật dụng: {@code lineAmount} (do owner nhập) chia cho tổng số lượng
     *       → giá đơn vị thô. Phí {@code fees} được chia theo tỉ trọng {@code lineAmount}
     *       rồi cộng vào giá đơn vị → {@code unitPrice} lưu theo ĐVT nhỏ nhất.</li>
     *   <li>Tạo OfficeSupplyOrder + OfficeSupplyOrderItem (giữ nguyên dòng theo nhân viên
     *       để trang chi tiết đơn vẫn in được, mỗi dòng mang cùng {@code unitPrice}).</li>
     *   <li>Xóa toàn bộ request để tổng hợp lần sau.</li>
     * </ol>
     *
     * <p>Nếu owner thiếu giá cho vật dụng nào → ném {@link BusinessException} (FE sẽ highlight).
     */
    @Transactional
    public OrderHistoryDto placeOrder(Long warehouseId, PlaceOrderBody body) {
        User actor = currentUser();
        SupplyWarehouse wh = warehouse(warehouseId);
        List<OfficeSupplyRequest> requests = requestRepo.findAllByWarehouseIdWithItems(warehouseId);

        if (requests.isEmpty())
            throw new BusinessException("Chưa có phiếu đăng ký nào cho văn phòng " + wh.getName());
        if (body == null || body.getItems() == null || body.getItems().isEmpty())
            throw new BusinessException("Vui lòng nhập giá cho tất cả vật dụng trước khi đặt hàng");

        // 1) Gom quantity theo supplyItemId từ tất cả request
        Map<Long, BigDecimal> qtyByItem = new LinkedHashMap<>();
        Map<Long, SupplyItem> itemsById = new LinkedHashMap<>();
        for (OfficeSupplyRequest req : requests) {
            for (OfficeSupplyRequestItem line : req.getItems()) {
                Long id = line.getSupplyItem().getId();
                qtyByItem.merge(id, line.getQuantity(), BigDecimal::add);
                itemsById.putIfAbsent(id, line.getSupplyItem());
            }
        }

        // 2) Map lineAmount theo supplyItemId (body)
        Map<Long, BigDecimal> lineAmountByItem = new HashMap<>();
        for (ItemPriceLine ip : body.getItems()) {
            if (ip == null || ip.getSupplyItemId() == null) continue;
            if (ip.getLineAmount() == null || ip.getLineAmount().signum() < 0)
                throw new BusinessException("Thành tiền không hợp lệ");
            lineAmountByItem.put(ip.getSupplyItemId(), ip.getLineAmount());
        }

        // Validate: mọi vật dụng được tổng hợp đều có giá
        for (Long id : qtyByItem.keySet()) {
            if (!lineAmountByItem.containsKey(id)) {
                SupplyItem si = itemsById.get(id);
                throw new BusinessException("Thiếu giá cho vật dụng: "
                        + (si != null ? si.getName() : ("#" + id)));
            }
        }

        // 3) Tổng phí (nhập dương) và giảm giá (nhập dương, BE lưu negative)
        BigDecimal totalFees = BigDecimal.ZERO;
        List<FeeLine> normalizedFees = new ArrayList<>();
        if (body.getFees() != null) {
            for (FeeLine f : body.getFees()) {
                if (f == null) continue;
                BigDecimal amt = f.getAmount() == null ? BigDecimal.ZERO : f.getAmount();
                if (amt.signum() < 0) throw new BusinessException("Phí không được âm");
                if (amt.signum() == 0) continue;
                String name = f.getName() == null ? "" : f.getName().trim();
                normalizedFees.add(new FeeLine(name.isEmpty() ? "Phí khác" : name, amt));
                totalFees = totalFees.add(amt);
            }
        }
        // Giảm giá: cùng cơ chế phân bổ, nhưng ÂM — lưu như 1 "fee" tên "Giảm giá".
        BigDecimal discount = body.getDiscount() == null ? BigDecimal.ZERO : body.getDiscount();
        if (discount.signum() < 0)
            throw new BusinessException("Giảm giá không được âm");
        if (discount.signum() > 0) {
            normalizedFees.add(new FeeLine("Giảm giá", discount.negate()));
            totalFees = totalFees.subtract(discount);
        }

        // 4) Tổng tiền hàng = Σ lineAmount
        BigDecimal subtotal = lineAmountByItem.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 5) Phân bổ phí theo tỉ trọng lineAmount và tính unitPrice cho từng vật dụng
        //    unitPrice = (lineAmount + feeShare) / qty.
        //    Nếu subtotal = 0 (giá 0) nhưng có phí → chia phí đều theo qty tổng.
        Map<Long, BigDecimal> unitPriceByItem = new HashMap<>();
        BigDecimal totalQty = qtyByItem.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Map.Entry<Long, BigDecimal> e : qtyByItem.entrySet()) {
            Long id = e.getKey();
            BigDecimal qty = e.getValue();
            if (qty == null || qty.signum() <= 0) {
                unitPriceByItem.put(id, BigDecimal.ZERO);
                continue;
            }
            BigDecimal lineAmt = lineAmountByItem.get(id);
            BigDecimal feeShare;
            if (subtotal.signum() > 0) {
                feeShare = totalFees.multiply(lineAmt)
                        .divide(subtotal, 6, RoundingMode.HALF_UP);
            } else if (totalQty.signum() > 0) {
                feeShare = totalFees.multiply(qty)
                        .divide(totalQty, 6, RoundingMode.HALF_UP);
            } else {
                feeShare = BigDecimal.ZERO;
            }
            BigDecimal unitPrice = lineAmt.add(feeShare)
                    .divide(qty, 4, RoundingMode.HALF_UP);
            unitPriceByItem.put(id, unitPrice);
        }

        // 6) Tạo OfficeSupplyOrder
        OfficeSupplyOrder order = OfficeSupplyOrder.builder()
                .warehouse(wh)
                .placedAt(System.currentTimeMillis())
                .placedByName(actor.getFullName())
                .subtotalAmount(subtotal)
                .feesAmount(totalFees)
                .feesJson(writeFeesJson(normalizedFees))
                .build();

        List<OfficeSupplyOrderItem> orderItems = new ArrayList<>();
        for (OfficeSupplyRequest req : requests) {
            for (OfficeSupplyRequestItem line : req.getItems()) {
                Long id = line.getSupplyItem().getId();
                orderItems.add(OfficeSupplyOrderItem.builder()
                        .order(order)
                        .supplyItem(line.getSupplyItem())
                        .userId(req.getUser().getId())
                        .userFullName(req.getUser().getFullName())
                        .userPosition(req.getUser().getPosition())
                        .quantity(line.getQuantity())
                        .unitPrice(unitPriceByItem.get(id))
                        .note(line.getNote())
                        .build());
            }
        }
        order.setItems(orderItems);
        orderRepo.save(order);

        // Clear toàn bộ request
        requestRepo.deleteAll(requests);

        log.info("[OfficeSupply] Đặt hàng VPP văn phòng {} — {} request, {} dòng bởi {} (subtotal={}, fees={})",
                wh.getName(), requests.size(), orderItems.size(), actor.getFullName(),
                subtotal, totalFees);

        return toHistoryDto(order);
    }

    private String writeFeesJson(List<FeeLine> fees) {
        if (fees == null || fees.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(fees); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unused")
    private List<FeeLine> readFeesJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<FeeLine>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lịch sử giá — biểu đồ biến động theo vật dụng
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lịch sử giá của 1 vật dụng ở 1 văn phòng. Chỉ các lần đặt có {@code unitPrice}
     * khác null mới được tính vào thống kê.
     *
     * <p>Trung vị tính theo chuẩn: với n lẻ → phần tử giữa; với n chẵn → trung bình 2 giữa.
     */
    @Transactional(readOnly = true)
    public PriceHistoryDto priceHistory(Long warehouseId, Long supplyItemId) {
        SupplyItem si = itemRepo.findById(supplyItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Vật dụng không tồn tại"));

        List<OfficeSupplyOrder> orders = orderRepo.findAllWithItemsByWarehouseId(warehouseId);

        // Gom theo order: mỗi order 1 point (qty = tổng SL item này trong đơn, unitPrice chung).
        // Dòng OrderItem đã có cùng unitPrice cho mỗi item/order nên lấy max() cho an toàn.
        List<PriceHistoryPoint> points = new ArrayList<>();
        for (OfficeSupplyOrder o : orders) {
            BigDecimal qtySum = BigDecimal.ZERO;
            BigDecimal unitPrice = null;
            for (var oi : o.getItems()) {
                if (!Objects.equals(oi.getSupplyItem().getId(), supplyItemId)) continue;
                if (oi.getQuantity() != null) qtySum = qtySum.add(oi.getQuantity());
                if (oi.getUnitPrice() != null) unitPrice = oi.getUnitPrice();
            }
            if (qtySum.signum() <= 0 || unitPrice == null) continue;
            points.add(PriceHistoryPoint.builder()
                    .orderId(o.getId())
                    .placedAt(o.getPlacedAt())
                    .quantity(qtySum)
                    .unitPrice(unitPrice)
                    .build());
        }

        // Sort theo thời gian tăng dần — tiện vẽ chart
        points.sort(Comparator.comparingLong(PriceHistoryPoint::getPlacedAt));

        PriceStats stats = null;
        if (!points.isEmpty()) {
            PriceHistoryPoint min = points.stream()
                    .min(Comparator.comparing(PriceHistoryPoint::getUnitPrice)).get();
            PriceHistoryPoint max = points.stream()
                    .max(Comparator.comparing(PriceHistoryPoint::getUnitPrice)).get();
            PriceHistoryPoint last = points.get(points.size() - 1);

            BigDecimal sum = points.stream().map(PriceHistoryPoint::getUnitPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(points.size()), 2, RoundingMode.HALF_UP);

            List<BigDecimal> sorted = points.stream().map(PriceHistoryPoint::getUnitPrice)
                    .sorted().toList();
            BigDecimal median;
            int n = sorted.size();
            if (n % 2 == 1) median = sorted.get(n / 2).setScale(2, RoundingMode.HALF_UP);
            else median = sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

            stats = PriceStats.builder()
                    .minPrice(min.getUnitPrice()).minAt(min.getPlacedAt()).minQty(min.getQuantity())
                    .maxPrice(max.getUnitPrice()).maxAt(max.getPlacedAt()).maxQty(max.getQuantity())
                    .avgPrice(avg).medianPrice(median)
                    .lastPrice(last.getUnitPrice()).lastAt(last.getPlacedAt()).lastQty(last.getQuantity())
                    .sampleCount(points.size())
                    .build();
        }

        return PriceHistoryDto.builder()
                .supplyItemId(si.getId())
                .name(si.getName())
                .specification(si.getSpecification())
                .unit(si.getUnit())
                .points(points)
                .stats(stats)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lịch sử đặt hàng
    // ─────────────────────────────────────────────────────────────────────────

    /** Lịch sử đặt hàng của 1 văn phòng — mới nhất trước. */
    @Transactional(readOnly = true)
    public List<OrderHistoryDto> orderHistory(Long warehouseId) {
        return orderRepo.findAllByWarehouseIdOrderByPlacedAtDesc(warehouseId)
                .stream().map(this::toHistoryDto).collect(Collectors.toList());
    }

    /** Chi tiết 1 lần đặt hàng. */
    @Transactional(readOnly = true)
    public OrderDetailDto orderDetail(Long orderId) {
        OfficeSupplyOrder order = orderRepo.findByIdWithItems(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại"));
        return toDetailDto(order);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Báo cáo vật dụng
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Báo cáo theo từng vật dụng: số lần mua, ngày gần nhất, khoảng thời gian TB
     * giữa 2 lần đặt, số lượng TB mỗi lần đặt.
     *
     * <p>Không dùng {@code statsByItemAndWarehouse} nữa (chỉ đưa được MAX/SUM/COUNT).
     * Ta cần TẤT CẢ mốc {@code placedAt} để tính khoảng cách trung bình → nạp
     * hết order kèm items rồi group ở app-side. Đơn hàng VPP không nhiều nên
     * việc này rẻ và code rõ hơn viết SQL cửa sổ.
     */
    @Transactional(readOnly = true)
    public List<ItemReportRowDto> itemReport(Long warehouseId) {
        List<SupplyItem> items = itemRepo.findByDeletedAtIsNullOrderByNameAsc();
        List<com.nhatnam.server.entity.OfficeSupplyOrder> orders =
                orderRepo.findAllWithItemsByWarehouseId(warehouseId);

        // Gom theo item: mỗi item 1 danh sách (placedAt, quantity) — nạp thẳng
        // vào rowsByItem, không cần map trung gian.
        class Row { final long at; final BigDecimal qty;
            Row(long at, BigDecimal qty) { this.at = at; this.qty = qty; } }
        Map<Long, List<Row>> rowsByItem = new HashMap<>();

        for (var o : orders) {
            long at = o.getPlacedAt();
            for (var oi : o.getItems()) {
                Long itemId = oi.getSupplyItem().getId();
                rowsByItem.computeIfAbsent(itemId, k -> new ArrayList<>())
                        .add(new Row(at, oi.getQuantity() != null ? oi.getQuantity() : BigDecimal.ZERO));
            }
        }

        List<ItemReportRowDto> out = new ArrayList<>();
        for (SupplyItem it : items) {
            List<Row> rows = rowsByItem.get(it.getId());
            if (rows == null || rows.isEmpty()) continue;

            // Số lần đặt = số ORDER khác nhau có chứa item này (không phải số dòng)
            long orderCount = rows.stream().mapToLong(r -> r.at).distinct().count();
            long minAt      = rows.stream().mapToLong(r -> r.at).min().getAsLong();
            long maxAt      = rows.stream().mapToLong(r -> r.at).max().getAsLong();
            BigDecimal total = rows.stream().map(r -> r.qty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Khoảng thời gian trung bình giữa 2 lần đặt (ngày). Cần ≥ 2 mốc.
            Double avgIntervalDays = null;
            if (orderCount >= 2) {
                double days = (maxAt - minAt) / 86_400_000.0;
                avgIntervalDays = days / (orderCount - 1);
            }

            // Số lượng trung bình mỗi lần đặt
            BigDecimal avgQty = null;
            if (orderCount > 0) {
                avgQty = total.divide(BigDecimal.valueOf(orderCount), 3,
                        java.math.RoundingMode.HALF_UP);
            }

            out.add(ItemReportRowDto.builder()
                    .supplyItemId(it.getId())
                    .name(it.getName())
                    .specification(it.getSpecification())
                    .unit(it.getUnit())
                    .orderCount(orderCount)
                    .lastOrderedAt(maxAt)
                    .totalQuantity(total)
                    .avgIntervalDays(avgIntervalDays)
                    .avgQuantityPerOrder(avgQty)
                    .build());
        }

        out.sort(Comparator.comparing(ItemReportRowDto::getOrderCount).reversed());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Admin: CRUD danh mục vật dụng
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Danh sách vật dụng đầy đủ cho trang "Danh sách văn phòng phẩm" (Owner add/edit).
     * Không kèm tồn kho — module VPP không dùng kho vật lý.
     */
    @Transactional(readOnly = true)
    public List<AdminItemDto> adminListItems() {
        return itemRepo.findByDeletedAtIsNullOrderByNameAsc().stream()
                .map(it -> AdminItemDto.builder()
                        .id(it.getId())
                        .name(it.getName())
                        .specification(it.getSpecification())
                        .unit(it.getUnit())
                        .createdAt(it.getCreatedAt())
                        .updatedAt(it.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Tạo mới hoặc sửa vật dụng. Tận dụng {@code SupplyItemService.getOrCreate}
     * để đảm bảo unique theo bộ ba đã chuẩn hoá (tên, quy cách, ĐVT).
     */
    @Transactional
    public AdminItemDto adminCreateItem(SaveItemBody body, SupplyItemService supplyItemService) {
        if (body == null || body.getName() == null || body.getName().isBlank())
            throw new com.nhatnam.server.common.BusinessException("Tên vật dụng là bắt buộc");
        if (body.getUnit() == null || body.getUnit().isBlank())
            throw new com.nhatnam.server.common.BusinessException("Đơn vị tính là bắt buộc");

        SupplyItem it = supplyItemService.getOrCreate(
                body.getName(), body.getSpecification(), body.getUnit());
        return AdminItemDto.builder()
                .id(it.getId())
                .name(it.getName())
                .specification(it.getSpecification())
                .unit(it.getUnit())
                .createdAt(it.getCreatedAt())
                .updatedAt(it.getUpdatedAt())
                .build();
    }

    /**
     * Sửa NHÃN HIỂN THỊ (name/spec/unit) của item.
     *
     * <p>KHÁC {@code getOrCreate}: getOrCreate là "tìm-hoặc-tạo" dùng khi lưu phiếu,
     * còn ở đây Owner đang chủ đích SỬA bản ghi hiện có. Nếu bộ ba mới đụng bản
     * ghi khác thì báo lỗi để Owner đi con đường merge thay vì tạo double.
     */
    @Transactional
    public AdminItemDto adminUpdateItem(Long id, SaveItemBody body) {
        if (body == null || body.getName() == null || body.getName().isBlank())
            throw new com.nhatnam.server.common.BusinessException("Tên vật dụng là bắt buộc");
        if (body.getUnit() == null || body.getUnit().isBlank())
            throw new com.nhatnam.server.common.BusinessException("Đơn vị tính là bắt buộc");

        SupplyItem it = itemRepo.findById(id)
                .orElseThrow(() -> new com.nhatnam.server.common.ResourceNotFoundException(
                        "Vật dụng không tồn tại"));
        if (it.getDeletedAt() != null)
            throw new com.nhatnam.server.common.BusinessException("Vật dụng đã bị xoá / gộp");

        String nameNorm = SupplyItem.normalize(body.getName());
        String specNorm = SupplyItem.normalize(body.getSpecification());
        String unitNorm = SupplyItem.normalizeUnit(body.getUnit());

        // Nếu bộ ba mới khác bộ ba hiện tại, kiểm tra không đụng bản ghi khác
        if (!nameNorm.equals(it.getNameNormalized())
                || !specNorm.equals(it.getSpecNormalized())
                || !unitNorm.equals(it.getUnitNormalized())) {
            itemRepo.findByNameNormalizedAndSpecNormalizedAndUnitNormalizedAndDeletedAtIsNull(
                            nameNorm, specNorm, unitNorm)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw new com.nhatnam.server.common.BusinessException(
                                "Đã có vật dụng khác cùng (tên, quy cách, ĐVT). "
                                        + "Nếu cần hợp nhất hãy dùng chức năng Gộp.");
                    });
        }

        it.setName(body.getName().trim().replaceAll("\\s+", " "));
        it.setSpecification(body.getSpecification() == null || body.getSpecification().isBlank()
                ? null : body.getSpecification().trim().replaceAll("\\s+", " "));
        it.setUnit(body.getUnit().trim().replaceAll("\\s+", " "));
        it.setNameNormalized(nameNorm);
        it.setSpecNormalized(specNorm);
        it.setUnitNormalized(unitNorm);
        itemRepo.save(it);

        return AdminItemDto.builder()
                .id(it.getId())
                .name(it.getName())
                .specification(it.getSpecification())
                .unit(it.getUnit())
                .createdAt(it.getCreatedAt())
                .updatedAt(it.getUpdatedAt())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mappers
    // ─────────────────────────────────────────────────────────────────────────

    private MyRequestDto toMyRequestDto(OfficeSupplyRequest req) {
        return MyRequestDto.builder()
                .warehouseId(req.getWarehouse().getId())
                .warehouseName(req.getWarehouse().getName())
                .items(req.getItems().stream().map(i -> RequestItemDto.builder()
                        .supplyItemId(i.getSupplyItem().getId())
                        .name(i.getSupplyItem().getName())
                        .specification(i.getSupplyItem().getSpecification())
                        .unit(i.getSupplyItem().getUnit())
                        .quantity(i.getQuantity())
                        .note(i.getNote())
                        .build()).collect(Collectors.toList()))
                .updatedAt(req.getUpdatedAt())
                .build();
    }

    private OrderHistoryDto toHistoryDto(OfficeSupplyOrder o) {
        BigDecimal totalQty = o.getItems().stream()
                .map(OfficeSupplyOrderItem::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long itemTypes = o.getItems().stream()
                .map(i -> i.getSupplyItem().getId()).distinct().count();
        BigDecimal subtotal = o.getSubtotalAmount();
        BigDecimal fees = o.getFeesAmount();
        BigDecimal total = (subtotal == null ? BigDecimal.ZERO : subtotal)
                .add(fees == null ? BigDecimal.ZERO : fees);
        return OrderHistoryDto.builder()
                .id(o.getId())
                .warehouseId(o.getWarehouse().getId())
                .warehouseName(o.getWarehouse().getName())
                .placedAt(o.getPlacedAt())
                .placedByName(o.getPlacedByName())
                .itemTypeCount((int) itemTypes)
                .totalQuantity(totalQty)
                .subtotalAmount(subtotal)
                .feesAmount(fees)
                .totalAmount(total)
                .build();
    }

    private OrderDetailDto toDetailDto(OfficeSupplyOrder o) {
        return OrderDetailDto.builder()
                .id(o.getId())
                .warehouseId(o.getWarehouse().getId())
                .warehouseName(o.getWarehouse().getName())
                .placedAt(o.getPlacedAt())
                .placedByName(o.getPlacedByName())
                .items(o.getItems().stream().map(i -> OrderDetailItemDto.builder()
                        .userId(i.getUserId())
                        .userFullName(i.getUserFullName())
                        .userPosition(i.getUserPosition())
                        .supplyItemId(i.getSupplyItem().getId())
                        .itemName(i.getSupplyItem().getName())
                        .unit(i.getSupplyItem().getUnit())
                        .specification(i.getSupplyItem().getSpecification())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .note(i.getNote())
                        .build()).collect(Collectors.toList()))
                .build();
    }
}