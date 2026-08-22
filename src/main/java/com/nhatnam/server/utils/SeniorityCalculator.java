package com.nhatnam.server.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * THÂM NIÊN &amp; PHỤ CẤP THÂM NIÊN.
 *
 * <p>Hai phép tính tách rời nhau có chủ đích:
 * <ol>
 *   <li>{@link #years(Long, Long)} — số năm TRÒN từ ngày vào làm đến ngày chốt.</li>
 *   <li>{@link #percentOf(int)} — quy đổi số năm ra % phụ cấp theo chính sách.</li>
 * </ol>
 *
 * <p>Tách ra vì hai thứ này đổi vì lý do khác nhau: cách đếm năm là quy tắc lịch
 * (gần như không bao giờ đổi), còn thang % là CHÍNH SÁCH công ty (rất dễ đổi).
 * Muốn sửa mức phụ cấp chỉ cần đụng vào {@link #percentOf(int)}.
 *
 * <h3>Chính sách hiện hành</h3>
 * <pre>
 *   dưới 1 năm : 0%
 *   đủ 1 năm   : 2%   ← mốc bắt đầu được hưởng
 *   mỗi năm sau: +1%
 *   trần       : 10%
 * </pre>
 * Nói gọn: {@code % = min(số năm + 1, 10)}, và bằng 0 khi chưa đủ 1 năm.
 * <pre>
 *   0 năm →  0%      5 năm →  6%      9 năm → 10%
 *   1 năm →  2%      6 năm →  7%     12 năm → 10%  (đã chạm trần)
 *   2 năm →  3%      7 năm →  8%
 * </pre>
 *
 * <p>Toàn bộ hàm đều {@code static} và thuần tuý — không đụng DB, không phụ
 * thuộc Spring context — nên gọi được từ mọi tầng và kiểm thử bằng tay dễ dàng.
 */
public final class SeniorityCalculator {

    private SeniorityCalculator() {}

    /** Múi giờ chuẩn của công ty — mọi mốc ngày đều quy về đây trước khi so sánh. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Số năm tối thiểu để BẮT ĐẦU được hưởng phụ cấp thâm niên. */
    public static final int MIN_YEARS_FOR_ALLOWANCE = 1;

    /** % được hưởng ngay khi vừa đủ {@link #MIN_YEARS_FOR_ALLOWANCE} năm. */
    public static final int STARTING_PERCENT = 2;

    /** Mỗi năm thâm niên tăng thêm bao nhiêu %. */
    public static final int PERCENT_STEP_PER_YEAR = 1;

    /** TRẦN phụ cấp thâm niên — không vượt quá dù làm bao nhiêu năm. */
    public static final int MAX_PERCENT = 10;

    /** Nhãn hiển thị trên phiếu lương. */
    public static final String ALLOWANCE_LABEL = "Phụ cấp thâm niên";

    // ══════════════════════════════════════════════════════════════════════════
    //  1) SỐ NĂM THÂM NIÊN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * SỐ NĂM THÂM NIÊN TRÒN từ ngày vào làm đến ngày chốt.
     *
     * <h3>LUÔN LÀM TRÒN XUỐNG — không bao giờ tròn lên</h3>
     * Thiếu một ngày cũng là chưa đủ năm:
     * <pre>
     *   0,99 năm → 0    (chưa đủ ngày)
     *   1,99 năm → 1    (chưa đủ 2 năm)
     * </pre>
     *
     * <h3>Mốc là NGÀY CUỐI CỦA KỲ LƯƠNG, không phải ngày bấm Hoàn tất</h3>
     * Lương chốt ngày 1 đầu tháng sau nhưng tính cho THÁNG TRƯỚC — xem
     * {@code HrService.payrollReferenceDate}. Lấy ngày bấm nút sẽ cộng dôi thâm
     * niên của những ngày ngoài kỳ đang trả.
     *
     * <h3>Ngày 29/02</h3>
     * So sánh trên LỊCH ({@link ChronoUnit#YEARS}) chứ không lấy hiệu millis chia
     * 365 — cách chia đó trượt một ngày sau mỗi bốn năm. {@code ChronoUnit} đếm
     * theo tháng tròn nên người vào làm 29/02 phải sang 01/03 của năm không nhuận
     * mới tính là đủ năm:
     * <pre>
     *   vào 29/02/2024 → 28/02/2025 = 0 năm   (chưa đủ)
     *   vào 29/02/2024 → 01/03/2025 = 1 năm
     *   vào 29/02/2024 → 28/02/2026 = 1 năm   ← kỳ lương T2/2026
     *   vào 29/02/2024 → 31/03/2026 = 2 năm   ← kỳ lương T3/2026
     * </pre>
     *
     * @param workStartDate ngày vào làm (epoch millis); {@code null} ⇒ 0 năm
     * @param referenceDate ngày chốt (epoch millis) — NGÀY CUỐI của kỳ lương;
     *                      {@code null} ⇒ 0 năm
     * @return số năm tròn, luôn {@code >= 0} (ngày vào làm ở tương lai ⇒ 0)
     */
    public static int years(Long workStartDate, Long referenceDate) {
        if (workStartDate == null || referenceDate == null) return 0;

        LocalDate start = toLocalDate(workStartDate);
        LocalDate ref   = toLocalDate(referenceDate);

        if (ref.isBefore(start)) return 0;   // nhập nhầm ngày vào làm ở tương lai

        long y = ChronoUnit.YEARS.between(start, ref);
        return y < 0 ? 0 : (int) y;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  2) % PHỤ CẤP THEO SỐ NĂM
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * % PHỤ CẤP THÂM NIÊN ứng với số năm đã làm.
     *
     * @param years số năm tròn (lấy từ {@link #years(Long, Long)})
     * @return 0 nếu chưa đủ 1 năm; ngược lại {@code min(years + 1, 10)}
     */
    public static int percentOf(int years) {
        if (years < MIN_YEARS_FOR_ALLOWANCE) return 0;

        // Năm đầu tiên được hưởng đã là STARTING_PERCENT, mỗi năm dôi ra cộng thêm
        // PERCENT_STEP_PER_YEAR → 1 năm = 2%, 2 năm = 3%, 6 năm = 7%.
        int percent = STARTING_PERCENT
                + (years - MIN_YEARS_FOR_ALLOWANCE) * PERCENT_STEP_PER_YEAR;

        return Math.min(percent, MAX_PERCENT);
    }

    /** Gộp 2 bước: từ ngày vào làm + ngày chốt ra thẳng % phụ cấp. */
    public static int percentOf(Long workStartDate, Long referenceDate) {
        return percentOf(years(workStartDate, referenceDate));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  3) SỐ TIỀN PHỤ CẤP
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TIỀN PHỤ CẤP THÂM NIÊN = lương cơ bản × %.
     *
     * <p>Lấy LƯƠNG CƠ BẢN CHUẨN (mức đủ công trong hồ sơ), KHÔNG lấy lương đã
     * chia theo ngày công: thâm niên là quyền lợi gắn với thời gian gắn bó, không
     * phải với số ngày đi làm trong tháng. Công nhân xưởng nghỉ vài ngày vẫn giữ
     * nguyên mức phụ cấp thâm niên.
     *
     * <p>Làm tròn HALF_UP về hàng đồng — 2% của 8.150.000đ là 163.000đ chẵn, còn
     * các mức lương lẻ sẽ ra số nguyên thay vì phần thập phân.
     *
     * @param baseSalary lương cơ bản chuẩn (VNĐ/tháng)
     * @param percent    % phụ cấp lấy từ {@link #percentOf(int)}
     */
    public static long allowance(long baseSalary, int percent) {
        if (baseSalary <= 0 || percent <= 0) return 0L;

        return BigDecimal.valueOf(baseSalary)
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /** Gộp cả 3 bước — dùng khi chỉ cần con số cuối cùng. */
    public static long allowance(long baseSalary, Long workStartDate, Long referenceDate) {
        return allowance(baseSalary, percentOf(workStartDate, referenceDate));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Tiện ích
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Nhãn đầy đủ cho dòng phụ cấp trên phiếu lương.
     * VD: {@code "Phụ cấp thâm niên (6 năm — 7%)"}
     */
    public static String labelFor(int years, int percent) {
        return "%s (%d năm — %d%%)".formatted(ALLOWANCE_LABEL, years, percent);
    }

    /** Nhận diện dòng phụ cấp thâm niên đã có sẵn trong hồ sơ (tránh cộng 2 lần). */
    public static boolean isSeniorityAllowance(String label) {
        if (label == null) return false;
        String s = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        return s.contains("tham nien");
    }

    private static LocalDate toLocalDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate();
    }
}
