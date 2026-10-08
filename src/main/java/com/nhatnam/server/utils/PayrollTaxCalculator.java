package com.nhatnam.server.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tính bảo hiểm bắt buộc (BHXH/BHYT/BHTN) và thuế thu nhập cá nhân (TNCN)
 * theo luật lao động Việt Nam hiện hành (áp dụng từ kỳ tính thuế 01/01/2026):
 *
 * <p><b>Bảo hiểm bắt buộc — phần người lao động đóng:</b>
 * <ul>
 *   <li>BHXH: 8%</li>
 *   <li>BHYT: 1.5%</li>
 *   <li>BHTN: 1%</li>
 *   <li>Tổng: 10.5%</li>
 * </ul>
 *
 * <p><b>Giảm trừ gia cảnh (Nghị quyết 110/2025/UBTVQH15, hiệu lực 01/01/2026):</b>
 * <ul>
 *   <li>Bản thân người nộp thuế: 15.500.000đ/tháng</li>
 *   <li>Mỗi người phụ thuộc: 6.200.000đ/tháng</li>
 * </ul>
 *
 * <p><b>Biểu thuế lũy tiến từng phần 5 bậc (Luật Thuế TNCN 2025, Điều 9,
 * áp dụng từ kỳ tính thuế 2026):</b>
 * <pre>
 * Bậc  Thu nhập tính thuế/tháng     Thuế suất   Công thức rút gọn
 * 1    Đến 10 triệu                  5%         5% x TNTT
 * 2    Trên 10 đến 30 triệu          10%        10% x TNTT - 0.5tr
 * 3    Trên 30 đến 60 triệu          20%        20% x TNTT - 3.5tr
 * 4    Trên 60 đến 100 triệu         30%        30% x TNTT - 9.5tr
 * 5    Trên 100 triệu                35%        35% x TNTT - 14.5tr
 * </pre>
 * (TNTT = Thu nhập tính thuế = Thu nhập trước thuế - giảm trừ gia cảnh)
 */
public final class PayrollTaxCalculator {

    private PayrollTaxCalculator() {}

    // ── Tỷ lệ bảo hiểm bắt buộc (phần người lao động đóng) ─────────────────────
    public static final BigDecimal SOCIAL_INSURANCE_RATE       = new BigDecimal("0.08");   // BHXH 8%
    public static final BigDecimal HEALTH_INSURANCE_RATE       = new BigDecimal("0.015");  // BHYT 1.5%
    public static final BigDecimal UNEMPLOYMENT_INSURANCE_RATE = new BigDecimal("0.01");   // BHTN 1%
    public static final BigDecimal TOTAL_INSURANCE_RATE        =
            SOCIAL_INSURANCE_RATE.add(HEALTH_INSURANCE_RATE).add(UNEMPLOYMENT_INSURANCE_RATE); // 10.5%

    // ── Tỷ lệ bảo hiểm bắt buộc (phần DOANH NGHIỆP đóng) ────────────────────────
    public static final BigDecimal EMPLOYER_SOCIAL_INSURANCE_RATE       = new BigDecimal("0.17");   // BHXH 17%
    public static final BigDecimal EMPLOYER_ACCIDENT_INSURANCE_RATE     = new BigDecimal("0.005");  // BH TNLĐ-BNN 0.5%
    public static final BigDecimal EMPLOYER_HEALTH_INSURANCE_RATE       = new BigDecimal("0.03");   // BHYT 3%
    public static final BigDecimal EMPLOYER_UNEMPLOYMENT_INSURANCE_RATE = new BigDecimal("0.01");   // BHTN 1%
    public static final BigDecimal EMPLOYER_TOTAL_INSURANCE_RATE        =
            EMPLOYER_SOCIAL_INSURANCE_RATE.add(EMPLOYER_ACCIDENT_INSURANCE_RATE)
                    .add(EMPLOYER_HEALTH_INSURANCE_RATE).add(EMPLOYER_UNEMPLOYMENT_INSURANCE_RATE); // 21.5%

    // ── Giảm trừ gia cảnh (áp dụng từ kỳ tính thuế 2026) ───────────────────────
    public static final long PERSONAL_DEDUCTION   = 15_500_000L; // bản thân / tháng
    public static final long DEPENDENT_DEDUCTION   = 6_200_000L;  // mỗi người phụ thuộc / tháng

    // ── Công chuẩn (dùng cho lương tính theo giờ — nhân viên Xưởng/Sản xuất) ────
    /**
     * Số ngày công chuẩn MẶC ĐỊNH — chỉ dùng khi KHÔNG biết đang tính cho tháng nào.
     *
     * <p><b>Đừng dùng hằng số này để chia lương theo ngày công.</b> Số công chuẩn
     * thật phụ thuộc từng tháng (xem {@link #standardWorkdaysOf}): tháng 7/2026 có
     * 27 công, tháng 2/2026 chỉ có 24. Lấy cứng 26 sẽ khiến tháng dài bị trả dư
     * (làm 26/27 công vẫn ăn đủ lương) còn tháng ngắn bị trả thiếu.
     */
    public static final int    STANDARD_WORKDAYS  = 26;
    /** Số giờ làm việc chuẩn mỗi ngày. */
    public static final int    HOURS_PER_DAY      = 8;
    /** Tổng số giờ công chuẩn 1 tháng = 26 × 8 = 208 giờ. */
    public static final double STANDARD_WORK_HOURS = STANDARD_WORKDAYS * HOURS_PER_DAY; // 208

    /** Một bậc trong biểu thuế lũy tiến từng phần. */
    private static final class TaxBracket {
        final long upTo;
        final BigDecimal rate;
        final long deduction;
        TaxBracket(long upTo, BigDecimal rate, long deduction) {
            this.upTo = upTo; this.rate = rate; this.deduction = deduction;
        }
    }

    /**
     * Biểu thuế lũy tiến từng phần 5 bậc 2026 — dạng "công thức rút gọn":
     * Thuế = TNTT x thuế suất - khoản trừ.
     * upTo = Long.MAX_VALUE cho bậc cao nhất (không giới hạn trên).
     */
    private static final TaxBracket[] BRACKETS = {
            new TaxBracket(10_000_000L,  new BigDecimal("0.05"), 0L),
            new TaxBracket(30_000_000L,  new BigDecimal("0.10"), 500_000L),
            new TaxBracket(60_000_000L,  new BigDecimal("0.20"), 3_500_000L),
            new TaxBracket(100_000_000L, new BigDecimal("0.30"), 9_500_000L),
            new TaxBracket(Long.MAX_VALUE, new BigDecimal("0.35"), 14_500_000L),
    };

    /** Kết quả tính bảo hiểm bắt buộc. */
    public static final class InsuranceResult {
        public final long socialInsurance, healthInsurance, unemploymentInsurance, total;
        public InsuranceResult(long socialInsurance, long healthInsurance, long unemploymentInsurance, long total) {
            this.socialInsurance = socialInsurance;
            this.healthInsurance = healthInsurance;
            this.unemploymentInsurance = unemploymentInsurance;
            this.total = total;
        }
    }

    /** Kết quả tính bảo hiểm bắt buộc phần DOANH NGHIỆP đóng. */
    public static final class EmployerInsuranceResult {
        public final long socialInsurance, accidentInsurance, healthInsurance, unemploymentInsurance, total;
        public EmployerInsuranceResult(long socialInsurance, long accidentInsurance,
                                       long healthInsurance, long unemploymentInsurance, long total) {
            this.socialInsurance = socialInsurance;
            this.accidentInsurance = accidentInsurance;
            this.healthInsurance = healthInsurance;
            this.unemploymentInsurance = unemploymentInsurance;
            this.total = total;
        }
    }

    /**
     * Tính 3 khoản bảo hiểm bắt buộc (phần NLĐ đóng) trên mức lương đóng bảo hiểm.
     * @param insuranceSalary mức lương dùng làm căn cứ đóng BHXH/BHYT/BHTN (VNĐ)
     */
    public static InsuranceResult calcInsurance(long insuranceSalary) {
        if (insuranceSalary <= 0) return new InsuranceResult(0, 0, 0, 0);
        BigDecimal base = BigDecimal.valueOf(insuranceSalary);
        long si = round(base.multiply(SOCIAL_INSURANCE_RATE));
        long hi = round(base.multiply(HEALTH_INSURANCE_RATE));
        long ui = round(base.multiply(UNEMPLOYMENT_INSURANCE_RATE));
        return new InsuranceResult(si, hi, ui, si + hi + ui);
    }

    /**
     * Tính 4 khoản bảo hiểm bắt buộc (phần DOANH NGHIỆP đóng) trên mức lương
     * dùng làm căn cứ đóng bảo hiểm (insuranceSalary).
     */
    public static EmployerInsuranceResult calcEmployerInsurance(long insuranceSalary) {
        if (insuranceSalary <= 0) return new EmployerInsuranceResult(0, 0, 0, 0, 0);
        BigDecimal base = BigDecimal.valueOf(insuranceSalary);
        long si = round(base.multiply(EMPLOYER_SOCIAL_INSURANCE_RATE));
        long ai = round(base.multiply(EMPLOYER_ACCIDENT_INSURANCE_RATE));
        long hi = round(base.multiply(EMPLOYER_HEALTH_INSURANCE_RATE));
        long ui = round(base.multiply(EMPLOYER_UNEMPLOYMENT_INSURANCE_RATE));
        return new EmployerInsuranceResult(si, ai, hi, ui, si + ai + hi + ui);
    }

    /**
     * Tính giảm trừ gia cảnh = giảm trừ bản thân + (số người phụ thuộc x giảm trừ người phụ thuộc).
     */
    public static long calcFamilyDeduction(int dependents) {
        long dep = Math.max(0, dependents);
        return PERSONAL_DEDUCTION + dep * DEPENDENT_DEDUCTION;
    }

    /**
     * Tính thuế TNCN theo biểu thuế lũy tiến từng phần 5 bậc 2026.
     * @param taxableIncome thu nhập tính thuế (đã trừ bảo hiểm + giảm trừ gia cảnh), VNĐ/tháng
     * @return số thuế TNCN phải nộp (VNĐ), tối thiểu 0
     */
    public static long calcPersonalIncomeTax(long taxableIncome) {
        if (taxableIncome <= 0) return 0L;
        BigDecimal tnt = BigDecimal.valueOf(taxableIncome);
        for (TaxBracket b : BRACKETS) {
            if (taxableIncome <= b.upTo) {
                BigDecimal tax = tnt.multiply(b.rate).subtract(BigDecimal.valueOf(b.deduction));
                long result = round(tax);
                return Math.max(0L, result);
            }
        }
        // Không bao giờ tới đây vì bậc cuối upTo = Long.MAX_VALUE
        TaxBracket last = BRACKETS[BRACKETS.length - 1];
        return Math.max(0L, round(tnt.multiply(last.rate).subtract(BigDecimal.valueOf(last.deduction))));
    }

    /**
     * Kết quả tính NGƯỢC từ lương NET (thực nhận, chưa gồm phụ cấp/thưởng) ra
     * lương GROSS (trước bảo hiểm/thuế) + breakdown đầy đủ.
     */
    public static final class NetToGrossResult {
        public final long netSalary;          // NET đầu vào (không đổi, để đối chiếu)
        public final long grossSalary;        // GROSS suy ngược ra
        public final long socialInsuranceAmount, healthInsuranceAmount, unemploymentInsuranceAmount;
        public final long totalInsuranceAmount;
        public final long preTaxIncome;       // GROSS - bảo hiểm NLĐ đóng
        public final long personalDeduction, dependentDeduction;
        public final long taxableIncome;      // thu nhập tính thuế
        public final long personalIncomeTax;

        public NetToGrossResult(long netSalary, long grossSalary,
                                long socialInsuranceAmount, long healthInsuranceAmount, long unemploymentInsuranceAmount,
                                long totalInsuranceAmount, long preTaxIncome,
                                long personalDeduction, long dependentDeduction,
                                long taxableIncome, long personalIncomeTax) {
            this.netSalary = netSalary;
            this.grossSalary = grossSalary;
            this.socialInsuranceAmount = socialInsuranceAmount;
            this.healthInsuranceAmount = healthInsuranceAmount;
            this.unemploymentInsuranceAmount = unemploymentInsuranceAmount;
            this.totalInsuranceAmount = totalInsuranceAmount;
            this.preTaxIncome = preTaxIncome;
            this.personalDeduction = personalDeduction;
            this.dependentDeduction = dependentDeduction;
            this.taxableIncome = taxableIncome;
            this.personalIncomeTax = personalIncomeTax;
        }
    }

    /**
     * Tính NGƯỢC lương GROSS từ lương NET thực nhận (chưa gồm phụ cấp/thưởng —
     * những khoản đó cộng thẳng vào lương cuối cùng sau bước này, không đi qua
     * thuế/bảo hiểm).
     *
     * <p>Cách giải: trong mỗi bậc thuế, NET là hàm TUYẾN TÍNH theo GROSS (vì cả
     * bảo hiểm NLĐ đóng lẫn thuế TNCN đều là % cố định + hằng số trừ đi trong
     * 1 bậc) — nên có thể giải ngược bằng đại số chính xác (không cần dò số học
     * gần đúng):
     * <pre>
     *   preTaxIncome = GROSS × (1 - 10.5%)
     *   taxableIncome = preTaxIncome - giảm trừ gia cảnh
     *   thuế = taxableIncome × rate - deduction
     *   NET = preTaxIncome - thuế
     *       = GROSS × 0.895 × (1 - rate) + giảm trừ × rate + deduction
     *   ⇒ GROSS = (NET - giảm trừ × rate - deduction) / (0.895 × (1 - rate))
     * </pre>
     * Thử lần lượt từng bậc thuế (theo NET tại các điểm biên giữa các bậc) để
     * xác định đúng GROSS rơi vào bậc nào, rồi áp công thức ngược tương ứng.
     *
     * @param netSalary  lương NET thực nhận/tháng (KHÔNG gồm phụ cấp, thưởng)
     * @param dependents số người phụ thuộc
     */
    public static NetToGrossResult calcGrossFromNet(long netSalary, int dependents) {
        if (netSalary <= 0) {
            return new NetToGrossResult(netSalary, 0, 0, 0, 0, 0, 0,
                    PERSONAL_DEDUCTION, Math.max(0, dependents) * DEPENDENT_DEDUCTION, 0, 0);
        }

        long familyDeduction = calcFamilyDeduction(dependents);
        BigDecimal afterInsuranceRate = BigDecimal.ONE.subtract(TOTAL_INSURANCE_RATE); // 0.895
        BigDecimal netBD = BigDecimal.valueOf(netSalary);
        BigDecimal deductionBD = BigDecimal.valueOf(familyDeduction);

        // Bậc 0 ảo: taxableIncome <= 0 (chưa tới ngưỡng chịu thuế) — không có thuế,
        // NET = preTaxIncome = GROSS × 0.895 ⇒ GROSS = NET / 0.895.
        BigDecimal grossNoTax = netBD.divide(afterInsuranceRate, 0, RoundingMode.HALF_UP);
        long preTaxNoTax = round(grossNoTax.multiply(afterInsuranceRate));
        if (preTaxNoTax - familyDeduction <= 0) {
            long gross = grossNoTax.longValue();
            return buildNetToGrossResult(netSalary, gross, familyDeduction, dependents);
        }

        // Thử từng bậc thuế thực: với mỗi bậc, áp công thức ngược, rồi kiểm tra
        // taxableIncome suy ra có thực sự rơi đúng vào khoảng [bậc trước, bậc này]
        // hay không — nếu đúng thì đó chính là bậc cần tìm.
        long prevUpTo = 0L;
        for (TaxBracket b : BRACKETS) {
            BigDecimal oneMinusRate = BigDecimal.ONE.subtract(b.rate);
            BigDecimal denominator = afterInsuranceRate.multiply(oneMinusRate);
            BigDecimal numerator = netBD
                    .subtract(deductionBD.multiply(b.rate))
                    .subtract(BigDecimal.valueOf(b.deduction));
            BigDecimal grossBD = numerator.divide(denominator, 0, RoundingMode.HALF_UP);
            long gross = grossBD.longValue();

            long preTax = round(BigDecimal.valueOf(gross).multiply(afterInsuranceRate));
            long taxable = Math.max(0, preTax - familyDeduction);

            // Bậc hợp lệ nếu taxableIncome suy ra nằm đúng trong khoảng của bậc này
            if (taxable > prevUpTo && taxable <= b.upTo) {
                return buildNetToGrossResult(netSalary, gross, familyDeduction, dependents);
            }
            prevUpTo = b.upTo;
        }

        // Không nên tới đây (bậc cuối upTo = Long.MAX_VALUE luôn khớp) — fallback an toàn
        TaxBracket last = BRACKETS[BRACKETS.length - 1];
        BigDecimal denominator = afterInsuranceRate.multiply(BigDecimal.ONE.subtract(last.rate));
        BigDecimal numerator = netBD.subtract(deductionBD.multiply(last.rate)).subtract(BigDecimal.valueOf(last.deduction));
        long gross = numerator.divide(denominator, 0, RoundingMode.HALF_UP).longValue();
        return buildNetToGrossResult(netSalary, gross, familyDeduction, dependents);
    }

    /** Từ GROSS đã xác định, tính lại đầy đủ breakdown (bảo hiểm, thuế...) bằng công thức XUÔI để đảm bảo nhất quán. */
    private static NetToGrossResult buildNetToGrossResult(long netSalary, long gross, long familyDeduction, int dependents) {
        InsuranceResult ins = calcInsurance(gross);
        long preTaxIncome = Math.max(0, gross - ins.total);
        long taxableIncome = Math.max(0, preTaxIncome - familyDeduction);
        long pit = calcPersonalIncomeTax(taxableIncome);
        long personalDeduction = PERSONAL_DEDUCTION;
        long dependentDeduction = Math.max(0, dependents) * DEPENDENT_DEDUCTION;

        // Sai số làm tròn (nếu có) được điều chỉnh vào GROSS để NET suy xuôi khớp
        // chính xác tuyệt đối với NET đã nhập — tránh lệch 1-2đ do làm tròn nhiều bước.
        long computedNet = preTaxIncome - pit;
        long diff = netSalary - computedNet;
        if (diff != 0) {
            gross += diff;
            ins = calcInsurance(gross);
            preTaxIncome = Math.max(0, gross - ins.total);
            taxableIncome = Math.max(0, preTaxIncome - familyDeduction);
            pit = calcPersonalIncomeTax(taxableIncome);
        }

        return new NetToGrossResult(netSalary, gross,
                ins.socialInsurance, ins.healthInsurance, ins.unemploymentInsurance, ins.total,
                preTaxIncome, personalDeduction, dependentDeduction, taxableIncome, pit);
    }

    /**
     * Tính NGƯỢC lương GROSS từ lương NET thực nhận, nhưng BẢO HIỂM (phần NLĐ
     * đóng) được tính CỐ ĐỊNH trên {@code insuranceSalary} (mức lương đóng
     * BHXH/BHYT/BHTN), KHÔNG phụ thuộc vào GROSS. Còn thuế TNCN vẫn tính trên
     * thu nhập thực (GROSS − bảo hiểm − giảm trừ gia cảnh).
     *
     * <p>Đây là mô hình đúng theo yêu cầu: doanh nghiệp khai một mức lương đóng
     * bảo hiểm thấp (VD 6.000.000) nhưng trả lương thực nhận cao hơn — bảo hiểm
     * (cả NLĐ lẫn DN) tính trên mức đóng BH, còn thuế TNCN của người lao động
     * tính trên lương cuối.
     *
     * <p>Vì bảo hiểm là hằng số nên trong mỗi bậc thuế NET là hàm tuyến tính
     * theo GROSS, giải ngược chính xác bằng đại số:
     * <pre>
     *   preTax = GROSS − BH(cố định, trên insuranceSalary)
     *   taxable = max(0, preTax − giảm trừ gia cảnh)
     *   thuế   = taxable × rate − d
     *   NET    = preTax − thuế = preTax(1−rate) + giảm trừ×rate + d
     *   ⇒ preTax = (NET − giảm trừ×rate − d) / (1−rate);  GROSS = preTax + BH
     * </pre>
     *
     * @param netSalary       lương NET thực nhận/tháng (KHÔNG gồm phụ cấp, thưởng)
     * @param insuranceSalary mức lương làm căn cứ đóng BHXH/BHYT/BHTN (VNĐ)
     * @param dependents      số người phụ thuộc
     */
    public static NetToGrossResult calcGrossFromNetFixedInsurance(long netSalary, long insuranceSalary, int dependents) {
        long personalDeduction  = PERSONAL_DEDUCTION;
        long dependentDeduction  = Math.max(0, dependents) * DEPENDENT_DEDUCTION;
        long familyDeduction     = personalDeduction + dependentDeduction;

        InsuranceResult ins = calcInsurance(Math.max(0, insuranceSalary)); // BH cố định trên mức đóng BH
        long empInsTotal = ins.total;

        if (netSalary <= 0) {
            return new NetToGrossResult(netSalary, 0,
                    ins.socialInsurance, ins.healthInsurance, ins.unemploymentInsurance, empInsTotal,
                    0, personalDeduction, dependentDeduction, 0, 0);
        }

        // Trường hợp chưa tới ngưỡng chịu thuế: preTax = NET ≤ giảm trừ ⇒ thuế = 0
        if (netSalary <= familyDeduction) {
            long gross = netSalary + empInsTotal;
            return buildFixedInsResult(netSalary, gross, ins, familyDeduction, personalDeduction, dependentDeduction);
        }

        BigDecimal netBD = BigDecimal.valueOf(netSalary);
        BigDecimal famBD = BigDecimal.valueOf(familyDeduction);
        long prevUpTo = 0L;
        for (TaxBracket b : BRACKETS) {
            BigDecimal oneMinusRate = BigDecimal.ONE.subtract(b.rate);
            BigDecimal numerator = netBD
                    .subtract(famBD.multiply(b.rate))
                    .subtract(BigDecimal.valueOf(b.deduction));
            long preTax = numerator.divide(oneMinusRate, 0, RoundingMode.HALF_UP).longValue();
            long gross = preTax + empInsTotal;
            long taxable = Math.max(0, preTax - familyDeduction);
            if (taxable > prevUpTo && taxable <= b.upTo) {
                return buildFixedInsResult(netSalary, gross, ins, familyDeduction, personalDeduction, dependentDeduction);
            }
            prevUpTo = b.upTo;
        }

        // Fallback bậc cuối (upTo = Long.MAX_VALUE luôn khớp)
        TaxBracket last = BRACKETS[BRACKETS.length - 1];
        BigDecimal numerator = netBD.subtract(famBD.multiply(last.rate)).subtract(BigDecimal.valueOf(last.deduction));
        long preTax = numerator.divide(BigDecimal.ONE.subtract(last.rate), 0, RoundingMode.HALF_UP).longValue();
        long gross = preTax + empInsTotal;
        return buildFixedInsResult(netSalary, gross, ins, familyDeduction, personalDeduction, dependentDeduction);
    }

    /** Từ GROSS đã xác định (bảo hiểm cố định), tính lại breakdown xuôi + tinh chỉnh làm tròn để NET khớp tuyệt đối. */
    private static NetToGrossResult buildFixedInsResult(long netSalary, long gross, InsuranceResult ins,
                                                        long familyDeduction, long personalDeduction, long dependentDeduction) {
        long empInsTotal = ins.total;
        long preTaxIncome = Math.max(0, gross - empInsTotal);
        long taxableIncome = Math.max(0, preTaxIncome - familyDeduction);
        long pit = calcPersonalIncomeTax(taxableIncome);

        long computedNet = preTaxIncome - pit;
        long diff = netSalary - computedNet;
        if (diff != 0) {
            gross += diff;
            preTaxIncome = Math.max(0, gross - empInsTotal);
            taxableIncome = Math.max(0, preTaxIncome - familyDeduction);
            pit = calcPersonalIncomeTax(taxableIncome);
        }

        return new NetToGrossResult(netSalary, gross,
                ins.socialInsurance, ins.healthInsurance, ins.unemploymentInsurance, empInsTotal,
                preTaxIncome, personalDeduction, dependentDeduction, taxableIncome, pit);
    }

    /** Kết quả tính lương đầy đủ cho 1 nhân viên trong 1 tháng. */
    public static final class PayslipResult {
        public final long actualSalary, grossSalary;
        public final long socialInsuranceAmount, healthInsuranceAmount, unemploymentInsuranceAmount;
        public final long totalInsuranceAmount, preTaxIncome;
        public final long personalDeduction, dependentDeduction;
        public final long taxableIncome, personalIncomeTax, netSalary;

        public PayslipResult(long actualSalary, long grossSalary,
                             long socialInsuranceAmount, long healthInsuranceAmount, long unemploymentInsuranceAmount,
                             long totalInsuranceAmount, long preTaxIncome,
                             long personalDeduction, long dependentDeduction,
                             long taxableIncome, long personalIncomeTax, long netSalary) {
            this.actualSalary = actualSalary;
            this.grossSalary = grossSalary;
            this.socialInsuranceAmount = socialInsuranceAmount;
            this.healthInsuranceAmount = healthInsuranceAmount;
            this.unemploymentInsuranceAmount = unemploymentInsuranceAmount;
            this.totalInsuranceAmount = totalInsuranceAmount;
            this.preTaxIncome = preTaxIncome;
            this.personalDeduction = personalDeduction;
            this.dependentDeduction = dependentDeduction;
            this.taxableIncome = taxableIncome;
            this.personalIncomeTax = personalIncomeTax;
            this.netSalary = netSalary;
        }
    }

    /**
     * Tính toàn bộ phiếu lương 1 tháng cho 1 nhân viên.
     *
     * @param baseSalary       lương trước thuế (GROSS cơ bản, đã duyệt) VNĐ/tháng
     * @param standardWorkdays số công chuẩn trong tháng (T2-T6=1, T7=0.5)
     * @param actualWorkdays   số công thực tế đi làm
     * @param bonus            thưởng tháng này (VNĐ)
     * @param allowance        phụ cấp tháng này (VNĐ)
     * @param insuranceSalary  lương đóng BHXH/BHYT/BHTN (VNĐ) — nếu <= 0 sẽ dùng actualSalary
     * @param dependents       số người phụ thuộc
     */
    public static PayslipResult calc(long baseSalary, double standardWorkdays, double actualWorkdays,
                                     long bonus, long allowance, long insuranceSalary, int dependents) {
        // 1. Lương theo ngày công thực tế
        long actualSalary = standardWorkdays > 0
                ? round(BigDecimal.valueOf(baseSalary)
                .divide(BigDecimal.valueOf(standardWorkdays), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(actualWorkdays)))
                : baseSalary;

        // 2. Lương GROSS tháng này
        long grossSalary = actualSalary + Math.max(0, bonus) + Math.max(0, allowance);

        // 3. Bảo hiểm bắt buộc — nếu không nhập riêng, lấy theo lương thực tế đi làm
        long insBase = insuranceSalary > 0 ? insuranceSalary : actualSalary;
        InsuranceResult ins = calcInsurance(insBase);

        // 4. Thu nhập trước thuế = GROSS - bảo hiểm
        long preTaxIncome = grossSalary - ins.total;
        if (preTaxIncome < 0) preTaxIncome = 0;

        // 5. Giảm trừ gia cảnh
        long personalDeduction = PERSONAL_DEDUCTION;
        long dependentDeduction = Math.max(0, dependents) * DEPENDENT_DEDUCTION;

        // 6. Thu nhập tính thuế
        long taxableIncome = preTaxIncome - personalDeduction - dependentDeduction;
        if (taxableIncome < 0) taxableIncome = 0;

        // 7. Thuế TNCN
        long pit = calcPersonalIncomeTax(taxableIncome);

        // 8. Lương thực nhận (NET)
        long netSalary = preTaxIncome - pit;

        return new PayslipResult(actualSalary, grossSalary,
                ins.socialInsurance, ins.healthInsurance, ins.unemploymentInsurance,
                ins.total, preTaxIncome, personalDeduction, dependentDeduction,
                taxableIncome, pit, netSalary);
    }

    /**
     * Tính thưởng thực lĩnh theo tỷ lệ KPI.
     * <p>Ví dụ: thưởng nhập 6.000.000 và KPI đạt 100% ⇒ hưởng 6.000.000;
     * KPI đạt 80% ⇒ hưởng 80% × 6.000.000 = 4.800.000.
     *
     * @param bonus      thưởng gốc được nhập (VNĐ)
     * @param kpiPercent tỷ lệ KPI đạt được theo thang 100 (100.0 = 100%)
     * @return thưởng thực lĩnh (VNĐ), làm tròn về đồng gần nhất
     */
    public static long applyKpiToBonus(long bonus, double kpiPercent) {
        if (bonus <= 0 || kpiPercent <= 0) return 0L;
        return BigDecimal.valueOf(bonus)
                .multiply(BigDecimal.valueOf(kpiPercent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * Tính lương theo GIỜ công thực tế cho nhân viên hưởng lương theo giờ
     * (phòng Xưởng / ban Sản xuất).
     *
     * <p>Đơn giá 1 giờ = {@code standardSalary / STANDARD_WORK_HOURS} (208 giờ),
     * <b>KHÔNG làm tròn đơn giá</b> (giữ nhiều chữ số thập phân). Lương =
     * đơn giá × số giờ thực tế, chỉ làm tròn ở BƯỚC CUỐI về đơn vị đồng.
     *
     * <p>Ví dụ: lương chuẩn 6.000.000, công chuẩn 208 giờ ⇒ đơn giá
     * 28.846,15384615…đ/giờ; đi làm 190,5 giờ ⇒ 28.846,15384615… × 190,5 =
     * 5.495.192,3…đ ⇒ làm tròn về đồng = 5.495.192đ.
     *
     * @param standardSalary lương chuẩn/tháng khi đủ công (VNĐ)
     * @param actualHours    số giờ công thực tế trong tháng
     * @return lương theo giờ thực tế (VNĐ), làm tròn về đồng gần nhất
     */
    /**
     * ĐƠN GIÁ 1 NGÀY CÔNG = lương chuẩn / {@value #STANDARD_WORKDAYS} công,
     * LÀM TRÒN VỀ ĐỒNG ngay tại bước này.
     *
     * <p>Ví dụ: lương 8.000.000đ ⇒ 8.000.000 / 26 = 307.692,3077…
     * ⇒ đơn giá ngày = <b>307.692đ</b>.
     *
     * <p><b>Lưu ý:</b> làm tròn NGAY ở đơn giá là cố ý — bảng lương giấy của
     * xưởng ghi đơn giá ngày rồi mới nhân, nên phải làm y hệt để khớp số. Nếu
     * giữ nguyên số lẻ rồi mới nhân thì kết quả lệch vài đồng.
     */
    /**
     * SỐ NGÀY CÔNG CHUẨN CỦA MỘT THÁNG = số ngày trong tháng trừ các CHỦ NHẬT.
     *
     * <pre>
     *   Tháng 7/2026: 31 ngày − 4 chủ nhật (5, 12, 19, 26) = 27 công chuẩn
     *   Tháng 2/2026: 28 ngày − 4 chủ nhật                 = 24 công chuẩn
     * </pre>
     *
     * <p><b>Thứ Bảy tính TRÒN 1 công</b>, không phải nửa công. Doanh nghiệp làm
     * T2–T7 đủ ngày, chỉ nghỉ Chủ nhật.
     *
     * <p>Trước đây hàm này tính thứ Bảy = 0,5 nên tháng 7/2026 ra 25 công chuẩn,
     * trong khi khâu chấm công lại chấm thứ Bảy đủ ngày (tối đa 27 công). Hai vế
     * lệch nhau khiến nhân viên nghỉ hơn một ngày vẫn hiện "đủ công", và tỉ lệ
     * ngày công dùng chia thưởng KPI bị đội lên trên 100%.
     *
     * <p><b>[2026] Ngày lễ ĐÃ ĐƯỢC TRỪ</b> — trước đây chỉ tính trừ CN. Điều
     * đó khiến tháng có nghỉ lễ (VD tháng 9/2026 có 1–2/9 Quốc khánh) mọi
     * người bị công chuẩn = 26 trong khi thực tế chỉ 24 ngày làm được → bị
     * tính là nghỉ thiếu 2 công, lương pro-rata bị hụt. Đồng thời phụ cấp
     * cơm ở nhánh fallback (SALES/ACCOUNTING/WAREHOUSE) cũng dôi ra 2 ngày
     * sai luật (nghỉ lễ không đi làm thì không có bữa ăn giữa ca).
     *
     * <p>Danh sách ngày lễ do {@link VietnameseHolidays} quản lý. Hiện là hằng
     * số; sau này sẽ chuyển sang bảng {@code company_holiday} do HR upload.
     */
    public static double standardWorkdaysOf(int month, int year) {
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        double total = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            java.time.LocalDate date = java.time.LocalDate.of(year, month, d);
            if (date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) continue;
            total += 1.0;
        }
        return total;
    }

    public static double mealEligibleDaysOf(int month, int year) {
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        double total = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            java.time.LocalDate date = java.time.LocalDate.of(year, month, d);
            if (date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) continue;
            if (VietnameseHolidays.isHoliday(date)) continue;
            total += 1.0;
        }
        return total;
    }

    /** Đơn giá 1 ngày công theo số công chuẩn CỦA THÁNG ĐANG TÍNH. */
    public static long dailyRate(long standardSalary, double standardDays) {
        if (standardSalary <= 0 || standardDays <= 0) return 0L;
        return BigDecimal.valueOf(standardSalary)
                .divide(BigDecimal.valueOf(standardDays), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * LƯƠNG THEO NGÀY CÔNG THỰC TẾ, với công chuẩn của ĐÚNG tháng đang tính.
     *
     * <pre>
     *   Tháng 7/2026, công chuẩn 27, lương 8.000.000đ
     *   Đơn giá ngày = 8.000.000 / 27 = 296.296đ
     *   Làm 25,28 công ⇒ 25,28 × 296.296 = 7.490.370đ
     *   Làm đủ 27 công ⇒ trả tròn 8.000.000đ
     * </pre>
     */
    public static long prorateSalaryByDays(long standardSalary, double actualDays, double standardDays) {
        if (standardSalary <= 0 || actualDays <= 0) return 0L;
        if (standardDays <= 0) return standardSalary;

        // Đủ công (hoặc dư công) → trả tròn lương đã nhập. Nhân ngược đơn giá đã
        // làm tròn sẽ hụt vài đồng, mà nhân viên đủ công bị trừ là sai. Dư công
        // cũng chỉ tính tối đa 100% — tăng ca trả riêng.
        if (actualDays >= standardDays) return standardSalary;

        // FIX (10/2026 v2): làm tròn LÊN bội số 5.000đ gần nhất theo yêu cầu
        // mới. Trước đó làm tròn 5đ (lẻ 5.501.425); chốt lại cho đẹp, mọi phiếu
        // lương sẽ ra số tròn nghìn chẵn.
        //   Ví dụ: 6.219.000 × 21 / 26 = 5.023.038,46... → 5.025.000đ.
        //          8.500.000 × 19 / 26 = 6.211.538,46... → 6.215.000đ.
        //          6.219.000 × 23 / 26 = 5.501.423,08... → 5.505.000đ.
        //
        // KHÔNG dùng đơn giá-ngày đã HALF_UP (hụt 1-2đ sẽ tích luỹ ở kết quả
        // cuối). Chia thẳng rồi mới làm tròn 1 lần duy nhất.
        return ceilToMultipleOf5000(
                BigDecimal.valueOf(standardSalary)
                        .multiply(BigDecimal.valueOf(actualDays))
                        .divide(BigDecimal.valueOf(standardDays), 2, RoundingMode.HALF_UP)
                        .doubleValue());
    }

    /**
     * Làm tròn LÊN bội số 5.000đ gần nhất.
     * <pre>
     *   5.023.038,46 → 5.025.000
     *   6.211.538,46 → 6.215.000
     *   5.000.000    → 5.000.000  (đã là bội số)
     *   5.000.001    → 5.005.000
     * </pre>
     */
    public static long ceilToMultipleOf5000(double v) {
        if (v <= 0) return 0L;
        long c = (long) Math.ceil(v - 1e-9);
        long r = c % 5000L;
        return r == 0 ? c : c + (5000L - r);
    }

    /**
     * @deprecated giữ cho tương thích gọi cũ nếu có; chuyển sang
     *             {@link #ceilToMultipleOf5000(double)} là chính sách mới.
     */
    @Deprecated
    public static long ceilToMultipleOf5(double v) {
        if (v <= 0) return 0L;
        long c = (long) Math.ceil(v - 1e-9);
        long r = c % 5L;
        return r == 0 ? c : c + (5L - r);
    }

    public static long dailyRate(long standardSalary) {
        if (standardSalary <= 0) return 0L;
        return BigDecimal.valueOf(standardSalary)
                .divide(BigDecimal.valueOf(STANDARD_WORKDAYS), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * LƯƠNG THEO NGÀY CÔNG THỰC TẾ = đơn giá ngày × số công thực tế.
     *
     * <pre>
     *   Lương chuẩn        8.000.000đ
     *   Công chuẩn                 26 công
     *   Đơn giá 1 ngày        307.692đ      (8.000.000 / 26)
     *   Công thực tế            22,64 công
     *   ⇒ Lương thực nhận  6.966.147đ      (22,64 × 307.692)
     * </pre>
     *
     * <p>Số này là căn cứ tính THUẾ TNCN (cộng thêm phụ cấp/thưởng chịu thuế).
     * Riêng MỨC LƯƠNG ĐÓNG BẢO HIỂM thì KHÔNG đổi — vẫn là mức đã nhập trong
     * hồ sơ lương.
     *
     * @param standardSalary lương NET chuẩn khi đi làm đủ 26 công
     * @param actualDays     số công thực tế trong tháng (VD 22.64)
     */
    public static long prorateSalaryByDays(long standardSalary, double actualDays) {
        if (standardSalary <= 0 || actualDays <= 0) return 0L;

        // ĐI LÀM ĐỦ CÔNG (hoặc dư công) → TRẢ ĐÚNG LƯƠNG ĐÃ NHẬP.
        //
        //   Vì đơn giá ngày đã bị làm tròn nên nhân ngược lại sẽ KHÔNG về đúng
        //   số gốc: 8.000.000 / 26 = 307.692 → × 26 = 7.999.992đ, hụt 8đ.
        //   Nhân viên đi làm đủ công mà bị trừ vài đồng là sai, nên chặn ở đây.
        //
        //   Dư công cũng chỉ tính tối đa 100% — tăng ca đã được trả riêng,
        //   không cộng dồn vào lương cơ bản.
        if (actualDays >= STANDARD_WORKDAYS) return standardSalary;

        return BigDecimal.valueOf(dailyRate(standardSalary))
                .multiply(BigDecimal.valueOf(actualDays))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    public static long prorateSalaryByHours(long standardSalary, double actualHours) {
        if (standardSalary <= 0 || actualHours <= 0) return 0L;
        // Đơn giá giờ: giữ 12 chữ số thập phân để không mất chính xác (không làm tròn tiền ở đây).
        BigDecimal hourlyRate = BigDecimal.valueOf(standardSalary)
                .divide(BigDecimal.valueOf(STANDARD_WORK_HOURS), 12, RoundingMode.HALF_UP);
        // Lương = đơn giá × giờ thực tế, chỉ làm tròn về đồng ở bước cuối.
        return hourlyRate.multiply(BigDecimal.valueOf(actualHours))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    private static long round(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** Chi tiết thuế TNCN của MỘT bậc lũy tiến. */
    public static final class PitBracketDetail {
        public final int ratePercent;      // 5, 10, 20, 30, 35
        public final long lower, upper;     // ngưỡng dưới (loại trừ) → trên (bao gồm) của bậc
        public final long incomeInBracket;  // phần thu nhập tính thuế rơi vào bậc này
        public final long taxInBracket;      // tiền thuế của riêng bậc này
        public PitBracketDetail(int ratePercent, long lower, long upper, long incomeInBracket, long taxInBracket) {
            this.ratePercent = ratePercent;
            this.lower = lower;
            this.upper = upper;
            this.incomeInBracket = incomeInBracket;
            this.taxInBracket = taxInBracket;
        }
    }

    /**
     * Tách thuế TNCN theo TỪNG BẬC lũy tiến từng phần, trả về danh sách các bậc
     * CÓ phát sinh thu nhập tính thuế (incomeInBracket &gt; 0). Tổng taxInBracket
     * của danh sách = {@link #calcPersonalIncomeTax(long)} (chênh lệch làm tròn ≤ vài đồng).
     *
     * @param taxableIncome thu nhập tính thuế (đã trừ bảo hiểm + giảm trừ gia cảnh)
     */
    public static java.util.List<PitBracketDetail> calcPitBreakdown(long taxableIncome) {
        java.util.List<PitBracketDetail> out = new java.util.ArrayList<>();
        if (taxableIncome <= 0) return out;
        long prev = 0L;
        for (TaxBracket b : BRACKETS) {
            if (taxableIncome <= prev) break;
            long upper = b.upTo;
            long portion = Math.min(taxableIncome, upper) - prev;
            if (portion > 0) {
                int pct = b.rate.multiply(BigDecimal.valueOf(100)).intValue();
                long tax = round(BigDecimal.valueOf(portion).multiply(b.rate));
                out.add(new PitBracketDetail(pct, prev, upper, portion, tax));
            }
            prev = b.upTo;
        }
        return out;
    }
}