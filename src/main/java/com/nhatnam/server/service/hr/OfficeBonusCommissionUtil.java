// src/main/java/com/nhatnam/server/service/hr/OfficeBonusCommissionUtil.java
package com.nhatnam.server.service.hr;

import com.nhatnam.server.enumtype.Role;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * TÍNH HOA HỒNG DOANH THU — SALES &amp; ACCOUNTING (11/2026).
 *
 * <h3>Công thức chung</h3>
 * <pre>
 *   rawCommission = revenue × unitPrice ÷ 100.000.000
 * </pre>
 *
 * <h3>SALES — mỗi seller tính riêng</h3>
 * <pre>
 *   bonus = CEIL(rawCommission ÷ 5000) × 5000           // làm tròn LÊN 5.000
 *   VD: raw = 26.134.253,3 → bonus = 26.135.000
 * </pre>
 *
 * <h3>ACCOUNTING — cả phòng chia theo trọng số</h3>
 * <pre>
 *   rawPool  = revenue × unitPrice ÷ 100.000.000       // KHÔNG làm tròn
 *   KẾ TOÁN TRƯỞNG weight = 2, chuyên viên weight = 1
 *   unitShare = FLOOR(rawPool ÷ sumWeight ÷ 5000) × 5000 // làm tròn XUỐNG 5.000
 *   mỗi người = weight × unitShare, phần dư (ko chia được bội 5.000) BỎ.
 *
 *   VD: rawPool = 16.904.530,856; 1 KTT + 3 CV ⇒ sumWeight = 5
 *       unitShare = FLOOR(16.904.530,856 / 5 / 5000) × 5000
 *                 = 676 × 5000 = 3.380.000
 *       KTT   = 2 × 3.380.000 = 6.760.000
 *       mỗi CV = 1 × 3.380.000 = 3.380.000
 *       Tổng chia = 16.900.000; dư 4.530,856 → bỏ.
 * </pre>
 *
 * <p>Toàn bộ phép tính dùng {@link BigDecimal} rồi mới chốt về {@code long} ở
 * bước cuối để tránh sai số float.
 */
public final class OfficeBonusCommissionUtil {

    /** Mức làm tròn hoa hồng — đơn vị đồng. */
    public static final long ROUND_STEP = 5_000L;

    /** Mẫu số đơn giá: hoa hồng tính trên mỗi 100tr doanh thu. */
    public static final BigDecimal UNIT_BASE = new BigDecimal("100000000"); // 100tr

    private static final BigDecimal STEP_BD = BigDecimal.valueOf(ROUND_STEP);

    private OfficeBonusCommissionUtil() {}

    // ══════════════════════════════════════════════════════════════════════════
    // CORE
    // ══════════════════════════════════════════════════════════════════════════

    /** rawCommission = revenue × unitPrice ÷ 100.000.000 (không làm tròn). */
    public static BigDecimal rawCommission(BigDecimal revenue, long unitPrice) {
        if (revenue == null || revenue.signum() <= 0 || unitPrice <= 0)
            return BigDecimal.ZERO;
        return revenue.multiply(BigDecimal.valueOf(unitPrice))
                .divide(UNIT_BASE, 10, RoundingMode.HALF_UP);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SALES
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Hoa hồng cho 1 seller — làm tròn LÊN bước {@value #ROUND_STEP}.
     * Trả 0 khi revenue hoặc unitPrice ≤ 0.
     */
    public static long salesCommission(BigDecimal revenue, long unitPrice) {
        BigDecimal raw = rawCommission(revenue, unitPrice);
        if (raw.signum() <= 0) return 0L;
        return raw.divide(STEP_BD, 0, RoundingMode.CEILING)
                .multiply(STEP_BD)
                .longValueExact();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACCOUNTING
    // ══════════════════════════════════════════════════════════════════════════

    /** Trọng số chia pool theo role nhận lương. */
    public static int accountingWeightOf(Role payrollRole) {
        if (payrollRole == null) return 1;
        return payrollRole == Role.SUPER_ACCOUNTANT ? 2 : 1;
    }

    /** 1 phần chia của 1 nhân viên kế toán sau khi split pool. */
    public record AccountingShare(long userId, long bonusAmount, int weight) {}

    /** Input cho {@link #splitAccountingPool}: userId + weight. */
    public record UserWeight(long userId, int weight) {}

    /**
     * Chia pool chung cho các nhân viên theo trọng số, mỗi phần là bội của
     * {@value #ROUND_STEP}. Phần dư không chia được → bỏ.
     *
     * @param rawPool    quỹ chung (chưa làm tròn), tính từ {@link #rawCommission}
     * @param userWeights danh sách (userId, weight). Thứ tự được bảo toàn ở
     *                    output để caller giữ sort theo chức vụ.
     */
    public static List<AccountingShare> splitAccountingPool(
            BigDecimal rawPool, List<UserWeight> userWeights) {

        if (userWeights == null || userWeights.isEmpty()) return List.of();

        int sumWeight = userWeights.stream().mapToInt(UserWeight::weight).sum();
        if (sumWeight <= 0 || rawPool == null || rawPool.signum() <= 0) {
            return userWeights.stream()
                    .map(uw -> new AccountingShare(uw.userId(), 0L, uw.weight()))
                    .toList();
        }

        // unitShare = FLOOR(rawPool / sumWeight / step) × step
        long unitShare = rawPool
                .divide(BigDecimal.valueOf(sumWeight), 10, RoundingMode.DOWN)
                .divide(STEP_BD, 0, RoundingMode.DOWN)
                .multiply(STEP_BD)
                .longValueExact();

        return userWeights.stream()
                .map(uw -> new AccountingShare(
                        uw.userId(),
                        unitShare * (long) uw.weight(),
                        uw.weight()))
                .toList();
    }
}