package com.nhatnam.server.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PHÂN BỔ THUẾ/PHÍ VÀO GIÁ VỐN — nguồn chân lý duy nhất cho cả phiếu nhập kho và
 * phiếu đặt hàng nguyên liệu. FE có bản sao y hệt (src/utils/costCalc.js) để preview.
 *
 * <h3>Quy tắc</h3>
 * <ol>
 *   <li>Giá trị của 1 dòng: {@code lineValue = unitPrice × quantity}.</li>
 *   <li>Với mỗi khoản thuế/phí (mỗi loại là 1 dòng riêng, có phạm vi áp dụng riêng):
 *       phần mà dòng i phải gánh =
 *       {@code feeAmount × lineValue_i / Σ lineValue (trong phạm vi)}.</li>
 *   <li>Giá vốn 1 đơn vị = {@code unitPrice + (tổng phí gánh của dòng) / quantity}.</li>
 *   <li>CHỈ làm tròn ở BƯỚC CUỐI: giá vốn làm tròn tới hàng ĐƠN VỊ ĐỒNG (HALF_UP).
 *       Mọi bước trung gian giữ nguyên phần thập phân (scale 10).</li>
 *   <li>Không có thuế/phí → giá vốn = đơn giá nhập (vẫn làm tròn tới đồng).</li>
 * </ol>
 *
 * <h3>Ví dụ</h3>
 * <pre>
 *   A = 1.000.000, B = 1.200.000, C = 900.000 (qty đều = 1); phí = 500.000
 *   → A gánh 1.000.000/3.100.000 × 500.000 = 161.290,32…
 *   → giá vốn A = 1.000.000 + 161.290,32… = 1.161.290
 *      giá vốn B = 1.393.548 ; giá vốn C = 1.045.161
 * </pre>
 */
public final class CostAllocation {

    private CostAllocation() {}

    /** Scale dùng cho MỌI bước tính trung gian — không làm tròn sớm. */
    public static final int CALC_SCALE = 10;
    /** Số chữ số thập phân tối đa cho các số tiền người dùng nhập. */
    public static final int MONEY_SCALE = 3;

    // ── Input ────────────────────────────────────────────────────────────────

    /** Một dòng nguyên liệu cần tính giá vốn. */
    public record Line(Long id, BigDecimal quantity, BigDecimal unitPrice) {}

    /** Một khoản thuế/phí. {@code itemIds} rỗng/null = áp dụng cho tất cả các dòng. */
    public record Fee(String label, BigDecimal amount, List<Long> itemIds) {}

    // ── Output ───────────────────────────────────────────────────────────────

    /**
     * @param lineValue  đơn giá × số lượng (chưa gồm phí)
     * @param feeShare   tổng thuế/phí dòng này phải gánh (chưa làm tròn)
     * @param unitCost   GIÁ VỐN CUỐI CÙNG của 1 đơn vị — đã làm tròn tới đồng
     * @param lineCost   unitCost × quantity
     */
    public record Result(Long id, BigDecimal quantity, BigDecimal unitPrice,
                         BigDecimal lineValue, BigDecimal feeShare,
                         BigDecimal unitCost, BigDecimal lineCost,
                         Map<String, BigDecimal> feeBreakdown) {}

    /**
     * Tính giá vốn cho toàn bộ các dòng của một phiếu.
     *
     * @throws IllegalStateException nếu dữ liệu không hợp lệ (qty ≤ 0, đơn giá âm...)
     */
    public static Map<Long, Result> allocate(List<Line> lines, List<Fee> fees) {
        Objects.requireNonNull(lines, "lines");
        List<Fee> feeList = (fees == null) ? List.of() : fees;

        Map<Long, BigDecimal> qtyById = new LinkedHashMap<>();
        Map<Long, BigDecimal> unitPriceById = new LinkedHashMap<>();
        Map<Long, BigDecimal> lineValueById = new LinkedHashMap<>();

        for (Line l : lines) {
            if (l.quantity() == null || l.quantity().compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalStateException("Số lượng phải lớn hơn 0 (dòng id=" + l.id() + ")");
            if (l.unitPrice() == null || l.unitPrice().compareTo(BigDecimal.ZERO) < 0)
                throw new IllegalStateException("Đơn giá không hợp lệ (dòng id=" + l.id() + ")");
            qtyById.put(l.id(), l.quantity());
            unitPriceById.put(l.id(), l.unitPrice());
            lineValueById.put(l.id(), l.unitPrice().multiply(l.quantity()));
        }

        Map<Long, BigDecimal> feeShareById = new LinkedHashMap<>();
        Map<Long, Map<String, BigDecimal>> breakdownById = new LinkedHashMap<>();
        for (Long id : qtyById.keySet()) {
            feeShareById.put(id, BigDecimal.ZERO);
            breakdownById.put(id, new LinkedHashMap<>());
        }

        for (Fee fee : feeList) {
            BigDecimal amount = fee.amount();
            if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) continue;
            if (amount.compareTo(BigDecimal.ZERO) < 0)
                throw new IllegalStateException("Số tiền thuế/phí không được âm: " + fee.label());

            // Phạm vi áp dụng — rỗng = tất cả
            List<Long> scope = (fee.itemIds() == null || fee.itemIds().isEmpty())
                    ? new ArrayList<>(qtyById.keySet())
                    : fee.itemIds().stream().filter(qtyById::containsKey).toList();
            if (scope.isEmpty())
                throw new IllegalStateException("Khoản \"" + fee.label() + "\" chưa chọn nguyên liệu áp dụng");

            BigDecimal scopeTotal = scope.stream()
                    .map(lineValueById::get)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < scope.size(); i++) {
                Long id = scope.get(i);
                boolean isLast = (i == scope.size() - 1);
                BigDecimal share;
                if (isLast) {
                    // Dòng cuối nhận phần dư → tổng phân bổ luôn khớp đúng số tiền phí
                    share = amount.subtract(allocated);
                } else if (scopeTotal.compareTo(BigDecimal.ZERO) > 0) {
                    share = amount.multiply(lineValueById.get(id))
                            .divide(scopeTotal, CALC_SCALE, RoundingMode.HALF_UP);
                } else {
                    // Toàn bộ phạm vi có giá trị 0 → chia đều
                    share = amount.divide(BigDecimal.valueOf(scope.size()), CALC_SCALE, RoundingMode.HALF_UP);
                }
                allocated = allocated.add(share);
                feeShareById.merge(id, share, BigDecimal::add);
                breakdownById.get(id).merge(fee.label(), share, BigDecimal::add);
            }
        }

        Map<Long, Result> out = new LinkedHashMap<>();
        for (Long id : qtyById.keySet()) {
            BigDecimal qty = qtyById.get(id);
            BigDecimal feeShare = feeShareById.get(id);

            // giá vốn/đơn vị = đơn giá + phí gánh / số lượng — LÀM TRÒN TỚI ĐỒNG (bước cuối)
            BigDecimal feePerUnit = feeShare.divide(qty, CALC_SCALE, RoundingMode.HALF_UP);
            BigDecimal unitCost = unitPriceById.get(id).add(feePerUnit)
                    .setScale(0, RoundingMode.HALF_UP);

            out.put(id, new Result(
                    id,
                    qty,
                    unitPriceById.get(id),
                    lineValueById.get(id),
                    feeShare,
                    unitCost,
                    unitCost.multiply(qty),
                    breakdownById.get(id)));
        }
        return out;
    }

    /**
     * Đơn giá suy ra từ TỔNG TIỀN của 1 mặt hàng: {@code total / quantity},
     * làm tròn 3 số thập phân (HALF_UP).
     */
    public static BigDecimal unitPriceFromTotal(BigDecimal total, BigDecimal quantity) {
        if (total == null || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0)
            return BigDecimal.ZERO;
        return total.divide(quantity, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Chuẩn hoá số tiền người dùng nhập về tối đa 3 số thập phân. */
    public static BigDecimal normalizeMoney(BigDecimal v) {
        return v == null ? null : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
