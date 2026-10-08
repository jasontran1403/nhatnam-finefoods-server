package com.nhatnam.server.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * QUỸ NGÀY PHÉP NĂM — CỘNG DỒN THEO THÁNG.
 *
 * <h3>Chính sách từ 2026</h3>
 * <pre>
 *   Cơ bản    : 1 ngày / tháng làm việc đủ (tối đa 12 ngày/năm)
 *   Thâm niên : mỗi ĐỦ 5 năm → +1 ngày, cộng DỒN vào tháng 1 năm tiếp theo
 * </pre>
 *
 * <p>Ví dụ: tháng 9/2026, nhân viên làm từ đầu năm → 8 ngày (T1–T8 đã hoàn thành).
 * Nhân viên vào làm 13/06/2026 → tháng đầu tiên hoàn thành 13/07 → 2 ngày tính đến 07/09.
 *
 * <p>Ngày phép thâm niên KHÔNG nằm trong số cộng dồn hàng tháng mà được cộng
 * riêng (cột PLUS khi export, trường {@code bonusLeaveDays} trên entity).
 *
 * <p>Năm 2025 trở về trước: chỉ lưu số dư còn lại ({@code priorYearLeaveBalance}).
 */
public final class LeaveBalanceCalculator {

    private LeaveBalanceCalculator() {}

    /** Số ngày phép cơ bản mỗi năm (= 12 tháng × 1 ngày). */
    public static final int BASE_DAYS = 12;

    /** Cứ đủ bao nhiêu năm thâm niên thì được thêm ngày phép. */
    public static final int YEARS_PER_BONUS_DAY = 5;

    /** Mỗi mốc thâm niên được thêm mấy ngày. */
    public static final int BONUS_DAYS_PER_STEP = 1;

    /** Năm bắt đầu áp dụng cộng dồn theo tháng. Trước đó dùng priorYearLeaveBalance. */
    public static final int MONTHLY_ACCRUAL_START_YEAR = 2026;

    // ══════════════════════════════════════════════════════════════════════════

    /**
     * SỐ NGÀY PHÉP THÂM NIÊN THƯỞNG THÊM cho một năm.
     *
     * <p>Thâm niên chốt tại ngày 31/12 CỦA NĂM TRƯỚC — vì ngày phép thâm niên
     * được cộng vào tháng 1 của năm hiện tại dựa trên số năm tròn tính đến cuối
     * năm trước.
     *
     * @param workStartDate ngày vào làm (epoch millis); {@code null} ⇒ 0
     * @param year          năm cần tính
     * @return số ngày phép thâm niên (0 nếu chưa đủ 5 năm)
     */
    public static int seniorityBonusDays(Long workStartDate, int year) {
        if (workStartDate == null) return 0;

        // Chốt thâm niên tại 31/12 năm trước
        long endOfPriorYear = LocalDate.of(year - 1, 12, 31)
                .atTime(23, 59, 59)
                .atZone(SeniorityCalculator.ZONE)
                .toInstant().toEpochMilli();

        int seniorityYears = SeniorityCalculator.years(workStartDate, endOfPriorYear);
        if (seniorityYears < 0) seniorityYears = 0;
        return (seniorityYears / YEARS_PER_BONUS_DAY) * BONUS_DAYS_PER_STEP;
    }

    /**
     * SỐ NGÀY PHÉP CƠ BẢN CỘNG DỒN đến một ngày cụ thể trong năm.
     *
     * <p>Mỗi tháng ĐỦ kể từ ngày bắt đầu tính (max(1/1/year, workStartDate))
     * đến {@code asOfDate} → +1 ngày, tối đa 12.
     *
     * @param workStartDate ngày vào làm (epoch millis); {@code null} ⇒ tính từ 1/1
     * @param asOfDate      ngày chốt (thường là hôm nay)
     * @return số ngày phép cơ bản đã cộng dồn, không bao gồm thâm niên
     */
    public static int accruedBaseDays(Long workStartDate, LocalDate asOfDate) {
        int year = asOfDate.getYear();

        // Mốc bắt đầu tính: ngày đầu năm hoặc ngày vào làm (lấy cái nào muộn hơn)
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate accrualStart = yearStart;

        if (workStartDate != null) {
            LocalDate wsd = Instant.ofEpochMilli(workStartDate)
                    .atZone(SeniorityCalculator.ZONE).toLocalDate();

            if (wsd.isAfter(asOfDate)) return 0; // chưa bắt đầu làm
            if (wsd.isAfter(yearStart)) accrualStart = wsd;
        }

        // Số tháng ĐỦ từ accrualStart đến asOfDate
        long months = ChronoUnit.MONTHS.between(accrualStart, asOfDate);
        if (months < 0) months = 0;

        return (int) Math.min(months, BASE_DAYS);
    }

    /**
     * SỐ NGÀY PHÉP CƠ BẢN CỘNG DỒN tính đến NGÀY HIỆN TẠI.
     *
     * <p>Đây là hàm chính thay thế cho {@link #entitledDaysFor} cũ.
     * Chỉ trả về phần cơ bản (1 ngày/tháng), KHÔNG bao gồm thâm niên.
     *
     * @param workStartDate ngày vào làm (epoch millis)
     * @param year          năm cần tính
     * @return số ngày phép cơ bản đến hôm nay
     */
    public static int entitledDaysFor(Long workStartDate, int year) {
        LocalDate today = LocalDate.now(SeniorityCalculator.ZONE);

        if (year < today.getYear()) {
            // Năm đã qua → cộng đủ 12 tháng
            return accruedBaseDays(workStartDate, LocalDate.of(year, 12, 31));
        } else if (year > today.getYear()) {
            // Năm tương lai → chưa có ngày nào
            return 0;
        } else {
            // Năm hiện tại → tính đến hôm nay
            return accruedBaseDays(workStartDate, today);
        }
    }

    /** Số ngày phép còn lại. */
    public static double remaining(double entitled, double used) {
        return entitled - used;
    }
}