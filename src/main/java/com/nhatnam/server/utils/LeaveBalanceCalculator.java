package com.nhatnam.server.utils;

import java.time.LocalDate;

/**
 * QUỸ NGÀY PHÉP NĂM.
 *
 * <h3>Chính sách hiện hành</h3>
 * <pre>
 *   Cơ bản          : 12 ngày / năm
 *   Thâm niên       : mỗi ĐỦ 5 năm được thêm 1 ngày
 * </pre>
 * Tức {@code số ngày phép = 12 + (số năm thâm niên / 5)} (chia lấy phần nguyên).
 * <pre>
 *    0–4 năm → 12 ngày      15–19 năm → 15 ngày
 *    5–9 năm → 13 ngày      20–24 năm → 16 ngày
 *   10–14 năm → 14 ngày
 * </pre>
 *
 * <p>Tách khỏi {@link SeniorityCalculator} vì hai thứ đổi vì lý do khác nhau:
 * bên kia là % phụ cấp lương, bên này là ngày nghỉ. Chúng dùng chung cách ĐẾM
 * NĂM (đã kiểm kỹ ca 29/02 và quy tắc làm tròn xuống) nhưng thang quy đổi riêng.
 *
 * <p><b>Chưa chia theo tỷ lệ cho người vào làm giữa năm.</b> Vào làm tháng 10 vẫn
 * được trọn 12 ngày của năm đó. Luật lao động VN cho phép tính theo tỷ lệ tháng
 * làm việc; nếu công ty muốn áp dụng thì sửa {@link #entitledDays} — chỗ duy nhất
 * cần đụng.
 */
public final class LeaveBalanceCalculator {

    private LeaveBalanceCalculator() {}

    /** Số ngày phép cơ bản mỗi năm, chưa cộng thâm niên. */
    public static final int BASE_DAYS = 12;

    /** Cứ đủ bao nhiêu năm thâm niên thì được thêm ngày phép. */
    public static final int YEARS_PER_BONUS_DAY = 5;

    /** Mỗi mốc thâm niên được thêm mấy ngày. */
    public static final int BONUS_DAYS_PER_STEP = 1;

    // ══════════════════════════════════════════════════════════════════════════

    /**
     * SỐ NGÀY PHÉP ĐƯỢC HƯỞNG trong một năm.
     *
     * @param seniorityYears số năm thâm niên (làm tròn xuống)
     */
    public static int entitledDays(int seniorityYears) {
        if (seniorityYears < 0) seniorityYears = 0;
        return BASE_DAYS + (seniorityYears / YEARS_PER_BONUS_DAY) * BONUS_DAYS_PER_STEP;
    }

    /**
     * SỐ NGÀY PHÉP của {@code year}, suy thẳng từ ngày vào làm.
     *
     * <p>Thâm niên chốt tại NGÀY CUỐI NĂM đó — dùng một mốc cố định cho cả năm để
     * quỹ phép không tự tăng giữa chừng khi nhân viên chạm mốc 5 năm vào tháng 7.
     * Người vừa đủ 5 năm trong năm nay được hưởng 13 ngày cho TRỌN năm đó.
     *
     * @param workStartDate ngày vào làm (epoch millis); {@code null} ⇒ vẫn được
     *                      {@link #BASE_DAYS} ngày cơ bản, vì chưa khai báo ngày
     *                      vào làm là lỗi dữ liệu của công ty, không phải lý do
     *                      để cắt phép của nhân viên
     */
    public static int entitledDaysFor(Long workStartDate, int year) {
        if (workStartDate == null) return BASE_DAYS;

        long endOfYear = LocalDate.of(year, 12, 31)
                .atTime(23, 59, 59)
                .atZone(SeniorityCalculator.ZONE)
                .toInstant().toEpochMilli();

        return entitledDays(SeniorityCalculator.years(workStartDate, endOfYear));
    }

    /** Số ngày phép còn lại, không bao giờ âm khi hiển thị. */
    public static double remaining(double entitled, double used) {
        return entitled - used;
    }
}
