package com.nhatnam.server.service;

import com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.ExpenseCategorySummaryDto;
import com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.MaterialPriceAnalysisDto;
import com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.MaterialPricePointDto;
import com.nhatnam.server.entity.ExpenseItem;
import com.nhatnam.server.entity.ExpenseVoucher;
import com.nhatnam.server.entity.MaterialRequest;
import com.nhatnam.server.entity.MaterialRequestItem;
import com.nhatnam.server.entity.MaterialRequestVendor;
import com.nhatnam.server.entity.VendorExpenseCategory;
import com.nhatnam.server.repository.ExpenseItemRepository;
import com.nhatnam.server.repository.MaterialRequestItemRepository;
import com.nhatnam.server.repository.VendorExpenseCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Trang "Phân tích danh mục chi" (mở từ Quản lý nhà cung cấp).
 *
 * <p>Gộp toàn bộ chi tiêu của nhà máy thành các "danh mục chi", từ 2 nguồn:
 * <ul>
 *   <li><b>MATERIAL</b> — nguyên liệu đã mua qua phiếu đặt hàng
 *       ({@link MaterialRequestItem} đã có đơn giá). "Giá" = đơn giá theo đvt.</li>
 *   <li><b>EXPENSE</b> — danh mục khoản chi của NCC ({@link VendorExpenseCategory}),
 *       lấy số liệu từ {@link ExpenseItem} của phiếu chi ĐÃ DUYỆT.
 *       "Giá" = số tiền của 1 lần chi/dùng dịch vụ.</li>
 * </ul>
 *
 * <p>Cả hai đều gộp theo TÊN (không phân biệt hoa/thường) trên MỌI nhà cung cấp,
 * và cùng trả về {@link MaterialPriceAnalysisDto} khi xem chi tiết — nhờ vậy FE
 * dùng chung đúng một trang phân tích giá.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpenseCategoryAnalysisService {

    /** Nhóm điểm giá của 1 danh mục — giữ tên hiển thị gốc (key gom nhóm đã normalize). */
    private static final class Group {
        final String displayName;
        final List<MaterialPricePointDto> points = new ArrayList<>();
        Group(String displayName) { this.displayName = displayName; }
    }

    public static final String KIND_MATERIAL = "MATERIAL";
    public static final String KIND_EXPENSE  = "EXPENSE";

    /** Đvt quy ước cho khoản chi (không có số lượng như nguyên liệu). */
    private static final String EXPENSE_UNIT = "lần";

    private final MaterialRequestItemRepository itemRepo;
    private final ExpenseItemRepository expenseItemRepo;
    private final VendorExpenseCategoryRepository categoryRepo;
    private final SupplierManagementService supplierService;

    // ══════════════════════════════════════════════════════════════════════
    // 1) Danh sách danh mục chi (có tìm kiếm theo tên)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * @param search lọc theo tên danh mục chi / tên nguyên liệu (contains, bỏ qua hoa-thường)
     * @param kind   MATERIAL | EXPENSE | null (= cả hai)
     */
    public List<ExpenseCategorySummaryDto> listCategories(String search, String kind) {
        String q = normalize(search);
        List<ExpenseCategorySummaryDto> out = new ArrayList<>();

        if (kind == null || kind.isBlank() || KIND_MATERIAL.equalsIgnoreCase(kind)) {
            out.addAll(summarize(materialPointsByName(), KIND_MATERIAL));
        }
        if (kind == null || kind.isBlank() || KIND_EXPENSE.equalsIgnoreCase(kind)) {
            out.addAll(summarize(expensePointsByName(), KIND_EXPENSE));
        }

        return out.stream()
                .filter(c -> q.isEmpty() || normalize(c.getName()).contains(q))
                // tiêu nhiều nhất lên trước — đúng thứ tự quan tâm của chủ nhà máy
                .sorted(Comparator.comparing(
                        (ExpenseCategorySummaryDto c) -> nz(c.getTotalSpent())).reversed())
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2) Phân tích chi tiết 1 danh mục — dùng chung DTO với phân tích giá NL
    // ══════════════════════════════════════════════════════════════════════

    public MaterialPriceAnalysisDto analyze(String name, String kind) {
        // Nguyên liệu → tái dùng nguyên logic phân tích giá đã có (đa NCC theo tên)
        if (kind == null || kind.isBlank() || KIND_MATERIAL.equalsIgnoreCase(kind)) {
            MaterialPriceAnalysisDto dto = supplierService.getMaterialPriceAnalysis(name);
            dto.setKind(KIND_MATERIAL);
            return dto;
        }

        Group g = expensePointsByName().get(normalize(name));
        if (g == null || g.points.isEmpty()) {
            return MaterialPriceAnalysisDto.builder()
                    .kind(KIND_EXPENSE).materialName(name).unit(EXPENSE_UNIT)
                    .purchaseCount(0).vendorCount(0).points(List.of()).build();
        }
        return buildAnalysis(g.displayName, EXPENSE_UNIT, KIND_EXPENSE, g.points);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Nguồn dữ liệu → điểm giá, gom theo tên (key = tên đã normalize)
    // ══════════════════════════════════════════════════════════════════════

    /** Nguyên liệu: mỗi dòng phiếu đặt hàng đã có đơn giá = 1 điểm giá. */
    private Map<String, Group> materialPointsByName() {
        Map<String, Group> map = new LinkedHashMap<>();
        for (MaterialRequestItem i : itemRepo.findAllPriced()) {
            MaterialRequestVendor rv = i.getSuppliedByVendor();
            if (rv == null) continue;
            MaterialRequest mr = rv.getMaterialRequest();
            Long at = mr.getCompletedAt() != null ? mr.getCompletedAt() : mr.getCreatedAt();
            String vName = rv.getVendor() != null ? rv.getVendor().getName() : rv.getVendorName();

            map.computeIfAbsent(normalize(i.getMaterialName()), k -> new Group(i.getMaterialName()))
                    .points.add(MaterialPricePointDto.builder()
                            .unitPrice(i.getUnitPrice())
                            .quantity(i.getQtyReceived() != null ? i.getQtyReceived() : i.getQtyRequested())
                            .unit(i.getUnit())
                            .at(at)
                            .requestCode(mr.getRequestCode())
                            .vendorName(vName)
                            .vendorId(rv.getVendor() != null ? rv.getVendor().getId() : null)
                            .build());
        }
        return map;
    }

    /**
     * Khoản chi: mỗi {@link ExpenseItem} của phiếu chi ĐÃ DUYỆT = 1 điểm giá.
     * Tên danh mục lấy theo {@code categoryId} → {@link VendorExpenseCategory#getName()};
     * phiếu cũ (categoryId null, gõ tự do) fallback về {@code itemName}.
     * Mốc thời gian ưu tiên effectiveAt → expenseDate → createdAt (xem ExpenseVoucher).
     */
    private Map<String, Group> expensePointsByName() {
        Map<Long, VendorExpenseCategory> catById = new HashMap<>();
        for (VendorExpenseCategory c : categoryRepo.findAll()) catById.put(c.getId(), c);

        Map<String, Group> map = new LinkedHashMap<>();
        for (ExpenseItem it : expenseItemRepo.findAllByVoucherStatus(ExpenseVoucher.VoucherStatus.APPROVED)) {
            ExpenseVoucher v = it.getVoucher();
            if (v == null || it.getAmount() == null) continue;

            VendorExpenseCategory cat = it.getCategoryId() != null ? catById.get(it.getCategoryId()) : null;
            String catName = cat != null ? cat.getName() : it.getItemName();
            if (catName == null || catName.isBlank()) continue;

            Long at = v.getEffectiveAt() != null ? v.getEffectiveAt()
                    : v.getExpenseDate() != null ? v.getExpenseDate()
                    : v.getCreatedAt();
            // Danh mục giờ là POOL CHUNG (không còn thuộc NCC nào) → tên NCC chỉ
            // lấy từ chính phiếu chi.
            String vendorName = v.getVendorName() != null && !v.getVendorName().isBlank()
                    ? v.getVendorName()
                    : null;

            final String label = catName;
            map.computeIfAbsent(normalize(catName), k -> new Group(label))
                    .points.add(MaterialPricePointDto.builder()
                            .unitPrice(it.getAmount())
                            .quantity(BigDecimal.ONE)      // 1 lần chi
                            .unit(EXPENSE_UNIT)
                            .at(at)
                            .requestCode(v.getPaymentNumber() != null && !v.getPaymentNumber().isBlank()
                                    ? v.getPaymentNumber() : v.getVoucherCode())
                            .vendorName(vendorName)
                            .vendorId(v.getVendorId())
                            .build());
        }
        return map;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Tổng hợp
    // ══════════════════════════════════════════════════════════════════════

    private List<ExpenseCategorySummaryDto> summarize(Map<String, Group> byName, String kind) {
        List<ExpenseCategorySummaryDto> out = new ArrayList<>();
        for (Group g : byName.values()) {
            if (g.points.isEmpty()) continue;
            List<MaterialPricePointDto> points = sortedByTime(g.points);

            MaterialPricePointDto min  = points.stream().min(Comparator.comparing(MaterialPricePointDto::getUnitPrice)).get();
            MaterialPricePointDto max  = points.stream().max(Comparator.comparing(MaterialPricePointDto::getUnitPrice)).get();
            MaterialPricePointDto last = points.get(points.size() - 1);

            BigDecimal totalQty = points.stream().map(p -> nz(p.getQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalSpent = points.stream()
                    .map(p -> p.getUnitPrice().multiply(nz(p.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            out.add(ExpenseCategorySummaryDto.builder()
                    .kind(kind)
                    .name(g.displayName)
                    .unit(points.get(0).getUnit())
                    .purchaseCount(points.size())
                    .vendorCount((int) distinctVendors(points))
                    .minPrice(min.getUnitPrice()).minAt(min.getAt()).minVendorName(min.getVendorName())
                    .maxPrice(max.getUnitPrice()).maxAt(max.getAt()).maxVendorName(max.getVendorName())
                    .latestPrice(last.getUnitPrice()).latestAt(last.getAt()).latestVendorName(last.getVendorName())
                    .totalSpent(totalSpent)
                    .totalQuantity(totalQty)
                    .build());
        }
        return out;
    }

    /** Dựng {@link MaterialPriceAnalysisDto} đầy đủ chỉ số từ danh sách điểm giá. */
    private MaterialPriceAnalysisDto buildAnalysis(
            String name, String unit, String kind, List<MaterialPricePointDto> raw) {

        List<MaterialPricePointDto> points = sortedByTime(raw);
        int n = points.size();

        MaterialPricePointDto minP  = points.stream().min(Comparator.comparing(MaterialPricePointDto::getUnitPrice)).get();
        MaterialPricePointDto maxP  = points.stream().max(Comparator.comparing(MaterialPricePointDto::getUnitPrice)).get();
        MaterialPricePointDto firstP = points.get(0);
        MaterialPricePointDto lastP  = points.get(n - 1);

        BigDecimal simpleAvg = points.stream().map(MaterialPricePointDto::getUnitPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        BigDecimal totalQty = points.stream().map(p -> nz(p.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSpent = points.stream()
                .map(p -> p.getUnitPrice().multiply(nz(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weightedAvg = totalQty.compareTo(BigDecimal.ZERO) > 0
                ? totalSpent.divide(totalQty, 2, RoundingMode.HALF_UP)
                : simpleAvg;

        List<BigDecimal> sorted = points.stream().map(MaterialPricePointDto::getUnitPrice)
                .sorted().collect(Collectors.toList());
        BigDecimal median = (n % 2 == 1)
                ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        // Xu hướng: lần chi/mua gần nhất so với lần LIỀN TRƯỚC
        MaterialPricePointDto prevP = n >= 2 ? points.get(n - 2) : null;
        BigDecimal trendPct = null;
        if (prevP != null && prevP.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            trendPct = lastP.getUnitPrice().subtract(prevP.getUnitPrice())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(prevP.getUnitPrice(), 1, RoundingMode.HALF_UP);
        }
        BigDecimal volatilityPct = null;
        if (minP.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            volatilityPct = maxP.getUnitPrice().subtract(minP.getUnitPrice())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(minP.getUnitPrice(), 1, RoundingMode.HALF_UP);
        }

        return MaterialPriceAnalysisDto.builder()
                .kind(kind)
                .materialName(name)
                .unit(unit)
                .purchaseCount(n)
                .vendorCount((int) distinctVendors(points))
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

    // ── helpers ───────────────────────────────────────────────────────────

    private List<MaterialPricePointDto> sortedByTime(List<MaterialPricePointDto> points) {
        return points.stream()
                .sorted(Comparator.comparing(p -> p.getAt() == null ? 0L : p.getAt()))
                .collect(Collectors.toList());
    }

    private long distinctVendors(List<MaterialPricePointDto> points) {
        return points.stream()
                .map(p -> p.getVendorId() != null ? "#" + p.getVendorId() : normalize(p.getVendorName()))
                .filter(s -> !s.isEmpty())
                .distinct().count();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}