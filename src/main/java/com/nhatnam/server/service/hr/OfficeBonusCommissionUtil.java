// src/main/java/com/nhatnam/server/service/hr/OfficeBonusCommissionUtil.java
package com.nhatnam.server.service.hr;

import com.nhatnam.server.enumtype.Role;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TÍNH HOA HỒNG DOANH THU — SALES &amp; ACCOUNTING.
 *
 * <h3>Flow mới (11/2026 refactor v2)</h3>
 *
 * <h4>SALES — tier-based per seller</h4>
 * Mỗi tier có {@code threshold} (ngưỡng doanh thu, VNĐ) và {@code unitPrice}
 * (tiền thưởng khi doanh thu đạt đúng 1 lần ngưỡng đó).
 * <pre>
 *   remaining = revenue; raw = 0
 *   for each tier (sort DESC theo threshold):
 *       k = floor(remaining / tier.threshold)
 *       raw += k × tier.unitPrice
 *       remaining -= k × tier.threshold
 *   // remaining không đủ ngưỡng nào → bỏ
 *   bonus = CEIL(raw / 5000) × 5000           // làm tròn LÊN 5.000
 * </pre>
 * Ví dụ tiers = [200tr : 400k, 100tr : 180k, 70tr : 100k]:
 * <ul>
 *   <li>revenue 1.485tr → 7×200tr = 1.400tr → raw = 7×400k = 2.800k; dư 85tr
 *       → đủ 70tr: +1×100k = 2.900k; dư 15tr bỏ → bonus = 2.900.000đ.</li>
 *   <li>revenue 650tr → 3×200tr = 600tr → raw = 3×400k = 1.200k; dư 50tr &lt; 70tr → bỏ.
 *       → bonus = 1.200.000đ.</li>
 *   <li>revenue 565tr → 2×200tr = 400tr → raw = 800k; dư 165tr
 *       → 1×100tr: +180k = 980k; dư 65tr &lt; 70tr → bỏ.
 *       → bonus = 980.000đ.</li>
 * </ul>
 *
 * <h4>ACCOUNTING — pool × % phòng, chia theo tỉ lệ từng người</h4>
 * <pre>
 *   pool_raw   = revenue × deptPercent / 100
 *   mỗi người: share_raw = pool_raw × ratio_i / 100
 *              share     = CEIL(share_raw / 5000) × 5000      // làm tròn LÊN 5.000
 *   số dư = pool_raw × (100 - Σ ratio_i) / 100   (hiển thị, không chia)
 * </pre>
 * Σ ratio_i phải ≤ 100; FE cần cảnh báo nếu &lt; 100 (phần dư bỏ) và chặn &gt; 100.
 *
 * <p>Toàn bộ phép tính dùng {@link BigDecimal} rồi mới chốt về {@code long}.
 */
public final class OfficeBonusCommissionUtil {

    /** Mức làm tròn hoa hồng — đơn vị đồng. */
    public static final long ROUND_STEP = 5_000L;

    /** Mẫu số đơn giá LEGACY: hoa hồng tính trên mỗi 100tr doanh thu. */
    public static final BigDecimal UNIT_BASE = new BigDecimal("100000000"); // 100tr

    private static final BigDecimal STEP_BD = BigDecimal.valueOf(ROUND_STEP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private OfficeBonusCommissionUtil() {}

    // ══════════════════════════════════════════════════════════════════════════
    // SALES — TIER-BASED (flow MỚI 2026)
    // ══════════════════════════════════════════════════════════════════════════

    /** 1 mốc thưởng doanh thu kinh doanh. */
    public record SalesTier(BigDecimal threshold, long unitPrice) {
        public SalesTier {
            if (threshold == null || threshold.signum() <= 0)
                throw new IllegalArgumentException("Mốc doanh thu phải > 0");
            if (unitPrice <= 0)
                throw new IllegalArgumentException("Đơn giá thưởng phải > 0");
        }
    }

    /** Kết quả tính thưởng SALES — tiền thưởng + phần doanh thu còn dư (chưa đủ mốc nào). */
    public record SalesBonusResult(long bonusAmount, long rawBonus, BigDecimal unmatchedRevenue,
                                   List<TierBreakdown> breakdown) {}

    /** Chi tiết tính của 1 mốc trong 1 lần tính. */
    public record TierBreakdown(BigDecimal threshold, long unitPrice, int count, long subtotal) {}

    /**
     * Tính hoa hồng SALES theo nhiều mốc. Mốc nào cao nhất tính trước, dư thì
     * xuống mốc thấp hơn. Phần không đủ mốc thấp nhất → bỏ.
     * Số cuối cùng làm tròn LÊN bước {@value #ROUND_STEP}.
     */
    public static SalesBonusResult salesCommissionTiered(BigDecimal revenue, List<SalesTier> tiersIn) {
        if (revenue == null || revenue.signum() <= 0 || tiersIn == null || tiersIn.isEmpty())
            return new SalesBonusResult(0L, 0L, revenue == null ? BigDecimal.ZERO : revenue, List.of());

        // Sort DESC theo threshold
        List<SalesTier> tiers = new ArrayList<>(tiersIn);
        tiers.sort(Comparator.comparing(SalesTier::threshold).reversed());

        BigDecimal remaining = revenue;
        long raw = 0L;
        List<TierBreakdown> br = new ArrayList<>();

        for (SalesTier t : tiers) {
            // k = floor(remaining / threshold)
            BigDecimal[] dr = remaining.divideAndRemainder(t.threshold());
            long k = dr[0].longValueExact();
            if (k > 0) {
                long subtotal = Math.multiplyExact(k, t.unitPrice());
                raw = Math.addExact(raw, subtotal);
                remaining = dr[1];
                br.add(new TierBreakdown(t.threshold(), t.unitPrice(), (int) k, subtotal));
            }
        }

        long rounded = roundUp5000(raw);
        return new SalesBonusResult(rounded, raw, remaining, br);
    }

    /** Làm tròn LÊN bước {@value #ROUND_STEP}. */
    public static long roundUp5000(long v) {
        if (v <= 0) return 0L;
        long r = v % ROUND_STEP;
        return r == 0 ? v : v + (ROUND_STEP - r);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACCOUNTING — pool × % phòng, chia theo tỉ lệ từng người (flow MỚI 2026)
    // ══════════════════════════════════════════════════════════════════════════

    /** 1 tỉ lệ của 1 nhân viên trong phòng (userId + ratio %). */
    public record UserRatio(long userId, BigDecimal ratio) {}

    /** Phần chia của 1 nhân viên kế toán. */
    public record AccountingShareV2(long userId, long bonusAmount, long rawShare,
                                    BigDecimal ratio) {}

    /** Kết quả chia pool. */
    public record AccountingPoolResult(long totalPool, long distributedTotal,
                                       long undistributed, BigDecimal poolRaw,
                                       List<AccountingShareV2> shares) {}

    /**
     * Chia pool kế toán theo tỉ lệ:
     *   pool_raw = collectedRevenue × deptPercent / 100
     *   share_raw(i) = pool_raw × ratio_i / 100 → ceil 5k
     *   dư = pool_raw × (100 - Σratio) / 100  (hiển thị, bỏ)
     *
     * Tổng ratio có thể < 100 (dư) — không ép bằng 100 ở BE để FE tự cảnh báo.
     * Tổng > 100 sẽ throw.
     */
    public static AccountingPoolResult splitAccountingByRatios(BigDecimal collectedRevenue,
                                                               BigDecimal deptPercent,
                                                               List<UserRatio> userRatios) {
        if (collectedRevenue == null || collectedRevenue.signum() <= 0
                || deptPercent == null || deptPercent.signum() <= 0
                || userRatios == null || userRatios.isEmpty()) {
            return new AccountingPoolResult(0L, 0L, 0L, BigDecimal.ZERO,
                    userRatios == null ? List.of() :
                            userRatios.stream()
                                    .map(ur -> new AccountingShareV2(ur.userId(), 0L, 0L, ur.ratio()))
                                    .toList());
        }

        BigDecimal totalRatio = userRatios.stream()
                .map(UserRatio::ratio)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalRatio.compareTo(HUNDRED) > 0)
            throw new IllegalArgumentException(
                    "Tổng tỉ lệ phòng vượt 100% (= " + totalRatio.toPlainString() + "%).");

        // pool_raw = collectedRevenue × deptPercent / 100
        BigDecimal poolRaw = collectedRevenue
                .multiply(deptPercent)
                .divide(HUNDRED, 10, RoundingMode.HALF_UP);
        long poolRawLong = poolRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();

        List<AccountingShareV2> shares = new ArrayList<>();
        long distributed = 0L;
        for (UserRatio ur : userRatios) {
            BigDecimal ratio = ur.ratio() == null ? BigDecimal.ZERO : ur.ratio();
            BigDecimal shareRaw = poolRaw.multiply(ratio).divide(HUNDRED, 10, RoundingMode.HALF_UP);
            long shareRawL = shareRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
            long shareRounded = roundUp5000(shareRawL);
            distributed += shareRounded;
            shares.add(new AccountingShareV2(ur.userId(), shareRounded, shareRawL, ratio));
        }

        // Số dư NGUYÊN BẢN (không làm tròn các share) = pool_raw × (100 - Σratio) / 100
        BigDecimal undistributedBd = poolRaw.multiply(HUNDRED.subtract(totalRatio))
                .divide(HUNDRED, 10, RoundingMode.HALF_UP);
        long undistributed = Math.max(0L, undistributedBd.setScale(0, RoundingMode.HALF_UP).longValueExact());

        return new AccountingPoolResult(poolRawLong, distributed, undistributed, poolRaw, shares);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LEGACY (giữ cho dữ liệu cũ hiển thị — flow mới KHÔNG dùng)
    // ══════════════════════════════════════════════════════════════════════════

    @Deprecated
    public static BigDecimal rawCommission(BigDecimal revenue, long unitPrice) {
        if (revenue == null || revenue.signum() <= 0 || unitPrice <= 0)
            return BigDecimal.ZERO;
        return revenue.multiply(BigDecimal.valueOf(unitPrice))
                .divide(UNIT_BASE, 10, RoundingMode.HALF_UP);
    }

    @Deprecated
    public static long salesCommission(BigDecimal revenue, long unitPrice) {
        BigDecimal raw = rawCommission(revenue, unitPrice);
        if (raw.signum() <= 0) return 0L;
        return raw.divide(STEP_BD, 0, RoundingMode.CEILING)
                .multiply(STEP_BD)
                .longValueExact();
    }

    @Deprecated
    public static int accountingWeightOf(Role payrollRole) {
        if (payrollRole == null) return 1;
        return payrollRole == Role.SUPER_ACCOUNTANT ? 2 : 1;
    }

    @Deprecated
    public record AccountingShare(long userId, long bonusAmount, int weight) {}

    @Deprecated
    public record UserWeight(long userId, int weight) {}

    @Deprecated
    public static List<AccountingShare> splitAccountingPool(
            BigDecimal rawPool, List<UserWeight> userWeights) {
        if (userWeights == null || userWeights.isEmpty()) return List.of();
        int sumWeight = userWeights.stream().mapToInt(UserWeight::weight).sum();
        if (sumWeight <= 0 || rawPool == null || rawPool.signum() <= 0) {
            return userWeights.stream()
                    .map(uw -> new AccountingShare(uw.userId(), 0L, uw.weight()))
                    .toList();
        }
        long unitShare = rawPool
                .divide(BigDecimal.valueOf(sumWeight), 10, RoundingMode.DOWN)
                .divide(STEP_BD, 0, RoundingMode.DOWN)
                .multiply(STEP_BD)
                .longValueExact();
        return userWeights.stream()
                .map(uw -> new AccountingShare(uw.userId(),
                        unitShare * (long) uw.weight(), uw.weight()))
                .toList();
    }
}