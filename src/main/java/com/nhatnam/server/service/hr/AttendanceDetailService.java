package com.nhatnam.server.service.hr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.hr.AttendanceDetailDtos.*;
import com.nhatnam.server.entity.AttendanceEntry;
import com.nhatnam.server.entity.AttendanceSheet;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.AttendanceEntryRepository;
import com.nhatnam.server.repository.AttendanceSheetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service CUNG CẤP DỮ LIỆU CHO PAGE CHUYÊN CẦN — Phase 6 (10/2026).
 *
 * <p>Đọc {@code AttendanceEntry.dailyJson} đã có sẵn (do {@code FactoryPayrollService}
 * sinh ra khi parse file chấm công). Không parse lại Excel — nhanh.
 *
 * <h3>Quy tắc tính delta</h3>
 * <ul>
 *   <li>8:00-8:05 → đúng giờ (inDelta = 0).</li>
 *   <li>8:06+ → đi trễ (inDelta = phút sau 8:00).</li>
 *   <li>7:59 và trước → đi sớm (inDelta âm = phút trước 8:00, dấu "-").</li>
 *   <li>16:55-17:00 → đúng giờ (outDelta = 0).</li>
 *   <li>16:54 và trước → về sớm (outDelta dương = phút trước 17:00).</li>
 *   <li>17:01+ → về trễ (outDelta âm = phút sau 17:00).</li>
 * </ul>
 *
 * <h3>Thứ tự nhân viên</h3>
 * Phải khớp với file lương tổng hợp — xem {@code SalaryExportService.getDeptOrder()}
 * (ACCOUNTING → FACTORY → SALES → WAREHOUSE → DRIVER). Trong mỗi phòng ban, sort
 * theo cùng rule đó (role priority rồi full name).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceDetailService {

    private final AttendanceSheetRepository sheetRepo;
    private final AttendanceEntryRepository entryRepo;
    private final HolidayService holidayService;
    private final PayrollDepartmentResolver deptResolver;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Khung giờ chuẩn — khớp với PayrollCalculationService (Phase 1). */
    private static final LocalTime SHIFT_START = LocalTime.of(8, 0);
    private static final LocalTime SHIFT_END   = LocalTime.of(17, 0);

    /** Du di: đi muộn tối đa 5 phút → vẫn tính đúng giờ. Tương tự về sớm. */
    private static final int GRACE_MINUTES = 5;

    /** Thứ tự phòng ban khớp file lương tổng hợp. */
    private static final List<PayrollDepartment> DEPT_ORDER = List.of(
            PayrollDepartment.ACCOUNTING,
            PayrollDepartment.FACTORY,
            PayrollDepartment.SALES,
            PayrollDepartment.WAREHOUSE,
            PayrollDepartment.DRIVER
    );

    public AttendanceDetailResponse getDetail(int month, int year) {
        AttendanceSheet sheet = sheetRepo.findCompanySheet(month, year).orElse(null);
        if (sheet == null) {
            // Không có bảng chấm công → trả shell rỗng để UI vẫn render chart được
            return emptyShell(month, year);
        }

        YearMonth ym = YearMonth.of(year, month);
        int daysInMonth = ym.lengthOfMonth();

        // Cờ ngày (Chủ nhật, Lễ)
        List<Boolean> sundays = new ArrayList<>(daysInMonth);
        List<Boolean> holidayFlags = new ArrayList<>(daysInMonth);
        Set<LocalDate> holidays = holidayService.datesOfMonth(month, year);
        for (int d = 1; d <= daysInMonth; d++) {
            LocalDate dt = ym.atDay(d);
            sundays.add(dt.getDayOfWeek() == DayOfWeek.SUNDAY);
            holidayFlags.add(holidays.contains(dt));
        }

        // Nạp các entry + sort theo thứ tự lương tổng hợp
        List<AttendanceEntry> entries = entryRepo.findBySheet_Id(sheet.getId());
        List<EmployeeAttendanceRow> rows = entries.stream()
                .filter(e -> e.getUser() != null)
                .map(e -> buildRow(e, daysInMonth, sundays, holidayFlags))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(EmployeeAttendanceRow::getSortOrder)
                        .thenComparing(EmployeeAttendanceRow::getFullName,
                                String.CASE_INSENSITIVE_ORDER))
                .toList();

        return AttendanceDetailResponse.builder()
                .month(month).year(year)
                .daysInMonth(daysInMonth)
                .sundays(sundays)
                .holidays(holidayFlags)
                .employees(rows)
                .build();
    }

    private AttendanceDetailResponse emptyShell(int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        int d = ym.lengthOfMonth();
        List<Boolean> sun = new ArrayList<>(d), hol = new ArrayList<>(d);
        Set<LocalDate> hols = holidayService.datesOfMonth(month, year);
        for (int i = 1; i <= d; i++) {
            LocalDate dt = ym.atDay(i);
            sun.add(dt.getDayOfWeek() == DayOfWeek.SUNDAY);
            hol.add(hols.contains(dt));
        }
        return AttendanceDetailResponse.builder()
                .month(month).year(year).daysInMonth(d)
                .sundays(sun).holidays(hol)
                .employees(List.of())
                .build();
    }

    private EmployeeAttendanceRow buildRow(AttendanceEntry e, int daysInMonth,
                                           List<Boolean> sundays, List<Boolean> holidays) {
        var user = e.getUser();
        if (user == null) return null;

        // Khởi tạo mảng ngày — mặc định không present
        Map<Integer, DayCell> byDay = new HashMap<>(daysInMonth);
        for (int d = 1; d <= daysInMonth; d++) {
            byDay.put(d, DayCell.builder()
                    .day(d)
                    .inDelta(null).outDelta(null)
                    .present(false)
                    .sunday(sundays.get(d - 1))
                    .holiday(holidays.get(d - 1))
                    .build());
        }

        int totalLate = 0, totalEarlyLeave = 0;

        // Parse dailyJson và fill vào byDay
        try {
            JsonNode arr = JSON.readTree(e.getDailyJson() == null ? "[]" : e.getDailyJson());
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    // HOTFIX (10/2026): dailyJson có 2 schema song song
                    //   - FactoryPayrollService.buildEntry (legacy): {"d": 1, ...}
                    //   - CompanyAttendanceService.serializeDaily (Phase 2 công ty):
                    //     {"date": "2025-09-01", ...}
                    // Trước đây chỉ đọc "d" nên luồng công ty (sau Phase 7 là
                    // luồng duy nhất) không ra data. Giờ thử "d" trước, fallback
                    // trích ngày từ "date" theo định dạng ISO.
                    Integer dayNo = readInt(n, "d");
                    if (dayNo == null) dayNo = readDayFromDate(n);
                    if (dayNo == null || dayNo < 1 || dayNo > daysInMonth) continue;
                    DayCell c = byDay.get(dayNo);
                    LocalTime in = readTime(n, "in");
                    LocalTime out = readTime(n, "out");
                    boolean present = in != null || out != null;
                    boolean defaultedOut = n.hasNonNull("defaultedOut") && n.get("defaultedOut").asBoolean();
                    boolean defaultedIn  = n.hasNonNull("defaultedIn")  && n.get("defaultedIn").asBoolean();
                    boolean isLeave = "LEAVE".equalsIgnoreCase(readStr(n, "t"));
                    c.setPresent(present);
                    c.setDefaultedOut(defaultedOut);
                    c.setDefaultedIn(defaultedIn);
                    c.setLeave(isLeave);
                    if (in != null)  c.setInDelta(computeInDelta(in));
                    if (out != null) c.setOutDelta(computeOutDelta(out));

                    if (c.getInDelta() != null && c.getInDelta() > 0) totalLate += c.getInDelta();
                    if (c.getOutDelta() != null && c.getOutDelta() > 0) totalEarlyLeave += c.getOutDelta();
                }
            }
        } catch (Exception ex) {
            log.warn("[AttendanceDetail] Parse dailyJson lỗi cho user {}: {}", user.getId(), ex.getMessage());
        }

        List<DayCell> days = new ArrayList<>(daysInMonth);
        for (int d = 1; d <= daysInMonth; d++) days.add(byDay.get(d));

        PayrollDepartment dept = deptResolver.departmentOf(user);
        return EmployeeAttendanceRow.builder()
                .userId(user.getId())
                .fullName(user.getFullName())
                .roleLabel(deptResolver.roleLabelOf(user))
                .department(dept != null ? dept.name() : "OTHER")
                .sortOrder(computeSortOrder(dept, user))
                .days(days)
                .totalLateMinutes(totalLate)
                .totalEarlyLeaveMinutes(totalEarlyLeave)
                .build();
    }

    /**
     * Delta phút cho giờ VÀO: >0 = trễ, <0 = sớm, =0 = đúng giờ (có du di 5').
     * <br>8:00-8:05 → 0; 8:06 → 6; 7:59 → -1.
     */
    static int computeInDelta(LocalTime in) {
        int delta = (in.getHour() - SHIFT_START.getHour()) * 60
                + (in.getMinute() - SHIFT_START.getMinute());
        if (delta > 0 && delta <= GRACE_MINUTES) return 0;      // đúng giờ (du di)
        return delta;
    }

    /**
     * Delta phút cho giờ RA: >0 = về sớm, <0 = về trễ, =0 = đúng giờ.
     * <br>16:55-17:00 → 0; 16:54 → 6; 17:01 → -1.
     */
    static int computeOutDelta(LocalTime out) {
        int delta = (SHIFT_END.getHour() - out.getHour()) * 60
                + (SHIFT_END.getMinute() - out.getMinute());
        if (delta > 0 && delta <= GRACE_MINUTES) return 0;      // đúng giờ (du di)
        return delta;
    }

    /**
     * Thứ tự sort: index trong {@link #DEPT_ORDER} × 10000 + role priority
     * (role đứng đầu trong {@link PayrollDepartment#getRoles} được 0, kế tiếp
     * 1…). Nhân viên OWNER/ADMIN hoặc role lạ → cuối cùng.
     */
    private int computeSortOrder(PayrollDepartment dept, com.nhatnam.server.entity.User user) {
        if (dept == null) return 99_999;
        int deptIdx = DEPT_ORDER.indexOf(dept);
        if (deptIdx < 0) deptIdx = DEPT_ORDER.size();
        Role r = PayrollDepartment.resolvePayrollRole(user.getRoles());
        int rolePri = r == null ? 999 : dept.getRoles().indexOf(r);
        if (rolePri < 0) rolePri = 999;
        return deptIdx * 10_000 + rolePri;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static Integer readInt(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asInt();
    }
    private static String readStr(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }
    private static LocalTime readTime(JsonNode n, String f) {
        String s = readStr(n, f);
        if (s == null || s.isBlank()) return null;
        try { return LocalTime.parse(s); } catch (Exception e) { return null; }
    }

    /**
     * HOTFIX (10/2026): Trích day-of-month từ field {@code "date"} dạng
     * ISO {@code yyyy-MM-dd}. Dùng khi JSON do CompanyAttendanceService sinh
     * ra không có field {@code "d"}. Trả null nếu không parse được.
     */
    private static Integer readDayFromDate(JsonNode n) {
        String s = readStr(n, "date");
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s).getDayOfMonth(); }
        catch (Exception e) { return null; }
    }
}