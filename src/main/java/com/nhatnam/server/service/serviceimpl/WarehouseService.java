package com.nhatnam.server.service.serviceimpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.WarehouseDTO.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.entity.Warehouse.WarehouseType;
import com.nhatnam.server.entity.WarehouseReceipt.CostStatus;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import com.nhatnam.server.entity.WarehouseReceiptItem.AdjustResult;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.FifoDeductService;
import com.nhatnam.server.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseService {

    private final WarehouseRepository        warehouseRepository;
    private final WarehouseReceiptRepository warehouseReceiptRepository;
    private final IngredientExpiryRepository ingredientExpiryRepository;
    private final FifoDeductService          fifoDeductService;
    private final IngredientRepository       ingredientRepository;
    private final UserRepository             userRepository;
    private final ObjectMapper               objectMapper;
    private final IngredientStockRepository  ingredientStockRepository;
    private final NotificationService        notificationService;
    // Mục 4.2 — chuyển kho sang kho sản xuất (xưởng)
    private final com.nhatnam.server.repository.ProductionFactoryRepository productionFactoryRepository;
    private final com.nhatnam.server.repository.FactoryMaterialStockRepository factoryMaterialStockRepository;
    private final com.nhatnam.server.repository.IngredientWarehouseRepository ingredientWarehouseRepository;
    private final com.nhatnam.server.repository.FinishedGoodsStockRepository finishedGoodsStockRepository;
    // Điều chỉnh tồn theo LÔ — lô mới cần kế toán trưởng nhập giá vốn
    private final com.nhatnam.server.repository.LotPricingRequestRepository lotPricingRequestRepository;

    // ════════════════════════════════════════════════════════════════
    // EXPIRY LOT HELPERS
    // Thay đổi: dùng warehouseId + ingredientId thay vì Warehouse + Ingredient object
    // ════════════════════════════════════════════════════════════════

    private void adjustExpiryLot(Warehouse warehouse, Ingredient ingredient,
                                 BigDecimal delta, long now) {
        if (delta.compareTo(BigDecimal.ZERO) == 0) return;
        Long warehouseId = warehouse.getId();
        Long ingredientId = ingredient.getId();

        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            List<IngredientExpiry> allLots = ingredientExpiryRepository
                    .findByWarehouseIdAndIngredientIdOrderByExpiryDateAsc(warehouseId, ingredientId);

            BigDecimal costPrice = ingredientExpiryRepository
                    .findByWarehouseIdAndIngredientIdOrderByCreatedAtAsc(warehouseId, ingredientId)
                    .stream().findFirst().map(IngredientExpiry::getCostPrice)
                    .orElse(BigDecimal.ZERO);

            if (!allLots.isEmpty()) {
                IngredientExpiry soonest = allLots.get(0);
                soonest.setQuantity(soonest.getQuantity().add(delta));
                soonest.setUpdatedAt(now);
                ingredientExpiryRepository.save(soonest);
            } else {
                ingredientExpiryRepository.save(IngredientExpiry.builder()
                        .warehouse(warehouse)
                        .ingredientId(ingredientId)       // ← plain id
                        .expiryDate(null)
                        .costPrice(costPrice)
                        .quantity(delta)
                        .createdAt(now).updatedAt(now)
                        .build());
            }
        } else {
            BigDecimal toDeduct = delta.abs();
            List<IngredientExpiry> lots = ingredientExpiryRepository
                    .findAvailableLotsOrderByExpiryAsc(warehouseId, ingredientId);

            for (IngredientExpiry lot : lots) {
                if (toDeduct.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal take = lot.getQuantity().min(toDeduct);
                lot.setQuantity(lot.getQuantity().subtract(take));
                lot.setUpdatedAt(now);
                // Không xóa cứng lô dù về 0 — OrderStockDeduction.ingredient_expiry_id có thể
                // đang tham chiếu tới lô này (lịch sử xuất kho cho đơn hàng), xóa sẽ vi phạm FK.
                // Giữ lại bản ghi với quantity=0, nhất quán với cách FifoDeductService xử lý.
                ingredientExpiryRepository.save(lot);
                toDeduct = toDeduct.subtract(take);
            }
        }
    }

    /**
     * HSD (ms) sớm nhất trong các lô còn hàng của 1 nguyên liệu tại 1 kho — dùng để
     * mang HSD sang lô kho xưởng khi chuyển kho hàng → xưởng. Trả về null nếu không
     * lô nào có HSD.
     */
    private Long earliestExpiryMs(Warehouse warehouse, Ingredient ing) {
        return ingredientExpiryRepository
                .findFifoLots(warehouse.getId(), ing.getId()).stream()
                .filter(l -> l.getQuantity() != null && l.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .map(IngredientExpiry::getExpiryDate)
                .filter(Objects::nonNull)
                .map(d -> d.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli())
                .min(Long::compareTo)
                .orElse(null);
    }

    /**
     * Chuyển lô theo FIFO từ kho nguồn sang kho đích.
     *
     * @return HẠN SỬ DỤNG XA NHẤT trong số các lô đã chuyển (null nếu không lô nào có HSD).
     *         Giá trị này được ghi vào phiếu để in "Giấy thông tin nguồn gốc động vật":
     *         một dòng nguyên liệu có thể lấy từ nhiều lô, phiếu đi đường chỉ ghi 1 hạn —
     *         lấy hạn xa nhất theo yêu cầu nghiệp vụ.
     */
    private LocalDate transferExpiryLots(Warehouse source, Warehouse dest,
                                    Ingredient ingredient, BigDecimal qty, long now) {
        Long srcId = source.getId();
        Long dstId = dest.getId();
        Long ingId = ingredient.getId();

        List<IngredientExpiry> sourceLots = ingredientExpiryRepository
                .findAvailableLotsOrderByExpiryAsc(srcId, ingId);

        BigDecimal remaining = qty;
        LocalDate furthestExpiry = null;
        for (IngredientExpiry lot : sourceLots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = lot.getQuantity().min(remaining);
            if (lot.getExpiryDate() != null
                    && (furthestExpiry == null || lot.getExpiryDate().isAfter(furthestExpiry)))
                furthestExpiry = lot.getExpiryDate();

            lot.setQuantity(lot.getQuantity().subtract(take));
            lot.setUpdatedAt(now);
            // Không xóa cứng lô nguồn dù về 0 — OrderStockDeduction.ingredient_expiry_id có thể
            // đang tham chiếu tới lô này (lịch sử xuất kho cho đơn hàng), xóa sẽ vi phạm khóa ngoại
            // (lỗi "Cannot delete or update a parent row"). Giữ lại bản ghi với quantity=0,
            // nhất quán với cách FifoDeductService xử lý khi trừ kho cho đơn hàng.
            ingredientExpiryRepository.save(lot);

            final LocalDate expDate = lot.getExpiryDate();
            final BigDecimal cost   = lot.getCostPrice();

            // MỖI LẦN CHUYỂN KHO = MỘT LÔ MỚI ở kho đích, kể cả khi kho đích đã có
            // lô cùng HSD + giá vốn.
            //
            //   Bản cũ cộng dồn vào lô sẵn có. Điều đó vừa làm mất dấu vết đợt
            //   chuyển, vừa gây lỗi "query did not return a unique result" khi kho
            //   đích đã lỡ có 2 lô trùng — bảng không có ràng buộc duy nhất trên
            //   bộ (kho, nguyên liệu, HSD, giá vốn).
            //
            //   Một lần chuyển nhiều lô nguồn sẽ sinh ra đúng bấy nhiêu lô đích,
            //   giữ nguyên tương ứng 1-1 để truy vết được lô nào từ lô nào.
            ingredientExpiryRepository.save(IngredientExpiry.builder()
                    .warehouse(dest)
                    .ingredientId(ingId)              // ← plain id
                    .expiryDate(expDate).costPrice(cost)
                    .quantity(take)
                    .createdAt(now).updatedAt(now)
                    .build());
            remaining = remaining.subtract(take);
        }
        return furthestExpiry;
    }

    // ── WAREHOUSE CRUD ────────────────────────────────────────────────────────

    public List<WarehouseResponse> getAllWarehouses() {
        return warehouseRepository.findByActiveTrue()
                .stream().map(this::mapWarehouse).collect(Collectors.toList());
    }

    @Transactional
    public WarehouseResponse createWarehouse(CreateWarehouseRequest req) {
        long now = System.currentTimeMillis();
        Warehouse w = Warehouse.builder()
                .name(req.getName()).address(req.getAddress())
                .type(req.getType()).active(true)
                .createdAt(now).updatedAt(now).build();
        return mapWarehouse(warehouseRepository.save(w));
    }

    @Transactional
    public WarehouseResponse updateWarehouse(Long id, CreateWarehouseRequest req) {
        Warehouse w = warehouseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Warehouse not found: " + id));
        w.setName(req.getName()); w.setAddress(req.getAddress()); w.setType(req.getType());
        w.setUpdatedAt(System.currentTimeMillis());
        return mapWarehouse(warehouseRepository.save(w));
    }

    // ── TỒN KHO ──────────────────────────────────────────────────────────────

    public List<StockResponse> getStockByWarehouse(Long warehouseId) {
        // IngredientStock.ingredientId là plain column — không cần JOIN FETCH ingredient
        List<IngredientStock> stocks = ingredientStockRepository.findByWarehouseIdWithIngredient(warehouseId);

        // CHỈ GIỮ NGUYÊN LIỆU ĐÃ ĐƯỢC GÁN CHO KHO NÀY (bảng ingredient_warehouse).
        //
        // Bản ghi IngredientStock có thể còn sót lại sau khi nguyên liệu bị gỡ
        // khỏi kho (tồn 0, hoặc do chuyển kho trong quá khứ). Trước đây chúng vẫn
        // hiện trên màn Quản lý kho khiến thủ kho thấy những nguyên liệu không
        // thuộc phạm vi của mình. Trang kho của OWNER/ADMIN đã lọc như vậy từ
        // trước — thêm ở đây để hai bên nhìn thấy cùng một danh sách.
        Set<Long> registeredIngIds =
                new java.util.HashSet<>(ingredientWarehouseRepository.findIngredientIdsByWarehouseId(warehouseId));
        stocks = stocks.stream()
                .filter(s -> registeredIngIds.contains(s.getIngredientId()))
                .collect(Collectors.toList());

        // Lấy Ingredient records để lấy tên/đơn vị/ảnh (chỉ active mới hiển thị)
        Set<Long> ingIds = stocks.stream().map(IngredientStock::getIngredientId).collect(Collectors.toSet());
        Map<Long, Ingredient> ingredientMap = ingredientRepository.findAllById(ingIds)
                .stream().collect(Collectors.toMap(Ingredient::getId, i -> i));

        List<IngredientExpiry> allExpiry = ingredientExpiryRepository.findByWarehouseId(warehouseId);
        // IngredientExpiry.ingredientId là plain column
        Map<Long, List<IngredientExpiry>> expiryMap = allExpiry.stream()
                .collect(Collectors.groupingBy(IngredientExpiry::getIngredientId));

        return stocks.stream().map(s -> {
            StockResponse r = new StockResponse();
            r.setIngredientId(s.getIngredientId());

            // Ưu tiên dùng Ingredient object nếu còn active, fallback về snapshot
            Ingredient ing = ingredientMap.get(s.getIngredientId());
            r.setIngredientName(ing != null ? ing.getName()     : s.resolvedName());
            r.setUnit         (ing != null ? ing.getUnit()     : s.resolvedUnit());
            r.setImageUrl     (ing != null ? ing.getImageUrl() : null);
            r.setStockQuantity(s.getStockQuantity());

            java.time.LocalDate today = java.time.LocalDate.now();
            List<IngredientExpiry> lots = expiryMap
                    .getOrDefault(s.getIngredientId(), List.of())
                    .stream()
                    .filter(e -> e.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                    // Sắp theo hạn gần nhất trước; lô không hạn xếp cuối.
                    .sorted(Comparator.comparing(IngredientExpiry::getExpiryDate,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());

            List<ExpiryInfo> expiryList = lots.stream().map(e -> {
                ExpiryInfo ei = new ExpiryInfo();
                ei.setId(e.getId());
                ei.setExpiryDate(e.getExpiryDate());
                ei.setQuantity(e.getQuantity());
                ei.setImportedAt(e.getCreatedAt());
                ei.setCostPrice(e.getCostPrice());
                ei.setLotCost(e.getCostPrice() != null
                        ? e.getQuantity().multiply(e.getCostPrice()).setScale(2, java.math.RoundingMode.HALF_UP)
                        : null);
                ei.setTracked(true);
                return ei;
            }).collect(Collectors.toList());

            // Dòng bù phần tồn chưa gắn lô, để tổng lô khớp stockQuantity.
            BigDecimal trackedQty = lots.stream()
                    .map(IngredientExpiry::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal stockQty = s.getStockQuantity() != null ? s.getStockQuantity() : BigDecimal.ZERO;
            BigDecimal untracked = stockQty.subtract(trackedQty);
            if (untracked.compareTo(BigDecimal.ZERO) > 0) {
                ExpiryInfo ei = new ExpiryInfo();
                ei.setQuantity(untracked);
                ei.setTracked(false);
                expiryList.add(ei);
            }

            r.setExpiryList(expiryList);
            r.setFreshnessBadge(computeFreshnessBadge(lots, today));
            return r;
        }).collect(Collectors.toList());
    }

    /** Dưới 7 ngày = cực gấp; nhập trong 30 ngày = hàng mới. */
    private static final int FRESH_DAYS_CRITICAL = 7;
    private static final int FRESH_DAYS_DANGER = 30;
    private static final int FRESH_DAYS_NEW = 30;

    /**
     * MÀU TÌNH TRẠNG lô, ưu tiên gắt nhất thắng:
     * EXPIRED_OR_CRITICAL (đỏ cam) &gt; NEAR_EXPIRY (vàng) &gt; NEWLY_STOCKED (xanh) &gt; NONE.
     * Cùng logic với trang kho của OWNER/ADMIN để mọi nơi nhất quán.
     */
    private String computeFreshnessBadge(List<IngredientExpiry> lots, java.time.LocalDate today) {
        boolean hasCritical = false, hasNear = false, hasNew = false;
        long newFloorMs = today.minusDays(FRESH_DAYS_NEW)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (IngredientExpiry lot : lots) {
            java.time.LocalDate exp = lot.getExpiryDate();
            if (exp != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(today, exp);
                if (days < FRESH_DAYS_CRITICAL) hasCritical = true;
                else if (days <= FRESH_DAYS_DANGER) hasNear = true;
            }
            if (lot.getCreatedAt() != null && lot.getCreatedAt() >= newFloorMs) hasNew = true;
        }
        if (hasCritical) return "EXPIRED_OR_CRITICAL";
        if (hasNear)     return "NEAR_EXPIRY";
        if (hasNew)      return "NEWLY_STOCKED";
        return "NONE";
    }

    // ── NHẬP KHO ─────────────────────────────────────────────────────────────
    //
    // FLOW MỚI: nhập kho là CỘNG TỒN NGAY.
    //   1. Cộng IngredientStock.stockQuantity
    //   2. Tạo LÔ IngredientExpiry riêng cho từng dòng (theo HSD), costPrice tạm = 0
    //      → lưu id lô vào WarehouseReceiptItem.ingredientExpiryId để bước nhập giá vốn
    //        biết chính xác lô nào cần cập nhật.
    //   3. Phiếu ở trạng thái PENDING_COST → kế toán trưởng nhập giá vốn thật sau.
    //
    // Cố tình KHÔNG gộp vào lô có sẵn (dù trùng HSD + giá vốn 0): mỗi lần nhập là một
    // lô độc lập, để khi cập nhật giá vốn không làm sai giá của các lần nhập khác.
    @Transactional
    public ReceiptResponse importStock(ImportRequest req, Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse warehouse = getWarehouse(req.getWarehouseId());

        if (req.getItems() == null || req.getItems().isEmpty())
            throw new com.nhatnam.server.common.BusinessException("Phiếu nhập kho phải có ít nhất 1 nguyên liệu.");

        WarehouseReceipt receipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("IMP"))
                .receiptType(ReceiptType.IMPORT)
                .costStatus(CostStatus.PENDING_COST)
                .warehouse(warehouse)
                .referenceCode(req.getReferenceCode())
                .note(req.getNote())
                .imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now)
                .items(new ArrayList<>())
                .build();

        for (ImportItemRequest ir : req.getItems()) {
            Ingredient ing = getIngredient(ir.getIngredientId());
            BigDecimal qty = ir.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0)
                throw new com.nhatnam.server.common.BusinessException(
                        "Số lượng nhập phải lớn hơn 0: " + ing.getName());

            // 1) Cộng tồn kho tổng
            IngredientStock stock = getOrCreateStock(warehouse, ing, now);
            BigDecimal before = stock.getStockQuantity();
            BigDecimal after  = before.add(qty);
            stock.setStockQuantity(after);
            stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);

            // 2) Tạo lô HSD mới với GIÁ VỐN TẠM = 0 (chưa biết giá thật)
            IngredientExpiry lot = ingredientExpiryRepository.save(IngredientExpiry.builder()
                    .warehouse(warehouse)
                    .ingredientId(ing.getId())
                    .expiryDate(ir.getExpiryDate())
                    .quantity(qty)
                    .costPrice(BigDecimal.ZERO)
                    .createdAt(now).updatedAt(now)
                    .build());

            receipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(receipt)
                    .ingredientId(ing.getId())                    // ← plain id
                    .ingredientNameSnapshot(ing.getName())        // ← snapshot
                    .ingredientUnitSnapshot(ing.getUnit())        // ← snapshot
                    .ingredientImageUrlSnapshot(ing.getImageUrl())// ← snapshot
                    .quantity(qty)
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .difference(qty)
                    .expiryDate(ir.getExpiryDate())
                    .unitPrice(null)
                    .allocatedFee(null)
                    .costPrice(null)                              // chưa có giá vốn
                    .ingredientExpiryId(lot.getId())
                    .build());
        }

        WarehouseReceipt saved = warehouseReceiptRepository.save(receipt);
        notifyImportPendingCost(saved, warehouse);
        return mapReceipt(saved);
    }

    private void notifyImportPendingCost(WarehouseReceipt receipt, Warehouse warehouse) {
        try {
            String message = String.format("Phiếu nhập kho %s (%s) cần nhập giá vốn",
                    receipt.getReceiptCode(), warehouse.getName());
            String payload = String.format(
                    "{\"receiptId\":%d,\"receiptCode\":\"%s\",\"warehouseName\":\"%s\"}",
                    receipt.getId(), receipt.getReceiptCode(), warehouse.getName());
            List<User> targets = userRepository.findByRoleAndIsLockAccountFalse(Role.SUPER_ACCOUNTANT);
            for (User u : targets)
                notificationService.sendToUser(u, "SUPER_ACCOUNTANT", "IMPORT_PENDING_COST", message, payload);
        } catch (Exception e) {
            log.warn("[IMPORT] Failed to send notification: {}", e.getMessage());
        }
    }

    // ── XUẤT KHO ─────────────────────────────────────────────────────────────

    @Transactional
    public ReceiptResponse exportStock(ExportRequest req, Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse warehouse = getWarehouse(req.getWarehouseId());

        if (warehouse.getType() == WarehouseType.TRANSIT)
            throw new RuntimeException("Kho trung chuyển không được phép xuất bán.");
        if (req.getReason() == null || req.getReason().isBlank())
            throw new RuntimeException("Vui lòng nhập lý do xuất kho.");

        WarehouseReceipt receipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("EXP"))
                .receiptType(ReceiptType.EXPORT_OTHER)
                .costStatus(CostStatus.CONFIRMED)
                .warehouse(warehouse)
                .reason(req.getReason()).note(req.getNote())
                .imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now)
                .items(new ArrayList<>())
                .build();

        for (ExportItemRequest er : req.getItems()) {
            Ingredient ing = getIngredient(er.getIngredientId());
            IngredientStock stock = getOrCreateStock(warehouse, ing, now);
            checkSufficientStock(stock, er.getQuantity(), ing.getName());

            BigDecimal before = stock.getStockQuantity();
            BigDecimal after  = before.subtract(er.getQuantity());
            stock.setStockQuantity(after);
            stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);

            receipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(receipt)
                    .ingredientId(ing.getId())                    // ← plain id
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(er.getQuantity().negate())
                    .quantityBefore(before).quantityAfter(after)
                    .difference(after.subtract(before))
                    .build());
        }
        return mapReceipt(warehouseReceiptRepository.save(receipt));
    }

    // ── XUẤT KHO THEO ĐƠN HÀNG ───────────────────────────────────────────────

    @Transactional
    public ReceiptResponse exportForOrder(Long warehouseId, Order order,
                                          List<ExportItemRequest> items,
                                          Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse warehouse = getWarehouse(warehouseId);

        if (warehouse.getType() == WarehouseType.TRANSIT)
            throw new RuntimeException("Kho trung chuyển không được phép xuất bán.");

        WarehouseReceipt receipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("EXP"))
                .receiptType(ReceiptType.EXPORT_ORDER)
                .costStatus(CostStatus.CONFIRMED)
                .warehouse(warehouse).order(order)
                .referenceCode(order.getOrderCode())
                .note("Xuất kho theo đơn hàng: " + order.getOrderCode())
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now)
                .items(new ArrayList<>())
                .build();

        for (ExportItemRequest er : items) {
            Ingredient ing = getIngredient(er.getIngredientId());
            IngredientStock stock = getOrCreateStock(warehouse, ing, now);
            checkSufficientStock(stock, er.getQuantity(), ing.getName());

            BigDecimal before = stock.getStockQuantity();
            BigDecimal after  = before.subtract(er.getQuantity());
            stock.setStockQuantity(after);
            stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);

            fifoDeductService.deduct(warehouse, ing, er.getQuantity(), now);

            receipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(receipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(er.getQuantity().negate())
                    .quantityBefore(before).quantityAfter(after)
                    .difference(after.subtract(before))
                    .build());
        }
        return mapReceipt(warehouseReceiptRepository.save(receipt));
    }

    // ── CHUYỂN KHO ───────────────────────────────────────────────────────────

    @Transactional
    public TransferResponse transferStock(TransferRequest req, Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse fromWarehouse = getWarehouse(req.getFromWarehouseId());

        // Mục 4.2 — chuyển sang KHO SẢN XUẤT (xưởng): đích là ProductionFactory, không phải Warehouse
        if (req.getToProductionFactoryId() != null) {
            return transferToFactory(req, fromWarehouse, user, now);
        }

        // Mục 1 — chuyển sang KHO THÀNH PHẨM xưởng (kho bán/trung chuyển → kho TP xưởng)
        if (req.getToFinishedGoodsFactoryId() != null) {
            return transferToFinishedGoods(req, fromWarehouse, user, now);
        }

        Warehouse toWarehouse   = getWarehouse(req.getToWarehouseId());

        if (fromWarehouse.getId().equals(toWarehouse.getId()))
            throw new RuntimeException("Kho nguồn và kho đích không được trùng nhau.");

        String transferNote = String.format("Chuyển kho từ [%s] sang [%s]",
                fromWarehouse.getName(), toWarehouse.getName());
        String fullNote = req.getNote() != null && !req.getNote().isBlank()
                ? transferNote + " - " + req.getNote() : transferNote;

        WarehouseReceipt outReceipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("TRO")).receiptType(ReceiptType.TRANSFER_OUT)
                .costStatus(CostStatus.CONFIRMED).warehouse(fromWarehouse).partnerWarehouse(toWarehouse)
                .note(fullNote).imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();

        WarehouseReceipt inReceipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("TRI")).receiptType(ReceiptType.TRANSFER_IN)
                .costStatus(CostStatus.CONFIRMED).warehouse(toWarehouse).partnerWarehouse(fromWarehouse)
                .note(fullNote).imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();

        for (TransferItemRequest tr : req.getItems()) {
            Ingredient ing = getIngredient(tr.getIngredientId());

            IngredientStock fromStock = getOrCreateStock(fromWarehouse, ing, now);
            checkSufficientStock(fromStock, tr.getQuantity(), ing.getName());
            BigDecimal fromBefore = fromStock.getStockQuantity();
            BigDecimal fromAfter  = fromBefore.subtract(tr.getQuantity());
            fromStock.setStockQuantity(fromAfter); fromStock.setUpdatedAt(now);
            ingredientStockRepository.save(fromStock);

            IngredientStock toStock = getOrCreateStock(toWarehouse, ing, now);
            BigDecimal toBefore = toStock.getStockQuantity();
            BigDecimal toAfter  = toBefore.add(tr.getQuantity());
            toStock.setStockQuantity(toAfter); toStock.setUpdatedAt(now);
            ingredientStockRepository.save(toStock);

            // HSD xa nhất trong các lô được chuyển — dùng cho phiếu đi đường
            LocalDate furthestExpiry = transferExpiryLots(fromWarehouse, toWarehouse, ing, tr.getQuantity(), now);

            outReceipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(outReceipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(tr.getQuantity().negate())
                    .quantityBefore(fromBefore).quantityAfter(fromAfter)
                    .expiryDate(furthestExpiry)
                    .difference(fromAfter.subtract(fromBefore)).build());

            inReceipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(inReceipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(tr.getQuantity())
                    .quantityBefore(toBefore).quantityAfter(toAfter)
                    .expiryDate(furthestExpiry)
                    .difference(toAfter.subtract(toBefore)).build());
        }

        WarehouseReceipt savedOut = warehouseReceiptRepository.save(outReceipt);
        WarehouseReceipt savedIn  = warehouseReceiptRepository.save(inReceipt);
        savedOut.setLinkedReceiptId(savedIn.getId());
        savedIn.setLinkedReceiptId(savedOut.getId());
        warehouseReceiptRepository.save(savedOut);
        warehouseReceiptRepository.save(savedIn);

        TransferResponse res = new TransferResponse();
        res.setOutReceipt(mapReceipt(savedOut));
        res.setInReceipt(mapReceipt(savedIn));
        return res;
    }

    // ── Mục 4.2: Chuyển kho hàng → KHO SẢN XUẤT (xưởng) ───────────────────────
    // Kho hàng lưu Ingredient; kho xưởng lưu FactoryMaterial (2 domain tách biệt).
    // Quy ước ghép nối: theo TÊN nguyên liệu (không phân biệt hoa/thường, đã trim).
    // Chỉ chuyển được nguyên liệu mà kho xưởng đích đang có (FE đã lọc; BE chặn lại).
    @Transactional
    protected TransferResponse transferToFactory(TransferRequest req, Warehouse fromWarehouse,
                                                 User user, long now) {
        com.nhatnam.server.entity.ProductionFactory factory = productionFactoryRepository
                .findById(req.getToProductionFactoryId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy kho sản xuất (xưởng)."));

        // Tập tên nguyên liệu kho xưởng đang có
        List<FactoryMaterialStock> factoryStocks = factoryMaterialStockRepository
                .findByProductionFactory_IdAndIsActiveTrueOrderByCreatedAtAsc(factory.getId());
        java.util.Set<String> factoryNames = new java.util.HashSet<>();
        for (FactoryMaterialStock fs : factoryStocks)
            if (fs.getMaterialName() != null) factoryNames.add(fs.getMaterialName().trim().toLowerCase());

        String transferNote = String.format("Chuyển kho từ [%s] sang kho sản xuất [%s]",
                fromWarehouse.getName(), factory.getName());
        String fullNote = req.getNote() != null && !req.getNote().isBlank()
                ? transferNote + " - " + req.getNote() : transferNote;

        WarehouseReceipt outReceipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("TRO")).receiptType(ReceiptType.TRANSFER_OUT)
                .costStatus(CostStatus.CONFIRMED).warehouse(fromWarehouse).partnerWarehouse(null)
                .note(fullNote).imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();

        for (TransferItemRequest tr : req.getItems()) {
            Ingredient ing = getIngredient(tr.getIngredientId());
            String key = ing.getName() == null ? "" : ing.getName().trim().toLowerCase();
            if (!factoryNames.contains(key))
                throw new RuntimeException("Kho sản xuất [" + factory.getName()
                        + "] không có nguyên liệu: " + ing.getName());

            // Trừ tồn kho nguồn + giảm lô HSD nguồn (FIFO). deduct() trả về TỔNG giá vốn
            // đã trừ → chia số lượng để ra giá vốn/đơn vị mang sang kho xưởng.
            IngredientStock fromStock = getOrCreateStock(fromWarehouse, ing, now);
            checkSufficientStock(fromStock, tr.getQuantity(), ing.getName());
            BigDecimal fromBefore = fromStock.getStockQuantity();
            BigDecimal fromAfter  = fromBefore.subtract(tr.getQuantity());
            fromStock.setStockQuantity(fromAfter); fromStock.setUpdatedAt(now);
            ingredientStockRepository.save(fromStock);

            // Lấy HSD sớm nhất trong các lô sắp bị trừ (trước khi trừ) để mang sang kho xưởng
            Long carriedExpiry = earliestExpiryMs(fromWarehouse, ing);
            BigDecimal totalCost = fifoDeductService.deduct(fromWarehouse, ing, tr.getQuantity(), now);
            BigDecimal unitCost = (totalCost != null && tr.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                    ? totalCost.divide(tr.getQuantity(), 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // Cộng vào kho xưởng: tạo lô FactoryMaterialStock mới — GIỮ giá vốn + HSD
            factoryMaterialStockRepository.save(FactoryMaterialStock.builder()
                    .materialName(ing.getName())
                    .unit(ing.getUnit())
                    .quantity(tr.getQuantity())
                    .initialQuantity(tr.getQuantity())
                    .expiryDate(carriedExpiry)
                    .unitCost(unitCost)
                    .productionFactory(factory)
                    .isActive(true)
                    .createdAt(now).updatedAt(now)
                    .build());

            outReceipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(outReceipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(tr.getQuantity().negate())
                    .quantityBefore(fromBefore).quantityAfter(fromAfter)
                    .difference(fromAfter.subtract(fromBefore)).build());
        }

        WarehouseReceipt savedOut = warehouseReceiptRepository.save(outReceipt);
        TransferResponse res = new TransferResponse();
        res.setOutReceipt(mapReceipt(savedOut));
        res.setInReceipt(null);   // kho xưởng không tạo phiếu nhập kho hàng
        return res;
    }

    // ── Mục 1: Chuyển kho hàng → KHO THÀNH PHẨM xưởng ─────────────────────────
    // Điều kiện: kho thành phẩm xưởng đích ĐANG CÓ thành phẩm trùng tên nguyên liệu
    // (đối chiếu theo TÊN, không phân biệt hoa/thường). Tạo lô FinishedGoodsStock mới,
    // mang giá vốn + HSD từ lô nguồn (FIFO). Chỉ tạo phiếu xuất bên kho hàng.
    @Transactional
    protected TransferResponse transferToFinishedGoods(TransferRequest req, Warehouse fromWarehouse,
                                                       User user, long now) {
        com.nhatnam.server.entity.ProductionFactory factory = productionFactoryRepository
                .findById(req.getToFinishedGoodsFactoryId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy kho thành phẩm xưởng."));

        // Tập tên thành phẩm mà kho TP xưởng đang có
        java.util.Set<String> fgNames = new java.util.HashSet<>();
        for (FinishedGoodsStock fs : finishedGoodsStockRepository.searchActiveLotsByFactory(null, factory.getId()))
            if (fs.getProductName() != null) fgNames.add(fs.getProductName().trim().toLowerCase());

        String transferNote = String.format("Chuyển kho từ [%s] sang kho thành phẩm xưởng [%s]",
                fromWarehouse.getName(), factory.getName());
        String fullNote = req.getNote() != null && !req.getNote().isBlank()
                ? transferNote + " - " + req.getNote() : transferNote;

        WarehouseReceipt outReceipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("TRO")).receiptType(ReceiptType.TRANSFER_OUT)
                .costStatus(CostStatus.CONFIRMED).warehouse(fromWarehouse).partnerWarehouse(null)
                .note(fullNote).imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();

        for (TransferItemRequest tr : req.getItems()) {
            Ingredient ing = getIngredient(tr.getIngredientId());
            String key = ing.getName() == null ? "" : ing.getName().trim().toLowerCase();
            if (!fgNames.contains(key))
                throw new RuntimeException("Kho thành phẩm xưởng [" + factory.getName()
                        + "] không có thành phẩm: " + ing.getName());

            IngredientStock fromStock = getOrCreateStock(fromWarehouse, ing, now);
            checkSufficientStock(fromStock, tr.getQuantity(), ing.getName());
            BigDecimal fromBefore = fromStock.getStockQuantity();
            BigDecimal fromAfter  = fromBefore.subtract(tr.getQuantity());
            fromStock.setStockQuantity(fromAfter); fromStock.setUpdatedAt(now);
            ingredientStockRepository.save(fromStock);

            Long carriedExpiry = earliestExpiryMs(fromWarehouse, ing);
            BigDecimal totalCost = fifoDeductService.deduct(fromWarehouse, ing, tr.getQuantity(), now);
            BigDecimal unitCost = (totalCost != null && tr.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                    ? totalCost.divide(tr.getQuantity(), 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            finishedGoodsStockRepository.save(FinishedGoodsStock.builder()
                    .productName(ing.getName())
                    .unit(ing.getUnit())
                    .quantity(tr.getQuantity())
                    .initialQuantity(tr.getQuantity())
                    .unitCost(unitCost)
                    .totalCost(totalCost != null ? totalCost : BigDecimal.ZERO)
                    .expiryDate(carriedExpiry)
                    .factoryId(factory.getId())
                    .factoryNameSnapshot(factory.getName())
                    .isActive(true)
                    .build());

            outReceipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(outReceipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(tr.getQuantity().negate())
                    .quantityBefore(fromBefore).quantityAfter(fromAfter)
                    .difference(fromAfter.subtract(fromBefore)).build());
        }

        WarehouseReceipt savedOut = warehouseReceiptRepository.save(outReceipt);
        TransferResponse res = new TransferResponse();
        res.setOutReceipt(mapReceipt(savedOut));
        res.setInReceipt(null);
        return res;
    }

    /** Danh sách các kho THÀNH PHẨM xưởng (ACTIVE) làm đích khi chuyển kho — Mục 1. */
    public List<java.util.Map<String, Object>> listFinishedGoodsFactoriesForTransfer() {
        return productionFactoryRepository
                .findByStatusOrderByNameAsc(com.nhatnam.server.entity.ProductionFactory.FactoryStatus.ACTIVE)
                .stream().map(f -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", f.getId());
                    m.put("name", f.getName());
                    return m;
                }).collect(Collectors.toList());
    }

    /** Tên thành phẩm mà 1 kho TP xưởng đang có — để FE lọc dropdown chuyển kho. */
    public List<String> getFinishedGoodsProductNames(Long factoryId) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (FinishedGoodsStock fs : finishedGoodsStockRepository.searchActiveLotsByFactory(null, factoryId))
            if (fs.getProductName() != null) names.add(fs.getProductName());
        return new ArrayList<>(names);
    }

    /** Danh sách xưởng (ACTIVE) làm kho đích khi chuyển kho — Mục 4.2. */
    public List<java.util.Map<String, Object>> listProductionFactoriesForTransfer() {
        return productionFactoryRepository
                .findByStatusOrderByNameAsc(com.nhatnam.server.entity.ProductionFactory.FactoryStatus.ACTIVE)
                .stream()
                .map(f -> {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", f.getId());
                    m.put("name", f.getName());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /** Tên các nguyên liệu mà kho xưởng đang có (để FE lọc dropdown kho nguồn) — Mục 4.2. */
    public List<String> getFactoryMaterialNames(Long factoryId) {
        return factoryMaterialStockRepository
                .findByProductionFactory_IdAndIsActiveTrueOrderByCreatedAtAsc(factoryId)
                .stream()
                .map(FactoryMaterialStock::getMaterialName)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /** Danh sách ingredientId đã ĐĂNG KÝ (gán) cho 1 kho — dùng lọc chuyển kho. */
    public List<Long> getRegisteredIngredientIds(Long warehouseId) {
        return ingredientWarehouseRepository.findIngredientIdsByWarehouseId(warehouseId);
    }


    @Transactional
    public ReceiptResponse adjustStock(AdjustRequest req, Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse warehouse = getWarehouse(req.getWarehouseId());

        WarehouseReceipt receipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("ADJ")).receiptType(ReceiptType.ADJUST)
                .costStatus(CostStatus.CONFIRMED).warehouse(warehouse)
                .reason(req.getReason()).note(req.getNote())
                .imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();

        // Các lô MỚI được tạo trong phiếu này → gửi cho kế toán trưởng định giá
        List<LotPricingRequest> newLotRequests = new ArrayList<>();

        for (AdjustItemRequest ar : req.getItems()) {
            Ingredient ing = getIngredient(ar.getIngredientId());
            IngredientStock stock = getOrCreateStock(warehouse, ing, now);

            BigDecimal before = stock.getStockQuantity();
            BigDecimal after;

            boolean lotMode = ar.getLots() != null && !ar.getLots().isEmpty();
            if (lotMode) {
                after = applyLotAdjustments(warehouse, ing, ar.getLots(), receipt,
                        user, now, newLotRequests);
            } else {
                // ── Chế độ cũ: chỉnh theo tổng, phân bổ chênh lệch vào lô FIFO ──
                if (ar.getPhysicalQty() == null)
                    throw new com.nhatnam.server.common.BusinessException(
                            "Thiếu số lượng điều chỉnh cho: " + ing.getName());
                after = ar.getPhysicalQty();
                adjustExpiryLot(warehouse, ing, after.subtract(before), now);
            }

            BigDecimal diff = after.subtract(before);

            AdjustResult result;
            if      (diff.compareTo(BigDecimal.ZERO) > 0) result = AdjustResult.SURPLUS;
            else if (diff.compareTo(BigDecimal.ZERO) < 0) result = AdjustResult.SHORTAGE;
            else                                           result = AdjustResult.MATCH;

            stock.setStockQuantity(after);
            stock.setTotalCostValue(recalcTotalCostValue(warehouse.getId(), ing.getId()));
            stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);

            receipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(receipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(diff)
                    .quantityBefore(before).quantityAfter(after).difference(diff)
                    .physicalQty(after).adjustResult(result)
                    .build());
        }

        WarehouseReceipt saved = warehouseReceiptRepository.save(receipt);

        // Gắn mã phiếu + lưu yêu cầu định giá cho các lô mới, rồi báo kế toán trưởng
        if (!newLotRequests.isEmpty()) {
            newLotRequests.forEach(r -> r.setReceiptCode(saved.getReceiptCode()));
            lotPricingRequestRepository.saveAll(newLotRequests);
            notifyLotPricingPending(saved, warehouse, newLotRequests);
        }
        return mapReceipt(saved);
    }

    /**
     * Áp dụng điều chỉnh theo LÔ cho 1 nguyên liệu tại 1 kho.
     *
     * <p>Quy tắc:
     * <ul>
     *   <li>{@code lotId != null} → cập nhật số lượng + hạn sử dụng của lô đó.</li>
     *   <li>{@code lotId == null} → tạo lô mới, giá vốn mặc định = 1, đồng thời sinh
     *       {@link LotPricingRequest} để kế toán trưởng nhập giá vốn thật sau.</li>
     *   <li>Lô đang có nhưng KHÔNG được gửi lên → giữ nguyên, không đụng tới.</li>
     * </ul>
     *
     * @return tổng tồn mới của nguyên liệu = tổng số lượng tất cả các lô.
     */
    private BigDecimal applyLotAdjustments(Warehouse warehouse, Ingredient ing,
                                           List<AdjustLotRequest> lotReqs,
                                           WarehouseReceipt receipt, User user, long now,
                                           List<LotPricingRequest> newLotRequestsOut) {
        Map<Long, IngredientExpiry> existing = ingredientExpiryRepository
                .findByWarehouseIdAndIngredientId(warehouse.getId(), ing.getId())
                .stream().collect(Collectors.toMap(IngredientExpiry::getId, e -> e, (a, b) -> a));

        for (AdjustLotRequest lr : lotReqs) {
            BigDecimal qty = lr.getQuantity();
            if (qty == null) qty = BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) < 0)
                throw new com.nhatnam.server.common.BusinessException(
                        "Số lượng lô không được âm: " + ing.getName());

            if (lr.getLotId() != null) {
                IngredientExpiry lot = existing.get(lr.getLotId());
                if (lot == null)
                    throw new com.nhatnam.server.common.BusinessException(
                            "Lô #" + lr.getLotId() + " không thuộc nguyên liệu '"
                                    + ing.getName() + "' tại kho này");
                lot.setQuantity(qty);
                lot.setExpiryDate(lr.getExpiryDate());
                lot.setUpdatedAt(now);
                ingredientExpiryRepository.save(lot);
            } else {
                if (qty.compareTo(BigDecimal.ZERO) <= 0)
                    throw new com.nhatnam.server.common.BusinessException(
                            "Lô mới phải có số lượng lớn hơn 0: " + ing.getName());

                IngredientExpiry lot = ingredientExpiryRepository.save(IngredientExpiry.builder()
                        .warehouse(warehouse)
                        .ingredientId(ing.getId())
                        .expiryDate(lr.getExpiryDate())
                        .quantity(qty)
                        .costPrice(BigDecimal.ONE)   // giá vốn mặc định = 1, kế toán sửa sau
                        .createdAt(now).updatedAt(now)
                        .build());
                existing.put(lot.getId(), lot);

                newLotRequestsOut.add(LotPricingRequest.builder()
                        .ingredientExpiryId(lot.getId())
                        .warehouseId(warehouse.getId())
                        .warehouseName(warehouse.getName())
                        .ingredientId(ing.getId())
                        .ingredientName(ing.getName())
                        .ingredientUnit(ing.getUnit())
                        .quantity(qty)
                        .expiryDate(lr.getExpiryDate())
                        .requestedById(user.getId())
                        .requestedByName(resolveUserName(user))
                        .status(LotPricingRequest.Status.PENDING)
                        .createdAt(now)
                        .build());
            }
        }

        return existing.values().stream()
                .map(e -> e.getQuantity() != null ? e.getQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Giá trị tồn = Σ(số lượng lô × giá vốn lô). */
    private BigDecimal recalcTotalCostValue(Long warehouseId, Long ingredientId) {
        return ingredientExpiryRepository
                .findByWarehouseIdAndIngredientId(warehouseId, ingredientId)
                .stream()
                .map(e -> {
                    BigDecimal q = e.getQuantity() != null ? e.getQuantity() : BigDecimal.ZERO;
                    BigDecimal c = e.getCostPrice() != null ? e.getCostPrice() : BigDecimal.ZERO;
                    return q.multiply(c);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private void notifyLotPricingPending(WarehouseReceipt receipt, Warehouse warehouse,
                                         List<LotPricingRequest> lots) {
        try {
            String message = String.format(
                    "Phiếu điều chỉnh %s (%s) có %d lô mới cần nhập giá vốn",
                    receipt.getReceiptCode(), warehouse.getName(), lots.size());
            String payload = String.format(
                    "{\"receiptId\":%d,\"receiptCode\":\"%s\",\"warehouseName\":\"%s\",\"lotCount\":%d}",
                    receipt.getId(), receipt.getReceiptCode(), warehouse.getName(), lots.size());
            List<User> targets = userRepository.findByRoleAndIsLockAccountFalse(Role.SUPER_ACCOUNTANT);
            for (User u : targets)
                notificationService.sendToUser(u, "SUPER_ACCOUNTANT", "LOT_PENDING_COST", message, payload);
        } catch (Exception e) {
            log.warn("[ADJUST] Failed to send lot pricing notification: {}", e.getMessage());
        }
    }

    // ── LỊCH SỬ ──────────────────────────────────────────────────────────────

    public Page<ReceiptResponse> getHistory(List<ReceiptType> types, Long warehouseId,
                                            int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<WarehouseReceipt> receipts = warehouseId != null
                ? warehouseReceiptRepository.findByWarehouseIdAndTypes(warehouseId, types, pageable)
                : warehouseReceiptRepository.findByTypes(types, pageable);
        return receipts.map(this::mapReceipt);
    }

    public ReceiptResponse getReceiptDetail(Long receiptId) {
        return mapReceipt(warehouseReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new RuntimeException("Receipt not found: " + receiptId)));
    }

    // ── PHIẾU ĐI ĐƯỜNG (Giấy thông tin nguồn gốc động vật) ────────────────────

    /**
     * Gom dữ liệu in phiếu đi đường cho một PHIẾU CHUYỂN KHO RA (TRANSFER_OUT).
     *
     * <p>Mỗi dòng hàng lấy HSD XA NHẤT trong các lô đã chuyển — giá trị này được
     * ghi vào {@code WarehouseReceiptItem.expiryDate} ngay lúc chuyển kho. Với các
     * phiếu cũ (tạo trước khi có tính năng này) thì suy ra từ các lô hiện có ở kho
     * đích để phiếu vẫn in được.
     */
    @Transactional(readOnly = true)
    public com.nhatnam.server.utils.TransportSlipPdf.SlipData buildTransportSlip(Long receiptId) {
        WarehouseReceipt r = warehouseReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new com.nhatnam.server.common.BusinessException(
                        "Không tìm thấy phiếu #" + receiptId));

        if (r.getReceiptType() != ReceiptType.TRANSFER_OUT)
            throw new com.nhatnam.server.common.BusinessException(
                    "Chỉ phiếu chuyển kho ra mới in được phiếu đi đường");

        Warehouse dest = r.getPartnerWarehouse();
        String receiverName = dest != null ? dest.getName() : destinationFromNote(r.getNote());
        String destAddress  = dest != null && dest.getAddress() != null ? dest.getAddress() : "";

        List<com.nhatnam.server.utils.TransportSlipPdf.SlipItem> items = new ArrayList<>();
        for (WarehouseReceiptItem it : r.getItems()) {
            // HSD lấy ĐÚNG từ dòng phiếu, KHÔNG suy đoán.
            //
            //   Bản cũ: dòng phiếu không có HSD thì đi tra HSD xa nhất của nguyên
            //   liệu đó đang có ở kho đích. Hỏng với hàng vốn KHÔNG có hạn (ruột
            //   heo tính theo bó, bao bì, vật tư theo mét): nó vớ lấy ngày của một
            //   lô khác chẳng liên quan và in lên giấy tờ thú y một hạn sử dụng
            //   không có thật.
            //
            //   Đánh đổi: phiếu chuyển kho cũ chưa lưu expiryDate ở dòng phiếu sẽ
            //   hiện trống thay vì đoán ra một ngày. Trống thì người đọc biết là
            //   thiếu thông tin; một ngày sai thì không ai biết là sai.
            LocalDate expiry = it.getExpiryDate();
            // Phiếu chuyển RA lưu số lượng dạng ÂM — phiếu in cần giá trị dương
            BigDecimal qty = it.getQuantity() == null ? BigDecimal.ZERO : it.getQuantity().abs();

            items.add(new com.nhatnam.server.utils.TransportSlipPdf.SlipItem(
                    it.getIngredientNameSnapshot(), qty, it.getIngredientUnitSnapshot(), expiry));
        }

        return new com.nhatnam.server.utils.TransportSlipPdf.SlipData(
                r.getReceiptCode(),
                r.getCreatedAt(),
                receiverName,
                destAddress,
                "Xe tải",
                r.getCreatedByName(),
                items);
    }

    /** Phiếu chuyển sang kho xưởng không có partnerWarehouse — lấy tên đích từ ghi chú. */
    private String destinationFromNote(String note) {
        if (note == null) return "";
        int i = note.lastIndexOf("sang [");
        if (i < 0) return "";
        int j = note.indexOf(']', i);
        return j > 0 ? note.substring(i + 6, j) : "";
    }

    // ── NOTIFICATION ─────────────────────────────────────────────────────────

    public void notifyCostConfirmed(WarehouseReceipt receipt, String confirmedByName) {
        try {
            String message = String.format(
                    "Phiếu nhập kho %s (%s) đã được xác nhận giá vốn bởi %s. Tồn kho đã cập nhật.",
                    receipt.getReceiptCode(), receipt.getWarehouse().getName(), confirmedByName);
            String payload = String.format(
                    "{\"receiptId\":%d,\"receiptCode\":\"%s\",\"warehouseName\":\"%s\",\"confirmedBy\":\"%s\"}",
                    receipt.getId(), receipt.getReceiptCode(),
                    receipt.getWarehouse().getName(), confirmedByName);

            List<User> admins = userRepository.findByRoleAndIsLockAccountFalse(Role.ADMIN);
            List<User> owners = userRepository.findByRoleAndIsLockAccountFalse(Role.OWNER);
            for (User u : admins) notificationService.sendToUser(u, "COST_CONFIRMED", message, payload);
            for (User u : owners) notificationService.sendToUser(u, "COST_CONFIRMED", message, payload);
            notificationService.sendToRole("ADMIN", "COST_CONFIRMED", message, payload);
            notificationService.sendToRole("OWNER", "COST_CONFIRMED", message, payload);
        } catch (Exception e) {
            log.warn("[COST_CONFIRMED] Failed to send notification: {}", e.getMessage());
        }
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    private Warehouse getWarehouse(Long id) {
        return warehouseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Warehouse not found: " + id));
    }

    private Ingredient getIngredient(Long id) {
        return ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found: " + id));
    }

    private User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
    }

    /**
     * Tạo hoặc lấy IngredientStock.
     * THAY ĐỔI: không set .ingredient(ingredient) nữa — set .ingredientId() + snapshot.
     */
    private IngredientStock getOrCreateStock(Warehouse warehouse, Ingredient ingredient, long now) {
        return ingredientStockRepository
                .findByIngredientIdAndWarehouseId(ingredient.getId(), warehouse.getId())
                .orElseGet(() -> ingredientStockRepository.save(
                        IngredientStock.builder()
                                .ingredientId(ingredient.getId())                // ← plain id
                                .ingredientNameSnapshot(ingredient.getName())    // ← snapshot
                                .ingredientUnitSnapshot(ingredient.getUnit())    // ← snapshot
                                .warehouse(warehouse)
                                .stockQuantity(BigDecimal.ZERO)
                                .updatedAt(now)
                                .build()));
    }

    private void checkSufficientStock(IngredientStock stock, BigDecimal needed, String ingName) {
        if (stock.getStockQuantity().compareTo(needed) < 0)
            throw new RuntimeException(String.format(
                    "Không đủ tồn kho '%s' tại kho '%s' (còn: %s, cần: %s)",
                    ingName, stock.getWarehouse().getName(),
                    stock.getStockQuantity(), needed));
    }

    private void addExpiry(Warehouse warehouse, Ingredient ingredient,
                           BigDecimal quantity, LocalDate expiryDate,
                           BigDecimal costPrice, long now) {
        ingredientExpiryRepository
                .findByWarehouseIdAndIngredientId(warehouse.getId(), ingredient.getId())
                .stream()
                .filter(e -> java.util.Objects.equals(e.getExpiryDate(), expiryDate)
                        && java.util.Objects.equals(e.getCostPrice(), costPrice))
                .findFirst()
                .ifPresentOrElse(e -> {
                    e.setQuantity(e.getQuantity().add(quantity));
                    e.setUpdatedAt(now);
                    ingredientExpiryRepository.save(e);
                }, () -> ingredientExpiryRepository.save(
                        IngredientExpiry.builder()
                                .warehouse(warehouse)
                                .ingredientId(ingredient.getId()) // ← plain id
                                .expiryDate(expiryDate).quantity(quantity)
                                .costPrice(costPrice)
                                .createdAt(now).updatedAt(now)
                                .build()));
    }

    private String generateCode(String prefix) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String rand = String.format("%04d", new Random().nextInt(10000));
        String code = prefix + "-" + date + "-" + rand;
        while (warehouseReceiptRepository.existsByReceiptCode(code)) {
            rand = String.format("%04d", new Random().nextInt(10000));
            code = prefix + "-" + date + "-" + rand;
        }
        return code;
    }

    private String resolveUserName(User user) {
        return (user.getFullName() != null && !user.getFullName().isBlank())
                ? user.getFullName() : user.getUsername();
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(list); }
        catch (JsonProcessingException e) { return null; }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private WarehouseResponse mapWarehouse(Warehouse w) {
        WarehouseResponse r = new WarehouseResponse();
        r.setId(w.getId()); r.setName(w.getName()); r.setAddress(w.getAddress());
        r.setType(w.getType()); r.setActive(w.isActive());
        return r;
    }

    public ReceiptResponse mapReceipt(WarehouseReceipt receipt) {
        ReceiptResponse r = new ReceiptResponse();
        r.setId(receipt.getId());
        r.setReceiptCode(receipt.getReceiptCode());
        r.setReceiptType(receipt.getReceiptType());
        r.setWarehouseName(receipt.getWarehouse().getName());
        r.setWarehouseId(receipt.getWarehouse().getId());
        if (receipt.getPartnerWarehouse() != null) {
            r.setPartnerWarehouseName(receipt.getPartnerWarehouse().getName());
            r.setPartnerWarehouseId(receipt.getPartnerWarehouse().getId());
        }
        r.setReferenceCode(receipt.getReferenceCode());
        r.setReason(receipt.getReason());
        r.setNote(receipt.getNote());
        r.setImageUrls(fromJson(receipt.getImageUrls()));
        r.setCreatedByName(receipt.getCreatedByName());
        r.setCreatedAt(receipt.getCreatedAt());

        // THAY ĐỔI: đọc từ snapshot thay vì item.getIngredient().getXxx()
        List<ReceiptItemResponse> items = receipt.getItems().stream().map(item -> {
            ReceiptItemResponse ir = new ReceiptItemResponse();
            ir.setIngredientId(item.getIngredientId());                  // ← plain id
            ir.setIngredientName(item.resolvedIngredientName());         // ← snapshot
            ir.setUnit(item.resolvedIngredientUnit());                   // ← snapshot
            ir.setImageUrl(item.resolvedIngredientImageUrl());           // ← snapshot
            ir.setQuantity(item.getQuantity());
            ir.setQuantityBefore(item.getQuantityBefore());
            ir.setQuantityAfter(item.getQuantityAfter());
            ir.setDifference(item.getDifference());
            ir.setPhysicalQty(item.getPhysicalQty());
            ir.setAdjustResult(item.getAdjustResult() != null ? item.getAdjustResult().name() : null);
            ir.setExpiryDate(item.getExpiryDate());
            return ir;
        }).collect(Collectors.toList());

        r.setItems(items);
        return r;
    }
}