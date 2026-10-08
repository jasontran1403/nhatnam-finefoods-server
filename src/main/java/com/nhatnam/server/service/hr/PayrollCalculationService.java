package com.nhatnam.server.service.hr;

import com.nhatnam.server.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Set;

/**
 * CÁC HÀM TÍNH LƯƠNG DÙNG CHUNG — Phase 1 refactor (10/2026).
 *
 * <p>Service này KHÔNG đụng vào entity/repository của payroll cũ. Nó chỉ cung
 * cấp các hàm thuần (pure functions) để Phase 2 (viết lại luồng tính lương) và
 * Phase 4 (export) gọi. Mục đích là tách nghiệp vụ mới ra khỏi
 * {@code FactoryPayrollService} 3200 dòng để dễ unit test và tránh regression.
 *
 * <h3>Chuẩn công của tháng (NEW)</h3>
 * = tổng số ngày trong tháng − số CHỦ NHẬT.<br>
 * NGÀY LỄ VẪN TÍNH là chuẩn công (được nghỉ có lương).<br>
 * Ví dụ: tháng 9/2025 có 30 ngày, 4 chủ nhật → 26 chuẩn công; lễ 1/9, 2/9 vẫn
 * nằm trong 26 ngày này.
 *
 * <h3>Chuẩn công cho từng nhân viên (NEW — pro-rate theo hợp đồng)</h3>
 * Chỉ các ngày trong khoảng [workStartDate, cuối tháng] mới được tính. Lễ nằm
 * trước {@code workStartDate} KHÔNG được tính công. Ví dụ: nhân viên B bắt đầu
 * làm 9/9/2025, trong tháng 9 chỉ nhận 19 công (không có 1/9, 2/9).
 *
 * <h3>Phụ cấp cơm (NEW)</h3>
 * KHÔNG được cơm trong: ngày lễ, ngày nghỉ phép được duyệt, ngày nghỉ không
 * phép, và ngày làm nửa buổi mà thời gian làm < 360 phút (6 giờ).
 *
 * <h3>OT (NEW)</h3>
 * - Về sau 17:30 (trễ hơn 30 phút so với giờ chuẩn 17:00) → tính OT, số phút
 *   OT = (giờ ra − 17:00) tính bằng phút. Áp dụng cho các ngày THƯỜNG (T2-T7
 *   không phải lễ).<br>
 * - Chủ nhật và ngày lễ: toàn bộ thời gian có mặt (giờ ra − giờ vào) là OT,
 *   KHÔNG trừ giờ nghỉ trưa.<br>
 * - Công thức: {@code baseSalary / standardDays × minutes/480 × hệ số}, hệ số
 *   = 1.5 ngày thường, 2.0 chủ nhật, 3.0 ngày lễ. Kết quả làm tròn LÊN theo
 *   bội số 5.000đ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollCalculationService {

    private final HolidayService holidayService;

    /** Phút chuẩn trong 1 ngày công cơ bản (8 giờ × 60). */
    public static final int MINUTES_PER_WORKDAY = 480;

    /** Giờ ra chuẩn — về sau giờ này + 30 phút là bắt đầu OT. */
    public static final LocalTime STANDARD_END_TIME = LocalTime.of(17, 0);

    /** Ngưỡng kích hoạt OT ngày thường (phải về sau mốc này). */
    public static final LocalTime OT_TRIGGER_TIME = LocalTime.of(17, 30);

    /** Dưới ngưỡng này (phút) thì ngày đó không được phụ cấp cơm. */
    public static final int MEAL_MIN_MINUTES = 360;

    public static final double OT_RATE_WEEKDAY = 1.5;
    public static final double OT_RATE_SUNDAY  = 2.0;
    public static final double OT_RATE_HOLIDAY = 3.0;

    /** Đơn vị làm tròn lên cho tiền OT. */
    public static final long OT_ROUNDING_STEP = 5_000L;

    // ══════════════════════════════════════════════════════════════════════════
    // CHUẨN CÔNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Chuẩn công của tháng = số ngày trong tháng − số CHỦ NHẬT.
     * Ngày lễ vẫn tính là chuẩn công.
     */
    public int standardWorkdaysInMonth(int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        int count = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            if (ym.atDay(d).getDayOfWeek() != DayOfWeek.SUNDAY) count++;
        }
        return count;
    }

    /**
     * Chuẩn công cho 1 nhân viên trong 1 tháng — pro-rate theo
     * {@link User#getWorkStartDate()}.
     *
     * <p>Chỉ các ngày KHÔNG phải chủ nhật trong khoảng [startInMonth, cuối tháng]
     * mới tính, trong đó {@code startInMonth = max(workStartDate, ngày đầu tháng)}.
     * Nếu nhân viên bắt đầu làm sau cuối tháng (hoặc chưa có ngày bắt đầu) thì
     * trả về đúng chuẩn công của cả tháng. Nếu đã nghỉ việc trong tháng, FE
     * truyền thêm {@code endDate} (null = hết tháng).
     */
    public int standardWorkdaysForEmployee(User user, int month, int year) {
        return standardWorkdaysForEmployee(user, month, year, null);
    }

    public int standardWorkdaysForEmployee(User user, int month, int year, LocalDate endDate) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();

        LocalDate start = first;
        if (user != null && user.getWorkStartDate() != null) {
            LocalDate ws = toLocalDate(user.getWorkStartDate());
            if (ws != null && ws.isAfter(first)) start = ws;
        }
        LocalDate end = (endDate != null && endDate.isBefore(last)) ? endDate : last;

        if (start.isAfter(end)) return 0;

        int count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) count++;
        }
        return count;
    }

    /** Toàn bộ ngày lễ trong tháng (cache mỏng — mỗi lần gọi đi 1 query). */
    public Set<LocalDate> holidaysOfMonth(int month, int year) {
        return holidayService.datesOfMonth(month, year);
    }

    /**
     * Ngày lễ ÁP DỤNG cho 1 nhân viên — tức ngày lễ nằm trong hoặc sau
     * {@link User#getWorkStartDate()}. Dùng để tính "số ngày lễ được hưởng
     * lương" của người mới vào.
     */
    public Set<LocalDate> applicableHolidaysForEmployee(User user, int month, int year) {
        Set<LocalDate> all = holidaysOfMonth(month, year);
        if (user == null || user.getWorkStartDate() == null) return all;
        LocalDate ws = toLocalDate(user.getWorkStartDate());
        if (ws == null) return all;
        return all.stream().filter(d -> !d.isBefore(ws)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHỤ CẤP CƠM
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Kiểm tra ngày đó có được phụ cấp cơm hay không.
     *
     * @param date          Ngày đang xét.
     * @param isHoliday     Có phải ngày lễ không. (Caller nên gọi
     *                      {@link #holidaysOfMonth(int, int)} để truyền vào.)
     * @param isApprovedLeave Nhân viên có đơn nghỉ phép được duyệt cả ngày.
     * @param isUnexcusedAbsence Nghỉ không phép.
     * @param workedMinutes Số phút thực sự có mặt. Dùng để chặn "nửa buổi < 360'".
     */
    public boolean isMealEligible(LocalDate date,
                                  boolean isHoliday,
                                  boolean isApprovedLeave,
                                  boolean isUnexcusedAbsence,
                                  int workedMinutes) {
        if (isHoliday) return false;
        if (isApprovedLeave) return false;
        if (isUnexcusedAbsence) return false;
        if (workedMinutes < MEAL_MIN_MINUTES) return false;
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OT — PHÂN LOẠI VÀ TÍNH PHÚT
    // ══════════════════════════════════════════════════════════════════════════

    public enum DayCategory { WEEKDAY, SUNDAY, HOLIDAY }

    /** Phân loại 1 ngày để biết áp hệ số OT nào. */
    public DayCategory categorize(LocalDate date, Set<LocalDate> holidays) {
        if (holidays != null && holidays.contains(date)) return DayCategory.HOLIDAY;
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) return DayCategory.SUNDAY;
        return DayCategory.WEEKDAY;
    }

    /**
     * Số phút OT của 1 NGÀY THƯỜNG dựa trên giờ chấm công ra.
     *
     * <p>Trigger: {@code checkOut} phải sau {@link #OT_TRIGGER_TIME} (17:30).
     * Khi trigger, số phút OT = (checkOut − 17:00) tính bằng phút. Nếu
     * {@code checkOut == null} hoặc không trễ đủ 30 phút → trả 0.
     */
    public int weekdayOtMinutes(LocalTime checkOut) {
        if (checkOut == null) return 0;
        if (!checkOut.isAfter(OT_TRIGGER_TIME)) return 0;
        return (int) Duration.between(STANDARD_END_TIME, checkOut).toMinutes();
    }

    /**
     * Số phút OT của 1 NGÀY CHỦ NHẬT hoặc NGÀY LỄ = toàn bộ thời gian có mặt
     * (checkOut − checkIn), KHÔNG trừ giờ nghỉ trưa.
     */
    public int weekendOrHolidayWorkedMinutes(LocalTime checkIn, LocalTime checkOut) {
        if (checkIn == null || checkOut == null) return 0;
        if (!checkOut.isAfter(checkIn)) return 0;
        return (int) Duration.between(checkIn, checkOut).toMinutes();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OT — SỐ TIỀN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Kết quả tính OT cho 1 tháng của 1 nhân viên.
     * Các cột *Minutes là input (tổng gộp từ chấm công), các cột *Amount là
     * output (đã làm tròn theo 5.000đ).
     */
    public record OtResult(
            int weekdayMinutes, long weekdayAmount,
            int sundayMinutes,  long sundayAmount,
            int holidayMinutes, long holidayAmount,
            long totalAmount
    ) {
        public static OtResult zero() { return new OtResult(0,0,0,0,0,0,0); }
    }

    /**
     * Tính tổng tiền OT cho 1 nhân viên trong 1 tháng.
     *
     * @param baseSalary   Lương cơ bản (đồng/tháng).
     * @param standardDays Chuẩn công của tháng (dùng để chia ra giá trị 1 ngày công).
     *                     Nên là chuẩn công của CẢ CÔNG TY, không phải chuẩn công
     *                     pro-rated của nhân viên, vì mẫu số trong công thức là
     *                     mẫu số của "lương cơ bản".
     * @param weekdayMinutes Tổng phút OT các ngày thường T2-T7 (không lễ).
     * @param sundayMinutes  Tổng phút làm vào các chủ nhật.
     * @param holidayMinutes Tổng phút làm vào các ngày lễ.
     */
    public OtResult computeOtAmount(long baseSalary, int standardDays,
                                    int weekdayMinutes, int sundayMinutes, int holidayMinutes) {
        if (standardDays <= 0 || baseSalary <= 0) return OtResult.zero();

        double perDay = (double) baseSalary / standardDays;

        long weekdayAmount  = roundUpToStep(perDay * weekdayMinutes  / MINUTES_PER_WORKDAY * OT_RATE_WEEKDAY);
        long sundayAmount   = roundUpToStep(perDay * sundayMinutes   / MINUTES_PER_WORKDAY * OT_RATE_SUNDAY);
        long holidayAmount  = roundUpToStep(perDay * holidayMinutes  / MINUTES_PER_WORKDAY * OT_RATE_HOLIDAY);

        return new OtResult(
                weekdayMinutes, weekdayAmount,
                sundayMinutes,  sundayAmount,
                holidayMinutes, holidayAmount,
                weekdayAmount + sundayAmount + holidayAmount
        );
    }

    /**
     * Làm tròn LÊN theo {@link #OT_ROUNDING_STEP} = 5.000đ.
     * Ví dụ: 90.444,59 → 95.000; 298.990,38 → 300.000; 300.000 giữ nguyên.
     * Giá trị âm hoặc không dương trả về 0.
     */
    long roundUpToStep(double raw) {
        if (!(raw > 0)) return 0L;
        double stepped = Math.ceil(raw / OT_ROUNDING_STEP) * OT_ROUNDING_STEP;
        return (long) stepped;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Chuyển {@code workStartDate} của {@link User} sang {@link LocalDate}.
     *
     * <p>Trong codebase hiện tại trường này đang là {@code java.util.Date}
     * (xem User.java line 141). Reflection-free helper để Phase 1 không phải
     * đổi kiểu entity.
     */
    static LocalDate toLocalDate(Object maybeDate) {
        if (maybeDate == null) return null;
        if (maybeDate instanceof LocalDate ld) return ld;
        if (maybeDate instanceof java.util.Date d)
            return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        if (maybeDate instanceof java.sql.Date sd) return sd.toLocalDate();
        if (maybeDate instanceof Long l)
            return java.time.Instant.ofEpochMilli(l).atZone(ZoneId.systemDefault()).toLocalDate();
        return null;
    }
}
