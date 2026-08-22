package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.*;
import com.nhatnam.server.entity.MaterialRequest;
import com.nhatnam.server.entity.MaterialRequestItem;
import com.nhatnam.server.entity.MaterialRequestVendor;
import com.nhatnam.server.entity.MaterialVendor;
import com.nhatnam.server.entity.VendorExpenseCategory;
import com.nhatnam.server.repository.MaterialRequestItemRepository;
import com.nhatnam.server.repository.MaterialRequestVendorRepository;
import com.nhatnam.server.repository.MaterialVendorRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Trang "Quản lý nhà cung cấp" (Owner/Admin — chỉ xem).
 *
 * Nguồn dữ liệu: {@link MaterialRequestItem} (đã có unitPrice/lineAmount sau khi
 * kế toán Hoàn thành phiếu) gộp theo {@link MaterialRequestVendor} và
 * {@link MaterialVendor}. Công nợ đọc lại từ MaterialRequestVendor.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierManagementService {

    private static final long DAY_MS = 86_400_000L;

    private final MaterialVendorRepository vendorRepo;
    private final MaterialRequestVendorRepository requestVendorRepo;
    private final MaterialRequestItemRepository itemRepo;
    private final com.nhatnam.server.repository.VendorExpenseCategoryRepository categoryRepo;
    /**
     * Dùng cho danh mục loại CONSUMABLE: tra/ tạo {@link com.nhatnam.server.entity.SupplyItem}
     * theo bộ ba (tên, quy cách, ĐVT) đã chuẩn hoá — nền tảng của quy tắc gộp tồn kho.
     */
    private final SupplyItemService supplyItemService;
    private final EntityManager em;

    // ══════════════════════════════════════════════════════════════════════
    // 0) Cập nhật thông tin NCC
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    public SupplierInfoDto updateVendor(Long vendorId, UpdateVendorRequest req) {
        MaterialVendor v = vendorRepo.findById(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhà cung cấp"));

        String oldName = v.getName();
        boolean nameChanged = false;

        // Cập nhật tên — check trùng
        if (req.getName() != null && !req.getName().isBlank()) {
            String newName = req.getName().trim();
            if (!newName.equalsIgnoreCase(oldName)) {
                vendorRepo.findByNameIgnoreCase(newName).ifPresent(existing -> {
                    if (!existing.getId().equals(vendorId)) {
                        throw new BusinessException("Nhà cung cấp \"" + newName + "\" đã tồn tại");
                    }
                });
                v.setName(newName);
                nameChanged = true;
            }
        }

        // Cập nhật loại
        if (req.getVendorType() != null && !req.getVendorType().isBlank()) {
            try {
                v.setVendorType(MaterialVendor.VendorType.valueOf(req.getVendorType().trim()));
            } catch (IllegalArgumentException ignored) { /* giữ nguyên nếu không hợp lệ */ }
        }

        // Cập nhật các trường khác
        if (req.getContactPerson() != null) v.setContactPerson(req.getContactPerson().trim());
        if (req.getContactPhone() != null) v.setContactPhone(req.getContactPhone().trim());
        if (req.getAddress() != null) v.setAddress(req.getAddress().trim());
        if (req.getTaxCode() != null) v.setTaxCode(req.getTaxCode().trim());

        vendorRepo.save(v);

        // Cascade cập nhật tên NCC ở các bảng snapshot
        if (nameChanged) {
            String newName = v.getName();
            // material_request_vendor (có FK vendor.id)
            em.createQuery("UPDATE MaterialRequestVendor rv SET rv.vendorName = :n WHERE rv.vendor.id = :id")
                    .setParameter("n", newName).setParameter("id", vendorId).executeUpdate();
            // expense_voucher (có vendorId column)
            em.createQuery("UPDATE ExpenseVoucher ev SET ev.vendorName = :n WHERE ev.vendorId = :id")
                    .setParameter("n", newName).setParameter("id", vendorId).executeUpdate();
            // vendor_expense_voucher (có FK vendor.id)
            em.createQuery("UPDATE VendorExpenseVoucher vev SET vev.vendorName = :n WHERE vev.vendor.id = :id")
                    .setParameter("n", newName).setParameter("id", vendorId).executeUpdate();
            // maintenance_schedule (chỉ có vendorName text — match by oldName)
            em.createQuery("UPDATE MaintenanceSchedule ms SET ms.vendorName = :n WHERE ms.vendorName = :old")
                    .setParameter("n", newName).setParameter("old", oldName).executeUpdate();
            // work_order_plan_material (chỉ có vendorName text — match by oldName)
            em.createQuery("UPDATE WorkOrderPlanMaterial wm SET wm.vendorName = :n WHERE wm.vendorName = :old")
                    .setParameter("n", newName).setParameter("old", oldName).executeUpdate();
        }

        return getSupplierInfo(vendorId);
    }

    /**
     * XÓA MỀM nhà cung cấp (Mục 2).
     *
     * <p>Không xóa cứng để giữ lịch sử phiếu đặt hàng / công nợ. Thay vào đó:
     *   - Đặt {@code active = false} → tự ẩn khỏi danh sách (list chỉ lấy activeTrue).
     *   - Đổi TÊN thêm tiền tố "[Đã xóa <timestamp>] " để GIẢI PHÓNG tên gốc,
     *     cho phép tạo lại nhà cung cấp trùng tên cũ sau này.
     *
     * <p>Các bảng snapshot (material_request_vendor, expense_voucher...) KHÔNG bị đổi
     * theo, vì chúng lưu tên NCC tại thời điểm giao dịch — giữ nguyên để không sai
     * lịch sử. Chỉ bản ghi NCC gốc được đổi tên.
     */
    @Transactional
    public void deleteVendor(Long vendorId) {
        MaterialVendor v = vendorRepo.findById(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhà cung cấp"));
        if (!v.isActive()) return;   // đã xóa rồi → bỏ qua (idempotent)

        String prefix = "[Đã xóa " + System.currentTimeMillis() + "] ";
        v.setName(prefix + v.getName());
        v.setActive(false);
        vendorRepo.save(v);
    }

    public List<SupplierListItemDto> listSuppliers(String search, String sortBy) {
        List<MaterialVendor> vendors = (search == null || search.isBlank())
                ? vendorRepo.findByActiveTrueOrderByNameAsc()
                : vendorRepo.findByNameContainingIgnoreCaseAndActiveTrue(search.trim());

        long now = System.currentTimeMillis();
        List<SupplierListItemDto> result = new ArrayList<>();

        for (MaterialVendor v : vendors) {
            List<MaterialRequestVendor> lots = requestVendorRepo.findByVendor_Id(v.getId());

            List<MaterialRequestVendor> unsettled = lots.stream()
                    .filter(this::isOutstandingDebt)
                    .collect(Collectors.toList());

            BigDecimal totalDebt = unsettled.stream()
                    .map(this::remainingOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Long oldestSince = unsettled.stream()
                    .map(MaterialRequestVendor::getDebtSince)
                    .filter(Objects::nonNull)
                    .min(Long::compareTo)
                    .orElse(null);

            result.add(SupplierListItemDto.builder()
                    .vendorId(v.getId())
                    .vendorName(v.getName())
                    .vendorType(v.getVendorType() != null ? v.getVendorType().name() : null)
                    .contactPerson(v.getContactPerson())
                    .contactPhone(v.getContactPhone())
                    .totalDebt(totalDebt)
                    .oldestDebtDays(oldestSince != null ? (int) ((now - oldestSince) / DAY_MS) : null)
                    .unsettledLotCount(unsettled.size())
                    .orderCount(lots.size())
                    .build());
        }

        // Sắp xếp: 'debt' (nợ lâu nhất trước) | 'amount' (nợ nhiều nhất) | 'name'
        Comparator<SupplierListItemDto> cmp;
        if ("amount".equalsIgnoreCase(sortBy)) {
            cmp = Comparator.comparing(SupplierListItemDto::getTotalDebt,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed();
        } else if ("name".equalsIgnoreCase(sortBy)) {
            cmp = Comparator.comparing(SupplierListItemDto::getVendorName,
                    String.CASE_INSENSITIVE_ORDER);
        } else { // debt / oldest — NCC có nợ lâu nhất lên đầu, không nợ xuống cuối
            cmp = Comparator.comparing(
                            (SupplierListItemDto s) -> s.getOldestDebtDays() == null ? -1 : s.getOldestDebtDays())
                    .reversed();
        }
        result.sort(cmp);
        return result;
    }

    /** Các lô công nợ còn lại của 1 NCC — hiển thị khi click badge */
    public List<DebtLotDto> getDebtLots(Long vendorId) {
        requireVendor(vendorId);
        long now = System.currentTimeMillis();
        return requestVendorRepo.findByVendor_Id(vendorId).stream()
                .filter(this::isOutstandingDebt)
                .sorted(Comparator.comparing(MaterialRequestVendor::getDebtSince,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(rv -> {
                    BigDecimal total = nz(rv.getTotalAmount());
                    BigDecimal paid = nz(rv.getPaidAmount());
                    return DebtLotDto.builder()
                            .requestVendorId(rv.getId())
                            .requestCode(rv.getMaterialRequest().getRequestCode())
                            .totalAmount(total)
                            .paidAmount(paid)
                            .remaining(remainingOf(rv))
                            .settlementStatus(rv.getDebtSettlementStatus() != null
                                    ? rv.getDebtSettlementStatus().name() : "NONE")
                            .debtSince(rv.getDebtSince())
                            .debtDays(rv.getDebtSince() != null ? (int) ((now - rv.getDebtSince()) / DAY_MS) : 0)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2) Chi tiết NCC: thông tin + lịch sử đặt hàng
    // ══════════════════════════════════════════════════════════════════════

    public SupplierInfoDto getSupplierInfo(Long vendorId) {
        MaterialVendor v = requireVendor(vendorId);
        List<MaterialRequestVendor> lots = requestVendorRepo.findByVendor_Id(vendorId);
        List<MaterialRequestItem> items = itemRepo.findAllSuppliedByVendor(vendorId);

        long now = System.currentTimeMillis();
        Long oldestSince = lots.stream().filter(this::isOutstandingDebt)
                .map(MaterialRequestVendor::getDebtSince)
                .filter(Objects::nonNull).min(Long::compareTo).orElse(null);

        BigDecimal totalDebt = lots.stream().filter(this::isOutstandingDebt)
                .map(this::remainingOf).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPurchased = items.stream()
                .map(i -> nz(i.getLineAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);

        long distinctProducts = items.stream()
                .map(i -> normalize(i.getMaterialName()))
                .filter(s -> !s.isBlank())
                .distinct().count();

        return SupplierInfoDto.builder()
                .vendorId(v.getId())
                .vendorName(v.getName())
                .vendorType(v.getVendorType() != null ? v.getVendorType().name() : null)
                .contactPerson(v.getContactPerson())
                .contactPhone(v.getContactPhone())
                .address(v.getAddress())
                .taxCode(v.getTaxCode())
                .totalDebt(totalDebt)
                .oldestDebtDays(oldestSince != null ? (int) ((now - oldestSince) / DAY_MS) : null)
                .orderCount(lots.size())
                .distinctProductCount((int) distinctProducts)
                .totalPurchased(totalPurchased)
                .build();
    }

    /** Lịch sử đặt hàng của 1 NCC — gộp item theo từng lô (MaterialRequestVendor). */
    public List<OrderHistoryDto> getOrderHistory(Long vendorId, String productSearch) {
        requireVendor(vendorId);
        List<MaterialRequestItem> items = itemRepo.findAllSuppliedByVendor(vendorId);

        String q = productSearch == null ? "" : productSearch.trim().toLowerCase();

        // Gộp theo lô (MaterialRequestVendor)
        Map<Long, List<MaterialRequestItem>> byLot = new LinkedHashMap<>();
        for (MaterialRequestItem it : items) {
            byLot.computeIfAbsent(it.getSuppliedByVendor().getId(), k -> new ArrayList<>()).add(it);
        }

        List<OrderHistoryDto> orders = new ArrayList<>();
        for (Map.Entry<Long, List<MaterialRequestItem>> e : byLot.entrySet()) {
            List<MaterialRequestItem> lotItems = e.getValue();
            MaterialRequestVendor rv = lotItems.get(0).getSuppliedByVendor();
            MaterialRequest mr = rv.getMaterialRequest();

            // Lọc theo tên sản phẩm nếu có — chỉ giữ lô có ít nhất 1 item khớp
            List<MaterialRequestItem> shown = q.isBlank() ? lotItems
                    : lotItems.stream()
                    .filter(i -> normalize(i.getMaterialName()).contains(q))
                    .collect(Collectors.toList());
            if (shown.isEmpty()) continue;

            BigDecimal total = lotItems.stream().map(i -> nz(i.getLineAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (rv.getTotalAmount() != null) total = rv.getTotalAmount();

            List<OrderItemLineDto> lines = shown.stream().map(i -> OrderItemLineDto.builder()
                            .itemId(i.getId())
                            .materialName(i.getMaterialName())
                            .unit(i.getUnit())
                            .quantity(i.getQtyReceived() != null ? i.getQtyReceived() : i.getQtyRequested())
                            .unitPrice(i.getUnitPrice())
                            .lineAmount(i.getLineAmount())
                            .build())
                    .collect(Collectors.toList());

            orders.add(OrderHistoryDto.builder()
                    .requestVendorId(rv.getId())
                    .materialRequestId(mr.getId())
                    .requestCode(mr.getRequestCode())
                    .orderedAt(mr.getOrderedAt())
                    .completedAt(mr.getCompletedAt())
                    .totalAmount(total)
                    .paymentStatus(rv.getPaymentStatus() != null ? rv.getPaymentStatus().name() : "UNSET")
                    .settlementStatus(rv.getDebtSettlementStatus() != null ? rv.getDebtSettlementStatus().name() : "NONE")
                    .items(lines)
                    .build());
        }

        // Mới nhất lên đầu
        orders.sort(Comparator.comparing(
                        (OrderHistoryDto o) -> o.getCompletedAt() != null ? o.getCompletedAt()
                                : (o.getOrderedAt() != null ? o.getOrderedAt() : 0L))
                .reversed());
        return orders;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3) Phân tích giá 1 sản phẩm của NCC (modal)
    // ══════════════════════════════════════════════════════════════════════

    public ProductPriceStatsDto getProductPriceStats(Long vendorId, String productName) {
        requireVendor(vendorId);
        String target = normalize(productName);

        // Các item cùng tên sản phẩm, đã có đơn giá
        List<MaterialRequestItem> matched = itemRepo.findAllSuppliedByVendor(vendorId).stream()
                .filter(i -> normalize(i.getMaterialName()).equals(target))
                .filter(i -> i.getUnitPrice() != null)
                .collect(Collectors.toList());

        if (matched.isEmpty()) {
            return ProductPriceStatsDto.builder()
                    .productName(productName).purchaseCount(0)
                    .points(List.of()).build();
        }

        // Điểm giá theo thời gian (mốc = completedAt của phiếu)
        List<PricePointDto> points = matched.stream()
                .map(i -> {
                    MaterialRequest mr = i.getSuppliedByVendor().getMaterialRequest();
                    Long at = mr.getCompletedAt() != null ? mr.getCompletedAt() : mr.getCreatedAt();
                    return PricePointDto.builder()
                            .unitPrice(i.getUnitPrice())
                            .quantity(i.getQtyReceived() != null ? i.getQtyReceived() : i.getQtyRequested())
                            .unit(i.getUnit())
                            .at(at)
                            .requestCode(mr.getRequestCode())
                            .build();
                })
                .sorted(Comparator.comparing(p -> p.getAt() == null ? 0L : p.getAt()))
                .collect(Collectors.toList());

        int n = points.size();

        // min / max (với thời điểm)
        PricePointDto minP = points.stream().min(Comparator.comparing(PricePointDto::getUnitPrice)).get();
        PricePointDto maxP = points.stream().max(Comparator.comparing(PricePointDto::getUnitPrice)).get();
        PricePointDto firstP = points.get(0);
        PricePointDto lastP = points.get(n - 1);

        // trung bình cộng đơn thuần
        BigDecimal sumPrice = points.stream().map(PricePointDto::getUnitPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal simpleAvg = sumPrice.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        // bình quân gia quyền theo số lượng = tổng chi / tổng lượng
        BigDecimal totalQty = points.stream().map(p -> nz(p.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSpent = points.stream()
                .map(p -> p.getUnitPrice().multiply(nz(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weightedAvg = totalQty.compareTo(BigDecimal.ZERO) > 0
                ? totalSpent.divide(totalQty, 2, RoundingMode.HALF_UP)
                : simpleAvg;

        // trung vị
        List<BigDecimal> sortedPrices = points.stream().map(PricePointDto::getUnitPrice)
                .sorted().collect(Collectors.toList());
        BigDecimal median;
        if (n % 2 == 1) {
            median = sortedPrices.get(n / 2);
        } else {
            median = sortedPrices.get(n / 2 - 1).add(sortedPrices.get(n / 2))
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }

        // xu hướng: giá gần nhất so với lần đầu
        BigDecimal trendPct = null;
        if (firstP.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            trendPct = lastP.getUnitPrice().subtract(firstP.getUnitPrice())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(firstP.getUnitPrice(), 1, RoundingMode.HALF_UP);
        }

        // biên độ dao động
        BigDecimal volatilityPct = null;
        if (minP.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            volatilityPct = maxP.getUnitPrice().subtract(minP.getUnitPrice())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(minP.getUnitPrice(), 1, RoundingMode.HALF_UP);
        }

        return ProductPriceStatsDto.builder()
                .productName(matched.get(0).getMaterialName())
                .unit(matched.get(0).getUnit())
                .purchaseCount(n)
                .avgPrice(weightedAvg)
                .simpleAvgPrice(simpleAvg)
                .medianPrice(median)
                .minPrice(minP.getUnitPrice()).minAt(minP.getAt())
                .maxPrice(maxP.getUnitPrice()).maxAt(maxP.getAt())
                .latestPrice(lastP.getUnitPrice()).latestAt(lastP.getAt())
                .firstPrice(firstP.getUnitPrice()).firstAt(firstP.getAt())
                .totalQuantity(totalQty)
                .totalSpent(totalSpent)
                .priceTrendPct(trendPct)
                .volatilityPct(volatilityPct)
                .points(points)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3b) Phân tích giá 1 nguyên liệu — GỘP ĐA-NHÀ-CUNG-CẤP (theo tên NL)
    // ══════════════════════════════════════════════════════════════════════

    public MaterialPriceAnalysisDto getMaterialPriceAnalysis(String materialName) {
        String target = normalize(materialName);
        if (target.isEmpty()) {
            return MaterialPriceAnalysisDto.builder()
                    .materialName(materialName).purchaseCount(0).vendorCount(0)
                    .points(List.of()).build();
        }

        List<MaterialRequestItem> matched = itemRepo.findAllPricedByMaterialName(target);
        if (matched.isEmpty()) {
            return MaterialPriceAnalysisDto.builder()
                    .materialName(materialName).purchaseCount(0).vendorCount(0)
                    .points(List.of()).build();
        }

        // Điểm giá theo thời gian (mốc = completedAt của phiếu), kèm tên NCC
        List<MaterialPricePointDto> points = matched.stream()
                .map(i -> {
                    MaterialRequestVendor rv = i.getSuppliedByVendor();
                    MaterialRequest mr = rv.getMaterialRequest();
                    Long at = mr.getCompletedAt() != null ? mr.getCompletedAt() : mr.getCreatedAt();
                    String vName = rv.getVendor() != null ? rv.getVendor().getName() : rv.getVendorName();
                    Long vId = rv.getVendor() != null ? rv.getVendor().getId() : null;
                    return MaterialPricePointDto.builder()
                            .unitPrice(i.getUnitPrice())
                            .quantity(i.getQtyReceived() != null ? i.getQtyReceived() : i.getQtyRequested())
                            .unit(i.getUnit())
                            .at(at)
                            .requestCode(mr.getRequestCode())
                            .vendorName(vName)
                            .vendorId(vId)
                            .build();
                })
                .sorted(Comparator.comparing(p -> p.getAt() == null ? 0L : p.getAt()))
                .collect(Collectors.toList());

        int n = points.size();

        // Số NCC khác nhau (theo tên, phòng khi vendorId null)
        long vendorCount = points.stream()
                .map(p -> p.getVendorId() != null ? ("#" + p.getVendorId())
                        : normalize(p.getVendorName()))
                .distinct().count();

        MaterialPricePointDto minP = points.stream()
                .min(Comparator.comparing(MaterialPricePointDto::getUnitPrice)).get();
        MaterialPricePointDto maxP = points.stream()
                .max(Comparator.comparing(MaterialPricePointDto::getUnitPrice)).get();
        MaterialPricePointDto firstP = points.get(0);
        MaterialPricePointDto lastP = points.get(n - 1);

        // trung bình cộng đơn thuần
        BigDecimal sumPrice = points.stream().map(MaterialPricePointDto::getUnitPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal simpleAvg = sumPrice.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        // bình quân gia quyền theo số lượng = tổng chi / tổng lượng
        BigDecimal totalQty = points.stream().map(p -> nz(p.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSpent = points.stream()
                .map(p -> p.getUnitPrice().multiply(nz(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weightedAvg = totalQty.compareTo(BigDecimal.ZERO) > 0
                ? totalSpent.divide(totalQty, 2, RoundingMode.HALF_UP)
                : simpleAvg;

        // trung vị
        List<BigDecimal> sortedPrices = points.stream().map(MaterialPricePointDto::getUnitPrice)
                .sorted().collect(Collectors.toList());
        BigDecimal median = (n % 2 == 1)
                ? sortedPrices.get(n / 2)
                : sortedPrices.get(n / 2 - 1).add(sortedPrices.get(n / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        // xu hướng: giá gần nhất so với lần mua LIỀN TRƯỚC (không phải lần đầu)
        MaterialPricePointDto prevP = n >= 2 ? points.get(n - 2) : null;
        BigDecimal trendPct = null;
        if (prevP != null && prevP.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            trendPct = lastP.getUnitPrice().subtract(prevP.getUnitPrice())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(prevP.getUnitPrice(), 1, RoundingMode.HALF_UP);
        }

        // biên độ dao động
        BigDecimal volatilityPct = null;
        if (minP.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            volatilityPct = maxP.getUnitPrice().subtract(minP.getUnitPrice())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(minP.getUnitPrice(), 1, RoundingMode.HALF_UP);
        }

        return MaterialPriceAnalysisDto.builder()
                .materialName(matched.get(0).getMaterialName())
                .unit(matched.get(0).getUnit())
                .purchaseCount(n)
                .vendorCount((int) vendorCount)
                .avgPrice(weightedAvg)
                .simpleAvgPrice(simpleAvg)
                .medianPrice(median)
                .minPrice(minP.getUnitPrice()).minAt(minP.getAt()).minVendorName(minP.getVendorName())
                .maxPrice(maxP.getUnitPrice()).maxAt(maxP.getAt()).maxVendorName(maxP.getVendorName())
                .latestPrice(lastP.getUnitPrice()).latestAt(lastP.getAt()).latestVendorName(lastP.getVendorName())
                .firstPrice(firstP.getUnitPrice()).firstAt(firstP.getAt()).firstVendorName(firstP.getVendorName())
                .prevPrice(prevP != null ? prevP.getUnitPrice() : null)
                .prevAt(prevP != null ? prevP.getAt() : null)
                .prevVendorName(prevP != null ? prevP.getVendorName() : null)
                .totalQuantity(totalQty)
                .totalSpent(totalSpent)
                .priceTrendPct(trendPct)
                .volatilityPct(volatilityPct)
                .points(points)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4) DANH MỤC KHOẢN CHI — POOL DÙNG CHUNG cho MỌI NCC
    //
    //    Owner tạo nhãn MỘT LẦN, tất cả nhà cung cấp đều chọn được.
    //    (Trước đây mỗi NCC có danh mục riêng → 10 nhãn × 200 NCC = 2.000 thao tác tạo,
    //     và cùng một khoản chi nằm rải rác ở 200 bản ghi nên rất khó tổng hợp.)
    //
    //    Các overload nhận `vendorId` chỉ còn để TƯƠNG THÍCH NGƯỢC với endpoint cũ —
    //    tham số vendorId bị BỎ QUA hoàn toàn.
    // ══════════════════════════════════════════════════════════════════════

    public List<VendorExpenseCategoryDto> listCategories(boolean activeOnly) {
        List<VendorExpenseCategory> list = activeOnly
                ? categoryRepo.findByActiveTrueOrderByNameAsc()
                : categoryRepo.findAllByOrderByNameAsc();
        return list.stream().map(this::toCategoryDto).collect(Collectors.toList());
    }

    @Transactional
    public VendorExpenseCategoryDto createCategory(CategoryUpsertRequest req, String createdByName) {
        String name = req.getName() == null ? "" : req.getName().trim();
        if (name.isEmpty()) throw new BusinessException("Tên nhãn khoản chi là bắt buộc");
        if (categoryRepo.existsByNameIgnoreCase(name)) {
            throw new BusinessException("Nhãn \"" + name + "\" đã tồn tại trong danh mục chung");
        }
        VendorExpenseCategory c = VendorExpenseCategory.builder()
                .name(name)
                .description(req.getDescription() != null ? req.getDescription().trim() : null)
                .active(req.getActive() == null || req.getActive())
                .createdByName(createdByName)
                .build();

        applyKind(c, req);
        return toCategoryDto(categoryRepo.save(c));
    }

    @Transactional
    public VendorExpenseCategoryDto updateCategory(Long categoryId, CategoryUpsertRequest req) {
        VendorExpenseCategory c = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Nhãn khoản chi không tồn tại"));
        if (req.getName() != null) {
            String name = req.getName().trim();
            if (name.isEmpty()) throw new BusinessException("Tên nhãn khoản chi là bắt buộc");
            if (!name.equalsIgnoreCase(c.getName()) && categoryRepo.existsByNameIgnoreCase(name)) {
                throw new BusinessException("Nhãn \"" + name + "\" đã tồn tại trong danh mục chung");
            }
            c.setName(name);
        }
        if (req.getDescription() != null) c.setDescription(req.getDescription().trim());
        if (req.getActive() != null) c.setActive(req.getActive());

        applyKind(c, req);
        return toCategoryDto(categoryRepo.save(c));
    }

    /**
     * VALIDATE + LIÊN KẾT DANH MỤC VẬT DỤNG.
     *
     * <ul>
     *   <li>{@code CONSUMABLE} ⇒ BẮT BUỘC {@code unit}, {@code specification} và
     *       {@code supplyItemId} (server tự sinh qua {@code getOrCreate}).</li>
     *   <li>{@code SERVICE} ⇒ {@code unit}/{@code specification} để trống được,
     *       {@code supplyItemId} = null, <b>KHÔNG nhập kho</b>.</li>
     * </ul>
     *
     * <p>Điểm mấu chốt: {@code getOrCreate(name, spec, unit)} tra theo BỘ BA đã
     * chuẩn hoá và KHÔNG chứa nhà cung cấp. Nhờ vậy "Nước rửa chén / 4L/chai /
     * Chai" được khai báo ở 10 NCC khác nhau vẫn trỏ về CÙNG MỘT SupplyItem, và
     * kho chỉ có 1 dòng tồn với số lượng cộng dồn.
     */
    private void applyKind(VendorExpenseCategory c, CategoryUpsertRequest req) {
        VendorExpenseCategory.CategoryKind kind = c.getCategoryKind() != null
                ? c.getCategoryKind()
                : VendorExpenseCategory.CategoryKind.SERVICE;
        if (req.getCategoryKind() != null && !req.getCategoryKind().isBlank()) {
            try {
                kind = VendorExpenseCategory.CategoryKind.valueOf(
                        req.getCategoryKind().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("Loại danh mục không hợp lệ: " + req.getCategoryKind());
            }
        }
        c.setCategoryKind(kind);

        if (kind == VendorExpenseCategory.CategoryKind.SERVICE) {
            // Dịch vụ: dọn sạch các field kho để không còn đường nào nhập kho nhầm.
            c.setUnit(null);
            c.setSpecification(null);
            c.setSupplyItemId(null);
            return;
        }

        String unit = req.getUnit() == null ? "" : req.getUnit().trim();
        String spec = req.getSpecification() == null ? "" : req.getSpecification().trim();
        if (unit.isEmpty())
            throw new BusinessException("Đồ dùng tiêu hao bắt buộc nhập Đơn vị tính");
        if (spec.isEmpty())
            throw new BusinessException("Đồ dùng tiêu hao bắt buộc nhập Quy cách");

        c.setUnit(unit);
        c.setSpecification(spec);

        // Chọn từ autocomplete → dùng đúng id đó (FE đã khoá 3 ô tên/quy cách/ĐVT).
        // Ngược lại → getOrCreate theo bộ ba đã chuẩn hoá.
        if (req.getSupplyItemId() != null) {
            c.setSupplyItemId(req.getSupplyItemId());
        } else {
            c.setSupplyItemId(supplyItemService.getOrCreate(c.getName(), spec, unit).getId());
        }
    }

    /** Ẩn nhãn (không xoá cứng — giữ toàn vẹn các phiếu chi đã tham chiếu). */
    @Transactional
    public void deleteCategory(Long categoryId) {
        VendorExpenseCategory c = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Nhãn khoản chi không tồn tại"));
        c.setActive(false);
        categoryRepo.save(c);
    }

    // ── Overload TƯƠNG THÍCH NGƯỢC (vendorId bị bỏ qua) ────────────────────

    /** @deprecated danh mục là pool chung — dùng {@link #listCategories(boolean)}. */
    @Deprecated
    public List<VendorExpenseCategoryDto> listCategories(Long vendorId, boolean activeOnly) {
        return listCategories(activeOnly);
    }

    /** @deprecated dùng {@link #createCategory(CategoryUpsertRequest, String)}. */
    @Deprecated
    public VendorExpenseCategoryDto createCategory(Long vendorId, CategoryUpsertRequest req, String createdByName) {
        return createCategory(req, createdByName);
    }

    /** @deprecated dùng {@link #updateCategory(Long, CategoryUpsertRequest)}. */
    @Deprecated
    public VendorExpenseCategoryDto updateCategory(Long vendorId, Long categoryId, CategoryUpsertRequest req) {
        return updateCategory(categoryId, req);
    }

    /** @deprecated dùng {@link #deleteCategory(Long)}. */
    @Deprecated
    public void deleteCategory(Long vendorId, Long categoryId) {
        deleteCategory(categoryId);
    }

    private VendorExpenseCategoryDto toCategoryDto(VendorExpenseCategory c) {
        return VendorExpenseCategoryDto.builder()
                .id(c.getId())
                .vendorId(null)            // pool chung — không thuộc NCC nào
                .name(c.getName())
                .description(c.getDescription())
                .active(c.isActive())
                .createdByName(c.getCreatedByName())
                .createdAt(c.getCreatedAt())
                .categoryKind(c.getCategoryKind() != null ? c.getCategoryKind().name() : "SERVICE")
                .unit(c.getUnit())
                .specification(c.getSpecification())
                .supplyItemId(c.getSupplyItemId())
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private MaterialVendor requireVendor(Long vendorId) {
        return vendorRepo.findById(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Nhà cung cấp không tồn tại"));
    }

    private boolean isOutstandingDebt(MaterialRequestVendor rv) {
        return rv.getPaymentStatus() == MaterialRequestVendor.VendorPaymentStatus.DEBT
                && rv.getDebtSettlementStatus() != MaterialRequestVendor.DebtSettlementStatus.SETTLED
                && remainingOf(rv).compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal remainingOf(MaterialRequestVendor rv) {
        BigDecimal r = nz(rv.getTotalAmount()).subtract(nz(rv.getPaidAmount()));
        return r.compareTo(BigDecimal.ZERO) > 0 ? r : BigDecimal.ZERO;
    }

    private BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}