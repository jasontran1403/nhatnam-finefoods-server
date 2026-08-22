package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.*;
import com.nhatnam.server.dto.hr.HrDtos.AllowanceItemDto;
import com.nhatnam.server.dto.hr.HrDtos.SalaryBreakdownDto;
import com.nhatnam.server.dto.hr.HrDtos.SalaryRequest;
import com.nhatnam.server.utils.PayrollTaxCalculator;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.attendance.AttendanceExcelParser;
import com.nhatnam.server.service.attendance.AttendanceExcelParser.DayRecord;
import com.nhatnam.server.service.attendance.AttendanceExcelParser.EmployeeBlock;
import com.nhatnam.server.service.attendance.AttendanceExceptionParser;
import com.nhatnam.server.service.hr.EmployeeRequestService;
import com.nhatnam.server.service.hr.HrService;
import com.nhatnam.server.service.hr.PayrollDepartmentResolver;
import com.nhatnam.server.utils.SeniorityCalculator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MODULE "QUẢN LÝ LƯƠNG" — dùng chung cho MỌI BỘ PHẬN.
 *
 * <h3>Luồng nghiệp vụ</h3>
 * <ol>
 *   <li><b>OWNER upload theo BỘ PHẬN</b> — mỗi tháng × bộ phận có bộ 3 file
 *       riêng (bảng chấm công · lịch nghỉ · đơn xin nghỉ). Bộ phận Tài xế không
 *       có file nào vì tính lương theo số km chạy.</li>
 *   <li><b>OWNER bấm "Hoàn tất"</b> cho tháng + bộ phận. Chỉ khi đó nhân viên
 *       mới nhìn thấy phiếu lương; chưa hoàn tất thì màn hình nhân viên hiện
 *       "Đang xử lý lương".</li>
 *   <li>Hoàn tất rồi vẫn XOÁ được file để tải file khác — mỗi lần đổi file thì
 *       cờ hoàn tất tự gỡ, bấm "Hoàn tất" lại sẽ tính theo file MỚI NHẤT.</li>
 * </ol>
 *
 * <h3>Nhân viên kiêm nhiệm</h3>
 * Chỉ lấy ROLE NHẬN LƯƠNG ({@link PayrollDepartmentResolver}) nên một người
 * không bao giờ xuất hiện ở 2 bộ phận. VD Trần Mộng Thuỳ (SELLER + WAREHOUSE)
 * chỉ nằm ở bộ phận Kinh doanh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryPayrollService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** Số tháng quá khứ hiển thị trong dropdown chọn tháng. */
    private static final int MONTHS_LOOKBACK = 24;

    /** Tiền tố nhận diện role thuộc XƯỞNG. */
    public static final String FACTORY_ROLE_PREFIX = "FACTORY_";

    // ══════════════════════════════════════════════════════════════════════════
    // CA LÀM VIỆC CHUẨN — mọi phép tính công / trễ / sớm đều dựa vào đây
    // ══════════════════════════════════════════════════════════════════════════

    public static final LocalTime SHIFT_START = LocalTime.of(8, 0);
    public static final LocalTime SHIFT_END = LocalTime.of(17, 0);

    public static final int STANDARD_SHIFT_MINUTES =
            (int) java.time.Duration.between(SHIFT_START, SHIFT_END).toMinutes();

    /** GIỜ NGHỈ TRƯA cố định 12:00–13:00, KHÔNG tính là thời gian làm việc. */
    public static final LocalTime LUNCH_START = LocalTime.of(12, 0);
    public static final LocalTime LUNCH_END   = LocalTime.of(13, 0);

    /** Số phút PHẢI làm trong 1 ngày chuẩn = 540 − 60 = 480 phút. */
    public static final int STANDARD_WORK_MINUTES = STANDARD_SHIFT_MINUTES
            - (int) java.time.Duration.between(LUNCH_START, LUNCH_END).toMinutes();

    public static final boolean FULL_DAY_IF_PUNCHED = true;

    @Value("${app.attendance.upload-dir:~/Desktop/nhatnam-finefoods-storage/attendance}")
    private String attendanceDir;

    private Path attendanceRoot;

    private final AttendanceSheetRepository sheetRepo;
    private final AttendanceEntryRepository entryRepo;
    private final EmployeeSalaryRepository salaryRepo;
    private final UserRepository userRepo;
    private final AttendanceExceptionRepository exceptionRepo;
    private final AttendanceLeaveRequestRepository leaveRepo;
    private final FactoryKpiService kpiService;
    private final PayrollDepartmentResolver deptResolver;
    private final DriverKmService driverKmService;
    private final HrService hrService;
    /** Bản CHỐT thâm niên của kỳ — ghi khi OWNER bấm "Hoàn tất". */
    private final EmployeeSeniorityRepository seniorityRepo;
    /** Nguồn sự thật cho ưu đãi từ ĐƠN NHÂN VIÊN đã được OWNER duyệt. */
    private final com.nhatnam.server.service.hr.EmployeeRequestService employeeRequestService;

    @PostConstruct
    void initStorage() {
        attendanceRoot = resolvePath(attendanceDir);
        try {
            Files.createDirectories(attendanceRoot);
            log.info("[Attendance] Thư mục lưu bảng chấm công: {}", attendanceRoot);
        } catch (IOException e) {
            log.error("[Attendance] KHÔNG tạo được thư mục lưu trữ: {} — {}. "
                            + "File gốc sẽ không được lưu, dữ liệu chấm công vẫn vào DB bình thường.",
                    attendanceRoot, e.getMessage());
        }
    }

    static Path resolvePath(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) v = "uploads/attendance";
        v = v.replace('\\', '/');

        Path result;
        String home = System.getProperty("user.home");

        if (v.equals("~")) {
            result = Paths.get(home);
        } else if (v.startsWith("~/")) {
            result = Paths.get(home).resolve(v.substring(2));
        } else {
            result = Paths.get(v);
        }
        result = result.toAbsolutePath().normalize();

        return redirectToOneDriveIfNeeded(result, home);
    }

    private static Path redirectToOneDriveIfNeeded(Path path, String home) {
        if (home == null) return path;

        Path plainDesktop = Paths.get(home).resolve("Desktop");
        if (!path.startsWith(plainDesktop) || Files.isDirectory(plainDesktop)) return path;

        Path oneDriveDesktop = Paths.get(home).resolve("OneDrive").resolve("Desktop");
        if (!Files.isDirectory(oneDriveDesktop)) return path;

        Path moved = oneDriveDesktop.resolve(plainDesktop.relativize(path));
        log.warn("[Attendance] Không thấy {} nhưng có Desktop của OneDrive — dùng {} thay thế.",
                plainDesktop, moved);
        return moved;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. DANH SÁCH THÁNG CÓ THỂ CHỌN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Danh sách tháng cho dropdown của NHÂN VIÊN — CHỈ các THÁNG ĐÃ QUA.
     * Cờ {@code finalized} cho biết tháng đó OWNER đã chốt lương chưa.
     */
    public List<PeriodOptionDto> availablePeriods(PayrollDepartment department) {
        YearMonth current = YearMonth.now(VN);
        List<PeriodOptionDto> result = new ArrayList<>();

        for (int i = 1; i <= MONTHS_LOOKBACK; i++) {
            YearMonth ym = current.minusMonths(i);
            AttendanceSheet s = department == null ? null
                    : sheetRepo.findByMonthAndYearAndDepartment(
                    ym.getMonthValue(), ym.getYear(), department).orElse(null);

            result.add(PeriodOptionDto.builder()
                    .month(ym.getMonthValue())
                    .year(ym.getYear())
                    .label(periodLabel(ym.getMonthValue(), ym.getYear()))
                    .attendanceReady(s != null && s.getFilePath() != null)
                    .finalized(s != null && s.isFinalized())
                    .build());
        }
        return result;
    }

    /**
     * Các tháng được phép thao tác trên trang Bảng chấm công của OWNER:
     * tháng hiện tại và các tháng đã qua, KHÔNG có tháng tương lai.
     */
    public List<PeriodOptionDto> uploadablePeriods(PayrollDepartment department) {
        YearMonth current = YearMonth.now(VN);
        List<PeriodOptionDto> out = new ArrayList<>();
        for (int i = 0; i <= MONTHS_LOOKBACK; i++) {
            YearMonth ym = current.minusMonths(i);
            AttendanceSheet s = department == null ? null
                    : sheetRepo.findByMonthAndYearAndDepartment(
                    ym.getMonthValue(), ym.getYear(), department).orElse(null);

            out.add(PeriodOptionDto.builder()
                    .month(ym.getMonthValue()).year(ym.getYear())
                    .label(periodLabel(ym.getMonthValue(), ym.getYear()))
                    .attendanceReady(s != null && s.getFilePath() != null)
                    .finalized(s != null && s.isFinalized())
                    .build());
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. PHIẾU LƯƠNG CỦA NHÂN VIÊN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Phiếu lương tháng {@code month}/{@code year} của nhân viên {@code user}.
     *
     * <p>Trả {@code status = PROCESSING} khi OWNER CHƯA BẤM HOÀN TẤT cho tháng +
     * bộ phận của nhân viên → FE hiển thị "Đang xử lý lương".
     */
    @Transactional
    public MyPayslipDto getMyPayslip(User user, int month, int year) {
        validatePastPeriod(month, year);

        String label = periodLabel(month, year);
        Role payrollRole = deptResolver.payrollRoleOf(user);
        PayrollDepartment dept = PayrollDepartment.of(payrollRole);

        MyPayslipDto.MyPayslipDtoBuilder b = MyPayslipDto.builder()
                .month(month).year(year).periodLabel(label)
                .userId(user.getId())
                .userFullName(user.getFullName())
                .department(user.getDepartment())
                .division(user.getDivision())
                .position(user.getPosition())
                .payrollRole(payrollRole != null ? payrollRole.name() : null)
                .payrollDepartment(dept != null ? dept.name() : null)
                .payrollDepartmentLabel(dept != null ? dept.getLabel() : null)
                .hasKpiBonus(dept != null && dept.isKpiBonus())
                .attendanceBased(dept != null && dept.isAttendanceBased())
                .roleLabel(deptResolver.roleLabelOf(user));

        // ── Không thuộc bộ phận tính lương nào (OWNER/ADMIN/HR…) ─────────────
        if (dept == null) {
            return b.status("NO_DEPARTMENT").build();
        }

        // ── CHƯA HOÀN TẤT → Đang xử lý lương ─────────────────────────────────
        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, dept).orElse(null);

        if (sheet == null || !sheet.isFinalized()) {
            return b.status("PROCESSING").build();
        }

        b.attendanceUploadedAt(sheet.getUploadedAt())
                .finalizedAt(sheet.getFinalizedAt());

        // ── Bộ phận Tài xế: không có chấm công, thay bằng km theo ngày ────────
        AttendanceSummaryDto attendance = null;
        if (!dept.isAttendanceBased()) {
            DriverMonthDto dm = driverKmService.monthOf(user, month, year);
            fillDriverSalary(dm, sheet, true);
            return b.driver(dm)
                    .status("READY")
                    .totalPay(dm.getTotalSalary() != null ? dm.getTotalSalary() : 0L)
                    .build();
        }
        if (dept.isAttendanceBased()) {
            double defaultStd = sheet.getStandardDays() != null
                    ? sheet.getStandardDays()
                    : standardWorkdaysOf(month, year);

            AttendanceEntry entry = entryRepo.findByUserAndPeriod(user.getId(), month, year).orElse(null);
            attendance = buildAttendanceSummary(entry, month, year, defaultStd);
            b.attendance(attendance);
        }

        // ── Thưởng KPI sản xuất: CHỈ bộ phận Xưởng ───────────────────────────
        long kpiAmount = 0L;
        if (dept.isKpiBonus()) {
            FactoryKpiBonus kpi = kpiService.getOrCompute(month, year);
            FactoryKpiBonusItem myItem = kpi.getItems().stream()
                    .filter(i -> i.getUser() != null && i.getUser().getId() == user.getId())
                    .findFirst().orElse(null);

            kpiAmount = myItem != null ? nz(myItem.getAmount()) : 0L;
            b.kpi(buildKpiDto(kpi, myItem));
        }
        b.kpiBonus(kpiAmount);

        // ── CHI TIẾT LƯƠNG — đúng bộ số liệu OWNER nhìn thấy ─────────────────
        SalaryBreakdownDto detail = hrService.getSalaryBreakdownForUser(user.getId(), month, year);
        b.salaryDetail(detail);

        // ── Tóm tắt lương theo hồ sơ (giữ tương thích màn hình cũ) ───────────
        EmployeeSalary salary = salaryRepo.findApprovedByUserId(user.getId())
                .stream().findFirst().orElse(null);

        if (salary == null) {
            return b.status(detail != null ? "READY" : "NO_SALARY")
                    .baseSalary(detail != null ? nz(detail.getBaseSalary()) : 0L)
                    .salaryByAttendance(detail != null ? nz(detail.getBaseSalary()) : 0L)
                    .allowance(detail != null ? nz(detail.getAllowance()) : 0L)
                    .fixedBonus(detail != null ? nz(detail.getEffectiveBonus()) : 0L)
                    .totalPay(detail != null ? nz(detail.getNetSalary()) + kpiAmount : kpiAmount)
                    .build();
        }

        long base = nz(salary.getBaseSalary());
        long allowance = nz(salary.getAllowance());
        long fixedBonus = nz(salary.getBonus());

        // ── LƯƠNG THEO NGÀY CÔNG ─────────────────────────────────────────────
        //   Ưu tiên lấy thẳng số đã tính trong breakdown (chia theo CÔNG CHUẨN
        //   26 công/tháng — xem HrService#computeBreakdown), vì đó cũng chính là
        //   số dùng để tính thuế TNCN nên hai màn hình luôn khớp nhau.
        long byAttendance = base;
        if (detail != null && Boolean.TRUE.equals(detail.getAttendanceProrated())) {
            byAttendance = nz(detail.getBaseSalary());
        } else if (attendance != null) {
            // Bộ phận không chia theo 26 công → giữ cách cũ: theo công chuẩn của tháng
            double std = nz(attendance.getStandardDays());
            double act = nz(attendance.getActualDays());
            if (std > 0) {
                byAttendance = BigDecimal.valueOf(base)
                        .divide(BigDecimal.valueOf(std), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(act))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue();
            }
        }

        return b.status("READY")
                .baseSalary(base)
                .salaryByAttendance(byAttendance)
                .allowance(allowance)
                .fixedBonus(fixedBonus)
                .totalPay(byAttendance + allowance + fixedBonus + kpiAmount)
                .build();
    }

    private KpiBonusDto buildKpiDto(FactoryKpiBonus kpi, FactoryKpiBonusItem myItem) {
        return KpiBonusDto.builder()
                .totalOutputKg(kpi.getTotalOutputKg())
                .totalOutputTon(kpi.getTotalOutputTon())
                .ratePerTon(kpi.getRatePerTon())
                .bonusPool(kpi.getBonusPool())
                .carryOverIn(kpi.getCarryOverIn())
                .carryOverOut(kpi.getCarryOverOut())
                .securityTotal(kpi.getSecurityTotal())
                .totalWeight(kpi.getTotalWeight())
                .carryOverInDetail(
                        kpiService.carryOverInDetail(kpi.getMonth(), kpi.getYear()).stream()
                                .map(e -> CarryOverEntryDto.builder()
                                        .month(e.getMonth())
                                        .year(e.getYear())
                                        .amount(e.getAmount())
                                        .label("T%d/%d".formatted(e.getMonth(), e.getYear()))
                                        .build())
                                .toList())
                .myRoleLabel(myItem != null ? myItem.getRoleLabel() : null)
                .myWeight(myItem != null && !Boolean.TRUE.equals(myItem.getFixedAmountRole())
                        ? myItem.getWeight() : null)
                .myFixedRole(myItem != null && Boolean.TRUE.equals(myItem.getFixedAmountRole()))
                .myRawAmount(myItem != null ? nz(myItem.getRawAmount()) : 0L)
                .myAmount(myItem != null ? nz(myItem.getAmount()) : 0L)
                .build();
    }

    /**
     * Dựng breakdown ngày công từ {@code dailyJson}.
     * Nhân viên không có trong file chấm công → trả lịch tháng với mọi ngày = 0.
     */
    private AttendanceSummaryDto buildAttendanceSummary(AttendanceEntry entry,
                                                        int month, int year,
                                                        double defaultStandard) {
        YearMonth ym = YearMonth.of(year, month);
        int len = ym.lengthOfMonth();

        Map<Integer, Map<String, Object>> daily = new HashMap<>();
        if (entry != null && entry.getDailyJson() != null && !entry.getDailyJson().isBlank()) {
            try {
                List<Map<String, Object>> raw = MAPPER.readValue(
                        entry.getDailyJson(), new TypeReference<>() {});
                for (Map<String, Object> r : raw) {
                    Object d = r.get("d");
                    if (d instanceof Number n) daily.put(n.intValue(), r);
                }
            } catch (Exception e) {
                log.warn("[Attendance] dailyJson lỗi ở entry {}: {}", entry.getId(), e.getMessage());
            }
        }

        List<AttendanceDayDto> days = new ArrayList<>(len);
        for (int d = 1; d <= len; d++) {
            LocalDate date = LocalDate.of(year, month, d);
            int weekday = date.getDayOfWeek() == DayOfWeek.SUNDAY ? 8
                    : date.getDayOfWeek().getValue() + 1;   // T2=2 … T7=7, CN=8

            Map<String, Object> r = daily.get(d);

            AttendanceDayDto.AttendanceDayDtoBuilder db = AttendanceDayDto.builder()
                    .day(d).weekday(weekday)
                    .weekdayLabel(r != null && r.get("w") != null
                            ? str(r.get("w")) : defaultWeekdayLabel(weekday))
                    .value(0.0).type("OFF");

            if (r != null) {
                double value = r.get("v") instanceof Number n ? n.doubleValue() : 0.0;
                String type = str(r.get("t"));
                if (type == null || type.isBlank())
                    type = value >= 1 ? "WORK" : value > 0 ? "HALF" : "OFF";

                db.value(value).type(type)
                        .checkIn(str(r.get("in")))
                        .checkOut(str(r.get("out")))
                        .lateMinutes(r.get("late")  instanceof Number n2 ? n2.intValue() : null)
                        .earlyMinutes(r.get("early") instanceof Number n3 ? n3.intValue() : null)
                        .workedMinutes(r.get("wk")   instanceof Number n4 ? n4.intValue() : null)
                        .requiredMinutes(r.get("rq") instanceof Number n5 ? n5.intValue() : null)
                        .exception(str(r.get("ex")))
                        .windowStart(str(r.get("ws")))
                        .windowEnd(str(r.get("we")))
                        .sessions(readSessions(r.get("ss")));
            }

            days.add(db.build());
        }

        return AttendanceSummaryDto.builder()
                // CÔNG CHUẨN LUÔN TÍNH LẠI TỪ LỊCH THÁNG, không đọc giá trị đã lưu.
                //
                //   Các bản ghi import trước khi sửa lỗi "thứ Bảy = nửa công" đang
                //   giữ số sai (T7/2026 lưu 25 thay vì 27). Tính lại lúc đọc thì mọi
                //   tháng cũ tự đúng mà không phải import lại toàn bộ chấm công.
                //
                //   An toàn vì công chuẩn chỉ phụ thuộc tháng/năm — không có dữ liệu
                //   riêng của từng nhân viên nào bị mất khi bỏ qua giá trị đã lưu.
                .standardDays(standardWorkdaysOf(month, year))
                .actualDays(entry != null ? nz(entry.getActualDays()) : 0.0)
                .leaveDays(entry != null ? nz(entry.getLeaveDays()) : 0.0)
                .unpaidDays(entry != null ? nz(entry.getUnpaidDays()) : 0.0)
                .overtimeHours(entry != null ? nz(entry.getOvertimeHours()) : 0.0)
                .presentDays(entry != null ? entry.getPresentDays() : 0)
                .employeeCode(entry != null ? entry.getEmployeeCode() : null)
                .lateCount(entry != null ? entry.getLateCount() : null)
                .lateMinutes(entry != null ? entry.getLateMinutes() : null)
                .earlyCount(entry != null ? entry.getEarlyCount() : null)
                .earlyMinutes(entry != null ? entry.getEarlyMinutes() : null)
                .shiftStart(SHIFT_START.format(HHMM))
                .shiftEnd(SHIFT_END.format(HHMM))
                .days(days)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<PunchSessionDto> readSessions(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<PunchSessionDto> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> mm = (Map<String, Object>) m;
                out.add(PunchSessionDto.builder()
                        .in(str(mm.get("in")))
                        .out(str(mm.get("out")))
                        .build());
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. TRẠNG THÁI FILE & IMPORT BẢNG CHẤM CÔNG
    // ══════════════════════════════════════════════════════════════════════════

    /** TRẠNG THÁI FILE của 1 tháng cho 1 bộ phận. */
    public AttendanceSheetDto monthStatus(int month, int year, PayrollDepartment department) {
        return sheetRepo.findByMonthAndYearAndDepartment(month, year, department)
                .map(this::toSheetDto)
                .orElseGet(() -> AttendanceSheetDto.builder()
                        .month(month).year(year)
                        .periodLabel(periodLabel(month, year))
                        .department(department.name())
                        .departmentLabel(department.getLabel())
                        .employeeCount(deptResolver.employeesOf(department).size())
                        .status("EMPTY")
                        .standardDays(standardWorkdaysOf(month, year))
                        .hasAttendanceFile(false)
                        .hasExceptionFile(false)
                        .hasLeaveFile(false)
                        .parsedRows(0).exceptionRows(0).leaveRows(0)
                        .finalized(false)
                        // Tài xế không cần file nào nên luôn hoàn tất được
                        .canFinalize(!department.isAttendanceBased())
                        .build());
    }

    /** Trạng thái của TẤT CẢ bộ phận trong 1 tháng — FE render 1 lần cho cả tab bar. */
    public List<AttendanceSheetDto> monthStatusAll(int month, int year) {
        return Arrays.stream(PayrollDepartment.values())
                .map(d -> monthStatus(month, year, d))
                .toList();
    }

    /** Danh sách bảng chấm công đã upload. */
    public List<AttendanceSheetDto> listSheets(PayrollDepartment department) {
        List<AttendanceSheet> sheets = department != null
                ? sheetRepo.findByDepartmentOrderByYearDescMonthDesc(department)
                : sheetRepo.findAllByOrderByYearDescMonthDesc();
        return sheets.stream().map(this::toSheetDto).toList();
    }

    private AttendanceSheetDto toSheetDto(AttendanceSheet s) {
        PayrollDepartment d = s.getDepartment() != null ? s.getDepartment() : PayrollDepartment.FACTORY;
        return AttendanceSheetDto.builder()
                .id(s.getId())
                .month(s.getMonth()).year(s.getYear())
                .periodLabel(periodLabel(s.getMonth(), s.getYear()))
                .department(d.name())
                .departmentLabel(d.getLabel())
                .employeeCount(deptResolver.employeesOf(d).size())
                .fileName(s.getFileName())
                .status(s.getStatus() != null ? s.getStatus().name() : "UPLOADED")
                .parsedRows(s.getParsedRows())
                .standardDays(s.getStandardDays())
                .note(s.getNote())
                .uploadedByName(s.getUploadedByName())
                .uploadedAt(s.getUploadedAt())
                .hasAttendanceFile(s.getFilePath() != null)
                .exceptionFileName(s.getExceptionFileName())
                .exceptionUploadedAt(s.getExceptionUploadedAt())
                .exceptionRows(s.getExceptionRows())
                .hasExceptionFile(s.getExceptionFilePath() != null)
                .leaveFileName(s.getLeaveFileName())
                .leaveUploadedAt(s.getLeaveUploadedAt())
                .leaveRows(s.getLeaveRows())
                .hasLeaveFile(s.getLeaveFilePath() != null)
                .finalized(s.isFinalized())
                .finalizedAt(s.getFinalizedAt())
                .finalizedByName(s.getFinalizedByName())
                .canFinalize(!d.isAttendanceBased() || s.getFilePath() != null)
                .build();
    }

    /**
     * Upload + đọc file chấm công của 1 tháng cho 1 BỘ PHẬN.
     * Upload lại cùng tháng + bộ phận → GHI ĐÈ và GỠ cờ "Hoàn tất".
     */
    @Transactional
    public AttendanceImportResultDto uploadSheet(MultipartFile file, int month, int year,
                                                 PayrollDepartment department, User uploader) {
        validateUploadPeriod(month, year);
        requireAttendanceBased(department);

        List<String> warnings = new ArrayList<>();
        String storedPath = storeFile(file, month, year, department, "cham-cong", warnings);

        AttendanceSheet sheet = sheetOf(month, year, department);

        sheet.setFileName(file.getOriginalFilename());
        sheet.setFilePath(storedPath);
        sheet.setUploadedBy(uploader);
        sheet.setUploadedByName(uploader != null ? uploader.getFullName() : null);
        sheet.setUploadedAt(System.currentTimeMillis());
        sheet.setStandardDays(standardWorkdaysOf(month, year));
        sheet.setStatus(AttendanceSheet.SheetStatus.UPLOADED);
        // Đổi file ⇒ số liệu cũ không còn đúng ⇒ phải bấm Hoàn tất lại
        sheet.unfinalize();
        sheet = sheetRepo.save(sheet);

        // Chỉ đối chiếu người CÓ CHẤM CÔNG. Bảo vệ xưởng hưởng khoán trọn tháng,
        // không quẹt thẻ nên không có trong file — đưa vào danh sách này thì lần
        // import nào cũng bị báo "thiếu trong file" và ghi nhận 0 công cho họ.
        List<User> employees = deptResolver.attendanceEmployeesOf(department);
        Map<Long, String> knownCodes = knownEmployeeCodes(employees);

        entryRepo.deleteBySheet_Id(sheet.getId());
        entryRepo.flush();

        List<EmployeeBlock> blocks;

        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            blocks = AttendanceExcelParser.parse(wb, year, warnings);
        } catch (Exception e) {
            log.error("[Attendance] Lỗi đọc file chấm công {} {}/{}", department, month, year, e);
            sheet.setStatus(AttendanceSheet.SheetStatus.ERROR);
            sheet.setNote("Lỗi đọc file: " + e.getMessage());
            sheetRepo.save(sheet);
            return AttendanceImportResultDto.builder()
                    .sheetId(sheet.getId()).month(month).year(year)
                    .department(department.name()).departmentLabel(department.getLabel())
                    .blocksInFile(0).departmentEmployees(employees.size())
                    .matched(0).skipped(employees.size())
                    .matchedRows(List.of()).unmatchedRows(List.of()).unusedBlocks(List.of())
                    .errors(List.of("Lỗi đọc file: " + e.getMessage()))
                    .build();
        }

        if (blocks.isEmpty())
            warnings.add("Không đọc được nhân viên nào từ file — kiểm tra lại định dạng.");

        AttendanceImportResultDto result =
                matchAndSave(sheet, blocks, employees, knownCodes, month, year, department, warnings);

        sheet.setParsedRows(result.getMatched());
        sheet.setStatus(result.getMatched() > 0
                ? AttendanceSheet.SheetStatus.PROCESSED
                : AttendanceSheet.SheetStatus.UPLOADED);
        sheet.setNote(warnings.isEmpty() ? null
                : String.join(" | ", warnings.subList(0, Math.min(10, warnings.size()))));
        sheetRepo.save(sheet);

        // Nhân sự xưởng có thể đã thay đổi → tính lại thưởng KPI của tháng
        if (department.isKpiBonus()) kpiService.recompute(month, year);

        return result;
    }

    /** Khớp block trong file với nhân viên của bộ phận rồi lưu vào DB. */
    private AttendanceImportResultDto matchAndSave(AttendanceSheet sheet,
                                                   List<EmployeeBlock> blocks,
                                                   List<User> employees,
                                                   Map<Long, String> knownCodes,
                                                   int month, int year,
                                                   PayrollDepartment department,
                                                   List<String> warnings) {
        Map<String, EmployeeBlock> byCode = new HashMap<>();
        Map<String, List<EmployeeBlock>> byName = new HashMap<>();
        for (EmployeeBlock b : blocks) {
            if (b.getEmployeeCode() != null && !b.getEmployeeCode().isBlank())
                byCode.putIfAbsent(b.getEmployeeCode().trim(), b);
            byName.computeIfAbsent(normalizeName(b.getEmployeeName()), k -> new ArrayList<>()).add(b);
        }

        // Lịch nghỉ CỦA BỘ PHẬN — dùng chung cho mọi nhân viên trong bộ phận
        Map<Integer, AttendanceException> deptExceptions = new HashMap<>();
        for (AttendanceException e :
                exceptionRepo.findByYearAndMonthAndDepartmentOrderByDayAsc(year, month, department))
            deptExceptions.put(e.getDay(), e);

        // Đơn xin nghỉ ĐÃ DUYỆT đọc từ FILE EXCEL (dữ liệu cũ), gom theo nhân viên
        Map<Long, Map<Integer, AttendanceLeaveRequest>> leavesByUser = new HashMap<>();
        for (AttendanceLeaveRequest lr :
                leaveRepo.findByYearAndMonthAndDepartmentOrderByDayAsc(year, month, department)) {
            if (!lr.isEffective() || lr.getUser() == null) continue;
            leavesByUser.computeIfAbsent(lr.getUser().getId(), k -> new HashMap<>())
                    .put(lr.getDay(), lr);
        }

        // ĐƠN NHÂN VIÊN TỰ TẠO đã được OWNER duyệt — nguồn chính từ nay.
        //
        // Đây chính là chỗ hai nghiệp vụ gặp nhau: nhân viên tạo phiếu và OWNER
        // duyệt là một luồng độc lập, không đụng tới bảng chấm công. Chỉ tới khi
        // OWNER upload bảng chấm công của bộ phận thì các phiếu đã duyệt mới được
        // khớp vào để ra công chuẩn.
        //
        // Lấy theo KHOẢNG NGÀY HIỆU LỰC của phiếu, KHÔNG theo ngày tạo phiếu:
        // phiếu tạo 29/5 xin nghỉ 3–4/6 vẫn rơi đúng vào kỳ tháng 6.
        Map<Long, Map<Integer, com.nhatnam.server.service.hr.EmployeeRequestService.DayEffect>>
                requestEffects = new HashMap<>();
        try {
            requestEffects = employeeRequestService.effectsForPeriod(month, year, department);
        } catch (Exception e) {
            log.error("[Attendance] Không nạp được đơn nhân viên {} {}/{}", department, month, year, e);
            warnings.add("Không đọc được đơn nhân viên đã duyệt — công được tính "
                    + "theo dữ liệu máy chấm công thuần tuý: " + e.getMessage());
        }

        List<MatchRowDto> matchedRows = new ArrayList<>();
        List<MatchRowDto> unmatchedRows = new ArrayList<>();
        List<AttendanceEntry> toSave = new ArrayList<>();
        Set<EmployeeBlock> usedBlocks = Collections.newSetFromMap(new IdentityHashMap<>());

        double standardDays = sheet.getStandardDays() != null
                ? sheet.getStandardDays() : standardWorkdaysOf(month, year);

        for (User u : employees) {
            String roleLabel = deptResolver.roleLabelOf(u);

            Matched m = findBlockFor(u, knownCodes.get(u.getId()), byCode, byName);

            if (m == null) {
                unmatchedRows.add(MatchRowDto.builder()
                        .userId(u.getId()).fullName(u.getFullName()).roleLabel(roleLabel)
                        .presentDays(0).actualDays(0.0)
                        .build());
                continue;
            }

            usedBlocks.add(m.block());
            AttendanceEntry entry = buildEntry(sheet, u, m.block(), month, year, standardDays,
                    deptExceptions,
                    leavesByUser.getOrDefault(u.getId(), Map.of()),
                    requestEffects.getOrDefault(u.getId(), Map.of()),
                    warnings);
            toSave.add(entry);

            matchedRows.add(MatchRowDto.builder()
                    .userId(u.getId()).fullName(u.getFullName()).roleLabel(roleLabel)
                    .employeeCode(m.block().getEmployeeCode())
                    .sourceName(m.block().getEmployeeName())
                    .presentDays(entry.getPresentDays())
                    .actualDays(entry.getActualDays())
                    .matchedBy(m.how())
                    .build());
        }

        entryRepo.saveAll(toSave);

        List<String> unused = blocks.stream()
                .filter(b -> !usedBlocks.contains(b))
                .map(b -> "%s — %s".formatted(
                        b.getEmployeeCode() != null ? b.getEmployeeCode() : "?",
                        b.getEmployeeName()))
                .toList();

        log.info("[Attendance] {} tháng {}/{}: file có {} NV, hệ thống có {} NV, khớp {}",
                department, month, year, blocks.size(), employees.size(), matchedRows.size());

        return AttendanceImportResultDto.builder()
                .sheetId(sheet.getId()).month(month).year(year)
                .department(department.name()).departmentLabel(department.getLabel())
                .blocksInFile(blocks.size())
                .departmentEmployees(employees.size())
                .matched(matchedRows.size())
                .skipped(unmatchedRows.size())
                .matchedRows(matchedRows)
                .unmatchedRows(unmatchedRows)
                .unusedBlocks(unused)
                .errors(warnings)
                .build();
    }

    /** Kết quả khớp: block nào + khớp bằng cách nào. */
    private record Matched(EmployeeBlock block, String how) {}

    private Matched findBlockFor(User u, String knownCode,
                                 Map<String, EmployeeBlock> byCode,
                                 Map<String, List<EmployeeBlock>> byName) {
        if (knownCode != null && byCode.containsKey(knownCode))
            return new Matched(byCode.get(knownCode), "CODE");

        if (u.getFullName() == null || u.getFullName().isBlank()) return null;
        List<EmployeeBlock> hits = byName.get(normalizeName(u.getFullName()));
        return (hits != null && hits.size() == 1) ? new Matched(hits.get(0), "EXACT_NAME") : null;
    }

    /** Dựng bản ghi chấm công của 1 nhân viên từ block đọc được. */
    private AttendanceEntry buildEntry(AttendanceSheet sheet, User user, EmployeeBlock block,
                                       int month, int year, double standardDays,
                                       Map<Integer, AttendanceException> deptExceptions,
                                       Map<Integer, AttendanceLeaveRequest> myLeaves,
                                       Map<Integer, com.nhatnam.server.service.hr.EmployeeRequestService.DayEffect> myEffects,
                                       List<String> warnings) {
        List<Map<String, Object>> daily = new ArrayList<>();
        double actualDays = 0;
        int presentDays = 0;
        int totalLate = 0, totalEarly = 0, totalWorked = 0;
        int lateDays = 0, earlyDays = 0;

        // Công của các ngày nghỉ CÓ LƯƠNG và ngày nghỉ KHÔNG LƯƠNG được đếm
        // riêng để hiện lên phiếu lương, thay vì trộn hết vào actualDays.
        double paidLeaveDays = 0, unpaidLeaveDays = 0, mealDays = 0;

        for (DayRecord d : block.getDays()) {
            if (d.getDate() == null) continue;

            if (d.getDate().getMonthValue() != month || d.getDate().getYear() != year) {
                warnings.add("Nhân viên \"%s\": bỏ qua ngày %s không thuộc tháng %d/%d"
                        .formatted(block.getEmployeeName(), d.getDate(), month, year));
                continue;
            }

            DayPlan w = resolvePlan(d.getDate().getDayOfMonth(), deptExceptions, myLeaves, myEffects);
            DayResult r = resolveDay(d, w);
            actualDays  += r.value();
            totalLate   += r.late();
            totalEarly  += r.early();
            totalWorked += r.worked();
            if (d.hasPunch())      presentDays++;
            if (r.mealEligible())  mealDays++;
            if (r.late()  > 0)     lateDays++;
            if (r.early() > 0)     earlyDays++;

            if ("LEAVE".equals(r.type()))  paidLeaveDays   += r.value();
            if ("UNPAID".equals(r.type())) unpaidLeaveDays += 1.0;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("d", d.getDate().getDayOfMonth());
            m.put("w", d.getWeekdayLabel());
            m.put("v", round2(r.value()));
            m.put("t", r.type());

            LocalTime in = d.firstIn(), out = d.lastOut();
            if (in  != null) m.put("in",  in.format(HHMM));
            if (out != null) m.put("out", out.format(HHMM));
            if (r.late()   > 0) m.put("late",   r.late());
            if (r.early()  > 0) m.put("early",  r.early());
            if (r.worked()   > 0) m.put("wk", r.worked());
            if (r.required() > 0) m.put("rq", r.required());
            if (r.note()   != null) m.put("ex", r.note());
            m.put("ws", w.start().format(HHMM));
            m.put("we", w.end().format(HHMM));
            if (w.deduction() > 0) m.put("dd", w.deduction());

            if (d.getSessions() != null && !d.getSessions().isEmpty()) {
                List<Map<String, Object>> ss = new ArrayList<>();
                for (AttendanceExcelParser.Session sess : d.getSessions()) {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    if (sess.getIn()  != null) sm.put("in",  sess.getIn().format(HHMM));
                    if (sess.getOut() != null) sm.put("out", sess.getOut().format(HHMM));
                    if (!sm.isEmpty()) ss.add(sm);
                }
                if (!ss.isEmpty()) m.put("ss", ss);
            }

            daily.add(m);
        }

        String dailyJson;
        try { dailyJson = MAPPER.writeValueAsString(daily); }
        catch (Exception e) { dailyJson = "[]"; }

        return AttendanceEntry.builder()
                .sheet(sheet)
                .user(user)
                .userFullName(user.getFullName())
                .employeeCode(block.getEmployeeCode())
                .sourceName(block.getEmployeeName())
                .standardDays(standardDays)
                .actualDays(round2(actualDays))
                .presentDays(presentDays)
                .mealDays(round2(mealDays))
                .leaveDays(round2(paidLeaveDays))
                .unpaidDays(round2(unpaidLeaveDays))
                .overtimeHours(0.0)
                .lateCount(lateDays)
                .lateMinutes(totalLate)
                .earlyCount(earlyDays)
                .earlyMinutes(totalEarly)
                .machineTotalHours(block.getTotalHours())
                .machineTotalWorkUnits(block.getTotalWorkUnits())
                .dailyJson(dailyJson)
                .build();
    }

    /**
     * KẾ HOẠCH CÔNG CỦA MỘT NGÀY — kết quả gộp của mọi nguồn ưu đãi.
     *
     * <p>Thay cho {@code DayWindow} cũ (chỉ có khung giờ + cờ đủ công), vì ba
     * quy tắc mới không diễn đạt được bằng khung giờ:
     * <ul>
     *   <li><b>Nghỉ nửa buổi</b> cần một mức công SÀN — không đi làm nốt nửa còn
     *       lại vẫn được 0.5 công, mà khung giờ thì không nói lên điều đó.</li>
     *   <li><b>Nghỉ không phép</b> cần ép về 0 công bất kể máy chấm công ghi gì.</li>
     *   <li><b>Nghỉ theo giờ</b> cần MIỄN một khoảng giữa ca, khác hẳn với dời
     *       giờ vào / giờ ra.</li>
     * </ul>
     *
     * @param start        giờ bắt đầu phải có mặt
     * @param end          giờ kết thúc phải có mặt
     * @param fullCredit   đủ 1 công vô điều kiện (nghỉ có lương, công tác, quên chấm công)
     * @param zeroDay      ép về 0 công (nghỉ không phép)
     * @param minCredit    mức công SÀN dù không đi làm (nghỉ nửa buổi = 0.5)
     * @param excusedFrom  đầu khoảng giờ được miễn có mặt (nghỉ ít hơn 1 ngày)
     * @param excusedTo    cuối khoảng giờ được miễn có mặt
     * @param deduction    số công OWNER quyết định trừ (0–1)
     * @param mealPaidLeave ngày nghỉ có lương → vẫn được phụ cấp cơm dù không quẹt thẻ
     * @param label        mô tả hiển thị trên lịch chi tiết
     */
    private record DayPlan(LocalTime start, LocalTime end,
                           boolean fullCredit, boolean zeroDay,
                           double minCredit,
                           LocalTime excusedFrom, LocalTime excusedTo,
                           double deduction,
                           boolean mealPaidLeave,
                           String label) {


        int expectedMinutes() { return (int) java.time.Duration.between(start, end).toMinutes(); }
    }

    /** Bộ dựng {@link DayPlan} — gom nhiều nguồn ưu đãi cho cùng một ngày. */
    private static final class DayPlanBuilder {
        LocalTime start = SHIFT_START, end = SHIFT_END;
        boolean fullCredit = false, zeroDay = false, mealPaidLeave = false;
        double minCredit = 0.0, deduction = 0.0;
        LocalTime excusedFrom = null, excusedTo = null;
        final List<String> labels = new ArrayList<>();

        DayPlan build() {
            // Không phép LUÔN THẮNG có phép. Một ngày vừa dính đơn nghỉ không
            // lương vừa dính ưu đãi khác thì vẫn về 0 công — nếu làm ngược lại,
            // chồng thêm đơn sẽ trở thành cách lách quyết định đã ra.
            if (zeroDay) { fullCredit = false; minCredit = 0.0; mealPaidLeave = false; }
            LocalTime s = start, e = end;
            if (!s.isBefore(e)) { s = SHIFT_START; e = SHIFT_END; }
            return new DayPlan(s, e, fullCredit, zeroDay,
                    Math.max(0.0, Math.min(1.0, minCredit)),
                    excusedFrom, excusedTo,
                    Math.max(0.0, Math.min(1.0, round2(deduction))),
                    mealPaidLeave,
                    labels.isEmpty() ? null : String.join(" · ", labels));
        }
    }

    /**
     * Dựng kế hoạch công của một ngày từ BA NGUỒN, áp theo thứ tự ưu tiên tăng dần:
     * <ol>
     *   <li><b>Lịch nghỉ của bộ phận</b> — áp cho mọi nhân viên trong bộ phận.</li>
     *   <li><b>Đơn nhập từ file Excel</b> (dữ liệu cũ) — giữ lại để bảng lương các
     *       tháng trước không đổi số sau khi nâng cấp.</li>
     *   <li><b>Đơn nhân viên tự tạo đã được OWNER duyệt</b> — nguồn chính từ nay.</li>
     * </ol>
     * Nguồn sau ghi đè nguồn trước khi mâu thuẫn, vì nó cụ thể hơn về con người.
     */
    private DayPlan resolvePlan(int day,
                                Map<Integer, AttendanceException> deptExceptions,
                                Map<Integer, AttendanceLeaveRequest> legacyLeaves,
                                Map<Integer, EmployeeRequestService.DayEffect> requestEffects) {
        DayPlanBuilder b = new DayPlanBuilder();

        AttendanceException ce = deptExceptions.get(day);
        if (ce != null) applyLegacy(b, ce.getType(), ce.getTimeMark(), "Lịch bộ phận");

        AttendanceLeaveRequest lr = legacyLeaves.get(day);
        if (lr != null) applyLegacy(b, lr.getType(), lr.getTimeMark(), "Đơn (file)");

        EmployeeRequestService.DayEffect eff = requestEffects.get(day);
        if (eff != null) applyEffect(b, eff);

        return b.build();
    }

    /** Áp ngoại lệ kiểu cũ (lịch bộ phận / đơn đọc từ file Excel). */
    private void applyLegacy(DayPlanBuilder b,
                             com.nhatnam.server.enumtype.AttendanceExceptionType type,
                             LocalTime mark, String source) {
        if (type == null) return;
        b.labels.add("%s: %s".formatted(source, type.getLabel())
                + (mark != null ? " (" + mark.format(HHMM) + ")" : ""));

        switch (type) {
            case FULL_DAY_OFF -> { b.fullCredit = true; b.mealPaidLeave = true; }

            // NGHỈ NỬA BUỔI — ca thu lại còn nửa kia, VÀ được mức sàn 0.5 công.
            // Đi làm nốt nửa còn lại thì tỉ lệ có mặt đạt 100% của ca đã thu nên
            // vẫn ra đủ 1 công; không đi thì rơi về đúng mức sàn 0.5.
            case HALF_DAY_MORNING_OFF -> {
                b.start = maxTime(b.start,
                        com.nhatnam.server.enumtype.AttendanceExceptionType.AFTERNOON_SHIFT_START);
                b.minCredit = Math.max(b.minCredit, 0.5);
            }
            case HALF_DAY_AFTERNOON_OFF -> {
                b.end = minTime(b.end,
                        com.nhatnam.server.enumtype.AttendanceExceptionType.MORNING_SHIFT_END);
                b.minCredit = Math.max(b.minCredit, 0.5);
            }

            case LATE_ARRIVAL -> { if (mark != null) b.start = maxTime(b.start, mark); }
            case EARLY_LEAVE  -> { if (mark != null) b.end   = minTime(b.end, mark); }
        }
    }

    /** Áp tác động của ĐƠN NHÂN VIÊN đã được duyệt. */
    private void applyEffect(DayPlanBuilder b, EmployeeRequestService.DayEffect eff) {
        if (eff.label() != null) b.labels.add("Đơn đã duyệt: " + eff.label());

        if (eff.zeroDay()) b.zeroDay = true;

        if (eff.fullDayCredit()) {
            b.fullCredit = true;
            // Nghỉ phép CÓ LƯƠNG vẫn hưởng phụ cấp cơm của ngày đó dù không quẹt thẻ.
            if (eff.mealEligible()) b.mealPaidLeave = true;
        }

        // Đi trễ / về sớm được duyệt: dời mốc ca. Lấy mốc CHẶT HƠN nếu lịch bộ
        // phận đã dời sẵn, để hai ưu đãi không cộng dồn thành một ca quá ngắn.
        if (eff.shiftStart() != null) b.start = maxTime(b.start, eff.shiftStart());
        if (eff.shiftEnd() != null)   b.end   = minTime(b.end, eff.shiftEnd());

        // Nghỉ ÍT HƠN 1 NGÀY: miễn có mặt trong khoảng giờ, KHÔNG dời mốc ca —
        // nhờ vậy phần ca còn lại vẫn bị soi đi trễ / về sớm như thường.
        if (eff.excusedFrom() != null && eff.excusedTo() != null) {
            b.excusedFrom = minTime(b.excusedFrom, eff.excusedFrom());
            b.excusedTo   = maxTime(b.excusedTo, eff.excusedTo());
        }

        b.deduction += eff.deduction();
    }

    /**
     * Kết quả công của một ngày.
     *
     * @param mealEligible ngày này có được tính phụ cấp cơm không
     */
    private record DayResult(double value, String type, int late, int early,
                             int worked, int required, boolean mealEligible, String note) {}

    private DayResult resolveDay(DayRecord d, DayPlan p) {
        // ── Nghỉ KHÔNG PHÉP: 0 công, 0 tiền cơm, bất kể máy ghi gì ────────────
        if (p.zeroDay())
            return new DayResult(0.0, "UNPAID", 0, 0, 0, 0, false, p.label());

        // ── Nghỉ CÓ LƯƠNG / công tác / quên chấm công: đủ công vô điều kiện ──
        if (p.fullCredit()) {
            double v = clamp01(1.0 - p.deduction());
            return new DayResult(v, "LEAVE", 0, 0, 0, 0, p.mealPaidLeave(), p.label());
        }

        // ── Không có dữ liệu chấm công ───────────────────────────────────────
        if (!d.hasPunch()) {
            // Mức sàn của ngày nghỉ nửa buổi vẫn được hưởng dù không đi làm nốt.
            double v = clamp01(p.minCredit() - p.deduction());
            return new DayResult(v, v > 0 ? "HALF" : "OFF", 0, 0, 0, 0, false, p.label());
        }

        LocalTime firstIn = d.firstIn();
        LocalTime lastOut = d.lastOut();

        if (firstIn == null || lastOut == null)
            return new DayResult(0.0, "MISSING", 0, 0, 0, 0, true, p.label());

        int late  = Math.max(0, minutesBetween(p.start(), firstIn));
        int early = Math.max(0, minutesBetween(lastOut, p.end()));

        int present = 0, presentDuringLunch = 0;
        if (d.getSessions() != null) {
            for (AttendanceExcelParser.Session ss : d.getSessions()) {
                if (ss.getIn() == null || ss.getOut() == null) continue;
                present            += overlapMinutes(ss.getIn(), ss.getOut(), p.start(), p.end());
                presentDuringLunch += overlapMinutes(ss.getIn(), ss.getOut(), LUNCH_START, LUNCH_END);
            }
        }

        int lunchAllowance = overlapMinutes(p.start(), p.end(), LUNCH_START, LUNCH_END);

        // Khoảng giờ được miễn có mặt bị TRỪ KHỎI số phút phải làm. Phần trùng
        // với giờ nghỉ trưa đã bị trừ ở trên rồi nên không trừ lần nữa.
        int excused = 0;
        if (p.excusedFrom() != null && p.excusedTo() != null) {
            excused = overlapMinutes(p.excusedFrom(), p.excusedTo(), p.start(), p.end())
                    - overlapMinutes(p.excusedFrom(), p.excusedTo(), LUNCH_START, LUNCH_END);
            excused = Math.max(0, excused);
        }

        int required = Math.max(0, p.expectedMinutes() - lunchAllowance - excused);
        int worked   = Math.max(0, present - presentDuringLunch);

        double value = 1.0;
        if (required > 0) {
            value = BigDecimal.valueOf(Math.min(worked, required))
                    .divide(BigDecimal.valueOf(required), 6, RoundingMode.HALF_UP)
                    .setScale(2, RoundingMode.FLOOR)
                    .doubleValue();
            value = clamp01(value);
        }

        // Mức sàn của ngày nghỉ nửa buổi áp cả khi có đi làm nhưng thiếu giờ.
        value = Math.max(value, p.minCredit());
        value = clamp01(value - p.deduction());

        // Có quẹt thẻ là có ăn cơm, kể cả hôm đó chỉ tính nửa công vì đi trễ.
        return new DayResult(value, "WORK", late, early, worked, required, true, p.label());
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, round2(v)));
    }

    private static LocalTime minTime(LocalTime a, LocalTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static LocalTime maxTime(LocalTime a, LocalTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static int overlapMinutes(LocalTime a1, LocalTime a2, LocalTime b1, LocalTime b2) {
        LocalTime start = a1.isAfter(b1)   ? a1 : b1;
        LocalTime end   = a2.isBefore(b2)  ? a2 : b2;
        return Math.max(0, minutesBetween(start, end));
    }

    private static int minutesBetween(LocalTime from, LocalTime to) {
        return (int) java.time.Duration.between(from, to).toMinutes();
    }

    private Map<Long, String> knownEmployeeCodes(List<User> users) {
        Map<Long, String> result = new HashMap<>();
        for (User u : users) {
            entryRepo.findLatestEmployeeCode(u.getId()).stream()
                    .filter(c -> c != null && !c.isBlank())
                    .findFirst()
                    .ifPresent(c -> result.put(u.getId(), c.trim()));
        }
        return result;
    }

    /**
     * THƯ MỤC RIÊNG CHO MỖI THÁNG × BỘ PHẬN:
     * {@code <storage>/MM_YYYY/<DEPARTMENT>/}.
     */
    public Path monthDir(int month, int year, PayrollDepartment department) {
        Path root = attendanceRoot != null ? attendanceRoot : resolvePath(attendanceDir);
        return root.resolve("%02d_%d".formatted(month, year))
                .resolve(department != null ? department.name() : PayrollDepartment.FACTORY.name());
    }

    private String storeFile(MultipartFile file, int month, int year,
                             PayrollDepartment department, String prefix, List<String> warnings) {
        Path dir = monthDir(month, year, department);
        try {
            Files.createDirectories(dir);

            String ext = Optional.ofNullable(file.getOriginalFilename())
                    .filter(n -> n.contains("."))
                    .map(n -> n.substring(n.lastIndexOf('.')))
                    .orElse(".xlsx");
            String fname = "%s-%d%s".formatted(prefix, System.currentTimeMillis(), ext);
            Path target = dir.resolve(fname);

            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("[Attendance] Đã lưu file {} {}/{}: {}", department, month, year, target);
            return target.toString();

        } catch (IOException e) {
            log.error("[Attendance] Không lưu được file gốc vào {}", dir, e);
            warnings.add("Không lưu được file gốc vào %s (%s). Dữ liệu chấm công vẫn được ghi vào hệ thống."
                    .formatted(dir, e.getMessage()));
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. LỊCH NGHỈ & ĐƠN XIN NGHỈ (theo bộ phận)
    // ══════════════════════════════════════════════════════════════════════════

    /** Lấy (hoặc tạo) bản ghi tháng × bộ phận. */
    private AttendanceSheet sheetOf(int month, int year, PayrollDepartment department) {
        return sheetRepo.findByMonthAndYearAndDepartment(month, year, department)
                .orElseGet(() -> sheetRepo.save(AttendanceSheet.builder()
                        .month(month).year(year)
                        .department(department)
                        .standardDays(standardWorkdaysOf(month, year))
                        .status(AttendanceSheet.SheetStatus.UPLOADED)
                        .build()));
    }

    /** Upload LỊCH NGHỈ / ĐI TRỄ / VỀ SỚM của 1 bộ phận cho 1 tháng. */
    @Transactional
    public AttendanceImportResultDto uploadExceptionFile(MultipartFile file, int month, int year,
                                                         PayrollDepartment department, User uploader) {
        validateUploadPeriod(month, year);
        requireAttendanceBased(department);

        List<String> warnings = new ArrayList<>();
        String path = storeFile(file, month, year, department, "lich-nghi", warnings);

        AttendanceSheet sheet = sheetOf(month, year, department);
        exceptionRepo.deleteByYearAndMonthAndDepartment(year, month, department);
        exceptionRepo.flush();

        int saved = 0;
        int lastDay = YearMonth.of(year, month).lengthOfMonth();

        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            List<AttendanceExceptionParser.ExceptionRow> rows =
                    AttendanceExceptionParser.parseExceptions(wb, lastDay, warnings);

            List<AttendanceException> toSave = new ArrayList<>();
            for (AttendanceExceptionParser.ExceptionRow r : rows) {
                toSave.add(AttendanceException.builder()
                        .year(year).month(month).day(r.getDay())
                        .department(department)
                        .type(r.getType()).timeMark(r.getTimeMark())
                        .note(r.getNote())
                        .build());
            }
            exceptionRepo.saveAll(toSave);
            saved = toSave.size();
        } catch (Exception e) {
            log.error("[Attendance] Lỗi đọc file lịch nghỉ {} {}/{}", department, month, year, e);
            warnings.add("Lỗi đọc file: " + e.getMessage());
        }

        sheet.setExceptionFileName(file.getOriginalFilename());
        sheet.setExceptionFilePath(path);
        sheet.setExceptionUploadedAt(System.currentTimeMillis());
        sheet.setExceptionRows(saved);
        sheet.unfinalize();
        sheetRepo.save(sheet);

        recalculateFromStoredSheet(month, year, department, warnings);

        log.info("[Attendance] Lịch nghỉ {} {}/{}: lưu {} ngoại lệ", department, month, year, saved);
        return AttendanceImportResultDto.builder()
                .sheetId(sheet.getId()).month(month).year(year)
                .department(department.name()).departmentLabel(department.getLabel())
                .blocksInFile(saved).departmentEmployees(deptResolver.attendanceEmployeesOf(department).size())
                .matched(saved).skipped(0)
                .matchedRows(List.of()).unmatchedRows(List.of()).unusedBlocks(List.of())
                .errors(warnings)
                .build();
    }

    /** Upload ĐƠN XIN ĐI TRỄ / VỀ SỚM / NGHỈ PHÉP CỦA CÁ NHÂN cho 1 bộ phận. */
    @Transactional
    public AttendanceImportResultDto uploadLeaveFile(MultipartFile file, int month, int year,
                                                     PayrollDepartment department, User uploader) {
        validateUploadPeriod(month, year);
        requireAttendanceBased(department);

        List<String> warnings = new ArrayList<>();
        String path = storeFile(file, month, year, department, "don-xin-nghi", warnings);

        AttendanceSheet sheet = sheetOf(month, year, department);
        leaveRepo.deleteByYearAndMonthAndDepartment(year, month, department);
        leaveRepo.flush();

        int matched = 0, skipped = 0;
        int lastDay = YearMonth.of(year, month).lengthOfMonth();
        List<MatchRowDto> unmatchedRows = new ArrayList<>();

        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            List<AttendanceExceptionParser.LeaveRow> rows =
                    AttendanceExceptionParser.parseLeaveRequests(wb, lastDay, warnings);

            // CHỈ khớp trong phạm vi nhân viên của bộ phận — đơn của bộ phận khác
            // lọt vào file sẽ bị báo thiếu thay vì âm thầm ghi nhận sai chỗ.
            Map<String, User> byName = new HashMap<>();
            for (User u : deptResolver.attendanceEmployeesOf(department)) {
                if (u.getFullName() != null) byName.putIfAbsent(normalizeName(u.getFullName()), u);
            }

            List<AttendanceLeaveRequest> toSave = new ArrayList<>();
            for (AttendanceExceptionParser.LeaveRow r : rows) {
                User u = byName.get(normalizeName(r.getEmployeeName()));
                if (u == null) {
                    skipped++;
                    warnings.add("Dòng %d: không tìm thấy nhân viên \"%s\" trong bộ phận %s"
                            .formatted(r.getExcelRow(), r.getEmployeeName(), department.getLabel()));
                    unmatchedRows.add(MatchRowDto.builder()
                            .fullName(r.getEmployeeName()).sourceName(r.getEmployeeName())
                            .build());
                } else {
                    matched++;
                }
                toSave.add(AttendanceLeaveRequest.builder()
                        .year(year).month(month).day(r.getDay())
                        .department(department)
                        .user(u).sourceName(r.getEmployeeName())
                        .type(r.getType()).timeMark(r.getTimeMark())
                        .status(r.getStatus()).note(r.getNote())
                        .build());
            }
            leaveRepo.saveAll(toSave);
        } catch (Exception e) {
            log.error("[Attendance] Lỗi đọc file đơn xin nghỉ {} {}/{}", department, month, year, e);
            warnings.add("Lỗi đọc file: " + e.getMessage());
        }

        sheet.setLeaveFileName(file.getOriginalFilename());
        sheet.setLeaveFilePath(path);
        sheet.setLeaveUploadedAt(System.currentTimeMillis());
        sheet.setLeaveRows(matched + skipped);
        sheet.unfinalize();
        sheetRepo.save(sheet);

        recalculateFromStoredSheet(month, year, department, warnings);

        log.info("[Attendance] Đơn xin nghỉ {} {}/{}: khớp {} · bỏ qua {}",
                department, month, year, matched, skipped);
        return AttendanceImportResultDto.builder()
                .sheetId(sheet.getId()).month(month).year(year)
                .department(department.name()).departmentLabel(department.getLabel())
                .blocksInFile(matched + skipped)
                .departmentEmployees(deptResolver.attendanceEmployeesOf(department).size())
                .matched(matched).skipped(skipped)
                .matchedRows(List.of()).unmatchedRows(unmatchedRows).unusedBlocks(List.of())
                .errors(warnings)
                .build();
    }

    /** Tính lại ngày công từ FILE CHẤM CÔNG ĐÃ LƯU của tháng × bộ phận. */
    private void recalculateFromStoredSheet(int month, int year,
                                            PayrollDepartment department, List<String> warnings) {
        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, department).orElse(null);
        if (sheet == null || sheet.getFilePath() == null) return;

        Path f = Paths.get(sheet.getFilePath());
        if (!Files.isRegularFile(f)) {
            warnings.add("Không tìm thấy file chấm công đã lưu để tính lại — "
                    + "hãy tải lại bảng chấm công của tháng.");
            return;
        }

        try (InputStream in = Files.newInputStream(f); Workbook wb = WorkbookFactory.create(in)) {
            List<EmployeeBlock> blocks = AttendanceExcelParser.parse(wb, year, warnings);
            entryRepo.deleteBySheet_Id(sheet.getId());
            entryRepo.flush();

            List<User> employees = deptResolver.attendanceEmployeesOf(department);
            matchAndSave(sheet, blocks, employees, knownEmployeeCodes(employees),
                    month, year, department, warnings);
            if (department.isKpiBonus()) kpiService.recompute(month, year);
            log.info("[Attendance] Đã tính lại ngày công {} {}/{} sau khi cập nhật ngoại lệ",
                    department, month, year);
        } catch (Exception e) {
            log.error("[Attendance] Lỗi tính lại ngày công {} {}/{}", department, month, year, e);
            warnings.add("Không tính lại được ngày công: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. XOÁ FILE
    // ══════════════════════════════════════════════════════════════════════════

    public enum FileKind { ATTENDANCE, EXCEPTION, LEAVE }

    /**
     * Xoá file của tháng × bộ phận + dữ liệu đã import từ file đó.
     *
     * <p>Được phép xoá KỂ CẢ khi đã "Hoàn tất" — cờ hoàn tất sẽ tự gỡ để OWNER
     * tải file mới rồi bấm Hoàn tất lại, lúc đó lương tính theo file mới nhất.
     */
    @Transactional
    public void deleteFile(FileKind kind, int month, int year, PayrollDepartment department) {
        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, department).orElse(null);
        if (sheet == null) return;

        List<String> warnings = new ArrayList<>();
        switch (kind) {
            case ATTENDANCE -> {
                deletePhysical(sheet.getFilePath());
                entryRepo.deleteBySheet_Id(sheet.getId());
                entryRepo.flush();
                sheet.setFileName(null);
                sheet.setFilePath(null);
                sheet.setParsedRows(0);
                sheet.setStatus(AttendanceSheet.SheetStatus.UPLOADED);
                sheet.unfinalize();
                sheetRepo.save(sheet);
                if (department.isKpiBonus()) kpiService.recompute(month, year);
            }
            case EXCEPTION -> {
                deletePhysical(sheet.getExceptionFilePath());
                exceptionRepo.deleteByYearAndMonthAndDepartment(year, month, department);
                exceptionRepo.flush();
                sheet.setExceptionFileName(null);
                sheet.setExceptionFilePath(null);
                sheet.setExceptionUploadedAt(null);
                sheet.setExceptionRows(0);
                sheet.unfinalize();
                sheetRepo.save(sheet);
                recalculateFromStoredSheet(month, year, department, warnings);
            }
            case LEAVE -> {
                deletePhysical(sheet.getLeaveFilePath());
                leaveRepo.deleteByYearAndMonthAndDepartment(year, month, department);
                leaveRepo.flush();
                sheet.setLeaveFileName(null);
                sheet.setLeaveFilePath(null);
                sheet.setLeaveUploadedAt(null);
                sheet.setLeaveRows(0);
                sheet.unfinalize();
                sheetRepo.save(sheet);
                recalculateFromStoredSheet(month, year, department, warnings);
            }
        }
        log.info("[Attendance] Đã xoá file {} của {} tháng {}/{}", kind, department, month, year);
    }

    private void deletePhysical(String path) {
        if (path == null || path.isBlank()) return;
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            log.warn("[Attendance] Không xoá được file {}: {}", path, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. HOÀN TẤT / MỞ LẠI XỬ LÝ LƯƠNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * OWNER bấm "HOÀN TẤT" cho tháng + bộ phận.
     * Từ lúc này nhân viên bộ phận đó xem được phiếu lương của tháng.
     */
    @Transactional
    public AttendanceSheetDto finalizePeriod(int month, int year,
                                             PayrollDepartment department, User actor) {
        validatePastOrCurrentPeriod(month, year);

        AttendanceSheet sheet = sheetOf(month, year, department);

        if (department.isAttendanceBased() && sheet.getFilePath() == null)
            throw new IllegalArgumentException(
                    "Chưa có bảng chấm công của %s tháng %d/%d — không thể hoàn tất."
                            .formatted(department.getLabel(), month, year));

        // Tài xế: bắt buộc đã nhập giá xăng + đơn giá thưởng trước khi Hoàn tất.
        if (department == PayrollDepartment.DRIVER
                && (sheet.getDriverGasPrice() == null || sheet.getDriverBonusUnitPrice() == null))
            throw new IllegalArgumentException(
                    "Vui lòng nhập giá xăng và đơn giá thưởng trước khi hoàn tất lương tài xế.");

        // Tính lại lần cuối theo FILE MỚI NHẤT trước khi chốt
        if (department.isAttendanceBased())
            recalculateFromStoredSheet(month, year, department, new ArrayList<>());
        if (department.isKpiBonus())
            kpiService.recompute(month, year);

        sheet.setFinalized(true);
        sheet.setFinalizedAt(System.currentTimeMillis());
        sheet.setFinalizedByName(actor != null ? actor.getFullName() : null);
        sheetRepo.save(sheet);

        // CHỐT THÂM NIÊN của kỳ.
        //
        //   Mốc đếm năm là NGÀY CUỐI CỦA KỲ LƯƠNG, KHÔNG phải lúc bấm nút: lương
        //   chốt ngày 1 đầu tháng sau nhưng tính cho THÁNG TRƯỚC, lấy ngày bấm sẽ
        //   cộng dôi phần thâm niên của những ngày ngoài kỳ.
        //     Vào làm 29/02/2024 · kỳ T2/2026 · bấm 01/03/2026
        //       mốc 01/03/2026 → 2 năm  ✗     mốc 28/02/2026 → 1 năm  ✓
        computeSeniority(month, year, department, actor);

        log.info("[Attendance] HOÀN TẤT lương {} tháng {}/{} bởi {}",
                department, month, year, actor != null ? actor.getFullName() : "?");
        return toSheetDto(sheet);
    }

    /**
     * TÍNH &amp; CHỐT THÂM NIÊN cho toàn bộ nhân viên của bộ phận trong kỳ.
     *
     * <p>Chạy đúng một lần mỗi khi OWNER bấm "Hoàn tất". Với mỗi nhân viên:
     * <pre>
     *   mốc chốt = NGÀY CUỐI CỦA KỲ LƯƠNG (không phải ngày bấm nút)
     *   số năm   = số năm TRÒN( ngày vào làm → mốc chốt ), LÀM TRÒN XUỐNG
     *   %        = 0 nếu chưa đủ 1 năm, ngược lại min(số năm + 1, 10)
     *   tiền     = lương cơ bản chuẩn × %
     * </pre>
     *
     * <p><b>Vì sao mốc là cuối kỳ lương.</b> Lương chốt vào ngày 1 đầu tháng sau
     * nhưng TÍNH CHO THÁNG TRƯỚC. Lấy ngày bấm nút thì nhân viên được cộng thêm
     * thâm niên của những ngày không thuộc kỳ đang trả:
     * <pre>
     *   Vào làm 29/02/2024 · kỳ T2/2026 (01/02→28/02) · bấm Hoàn tất 01/03/2026
     *     mốc 01/03/2026 → 2 năm  ✗ SAI
     *     mốc 28/02/2026 → 1 năm  ✓ ĐÚNG (chưa tới ngày kỷ niệm)
     * </pre>
     * Nhờ vậy con số CHỈ phụ thuộc (ngày vào làm, kỳ lương): bấm Hoàn tất lúc nào,
     * mở lại rồi chốt lại bao nhiêu lần, vẫn ra đúng một kết quả.
     *
     * <p>Kết quả ghi vào {@code employee_seniority} (upsert theo nhân viên + kỳ).
     * Bản chốt giữ nguyên con số đã trả kể cả khi {@code workStartDate} bị sửa về
     * sau — không có nó thì một lần chỉnh hồ sơ sẽ âm thầm đổi phụ cấp của những
     * tháng đã trả xong.
     *
     * <p>Nhân viên CHƯA khai báo ngày vào làm vẫn được ghi một dòng 0 năm / 0% —
     * để sổ có đủ mặt mọi người và nhìn ra ngay ai còn thiếu dữ liệu.
     *
     * @return số nhân viên đã chốt
     */
    @Transactional
    public int computeSeniority(int month, int year, PayrollDepartment department, User actor) {

        // Cuối kỳ lương — dùng chung một hàm với HrService để hai bên không bao
        // giờ lệch định nghĩa "cuối tháng".
        long referenceDate = HrService.endOfMonthMillis(month, year);

        List<User> employees = deptResolver.employeesOf(department);
        if (employees.isEmpty()) return 0;

        // Nạp một lượt bản chốt cũ + lương của cả bộ phận thay vì hỏi lẻ từng người.
        List<Long> userIds = employees.stream().map(User::getId).toList();
        Map<Long, EmployeeSeniority> existing = seniorityRepo
                .findByPeriodAndUserIds(month, year, userIds).stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), s -> s, (a, b) -> a));

        String actorName = actor != null ? actor.getFullName() : null;
        long now = System.currentTimeMillis();
        int count = 0;

        for (User u : employees) {
            // Lương cơ bản CHUẨN (mức đủ công trong hồ sơ) — gốc để nhân %.
            long baseSalary = salaryRepo.findAllByUserIdOrderByCreatedAtDesc(u.getId())
                    .stream().findFirst()
                    .map(s -> s.getBaseSalary() != null ? s.getBaseSalary() : 0L)
                    .orElse(0L);

            int years   = SeniorityCalculator.years(u.getWorkStartDate(), referenceDate);
            int percent = SeniorityCalculator.percentOf(years);
            long amount = SeniorityCalculator.allowance(baseSalary, percent);

            EmployeeSeniority row = existing.get(u.getId());
            if (row == null) {
                row = EmployeeSeniority.builder()
                        .user(u).month(month).year(year)
                        .build();
            }
            row.setWorkStartDate(u.getWorkStartDate());
            row.setReferenceDate(referenceDate);
            row.setYears(years);
            row.setPercent(percent);
            row.setBaseSalary(baseSalary);
            row.setAmount(amount);
            row.setComputedAt(now);
            row.setComputedByName(actorName);

            seniorityRepo.save(row);
            count++;
        }

        log.info("[Seniority] Đã chốt thâm niên {} nhân viên — {} tháng {}/{}",
                count, department, month, year);
        return count;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LƯƠNG TÀI XẾ — cấu hình giá xăng / đơn giá thưởng + bảng lương
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Điền các trường lương vào {@link DriverMonthDto} theo cấu hình trên sheet.
     * Chỉ trả lương xe máy (tương thích ngược cho màn hình cũ của tài xế).
     */
    private void fillDriverSalary(DriverMonthDto dm, AttendanceSheet sheet, boolean requireFinalized) {
        if (dm == null) return;
        boolean finalized = sheet != null && sheet.isFinalized();
        dm.setFinalized(finalized);

        Long gasPrice = sheet != null ? sheet.getDriverGasPrice() : null;
        Long bonusUnitPrice = sheet != null ? sheet.getDriverBonusUnitPrice() : null;
        dm.setGasPrice(gasPrice);
        dm.setBonusUnitPrice(bonusUnitPrice);

        boolean canCompute = gasPrice != null && bonusUnitPrice != null
                && (!requireFinalized || finalized);
        if (!canCompute) {
            dm.setFuelPay(null); dm.setBonusPay(null); dm.setTotalSalary(null);
            return;
        }
        double km = dm.getTotalKm() != null ? dm.getTotalKm() : 0;
        int tripsMoto = dm.getTotalTripsMotorbike() != null ? dm.getTotalTripsMotorbike()
                : (dm.getTotalTrips() != null ? dm.getTotalTrips() : 0);
        long fuelPay = Math.round(km * gasPrice);
        long bonusPay = (long) tripsMoto * bonusUnitPrice;
        dm.setFuelPay(fuelPay);
        dm.setBonusPay(bonusPay);
        dm.setTotalSalary(fuelPay + bonusPay);
    }

    /**
     * Cấu hình + bảng lương tài xế của một tháng cho OWNER — TÁCH THEO LOẠI XE.
     * Xe máy có tiền xăng + thưởng; xe tải chỉ có thưởng theo lượt (lương cứng
     * xe tải quản lý ở HR khác, không lấy vào bảng này).
     */
    @Transactional(readOnly = true)
    public DriverPayrollConfigDto driverPayrollConfig(int month, int year) {
        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, PayrollDepartment.DRIVER)
                .orElse(null);

        Long gasPrice = sheet != null ? sheet.getDriverGasPrice() : null;
        Long bonusMoto = sheet != null ? sheet.getDriverBonusUnitPrice() : null;
        Long bonusTruck = sheet != null ? sheet.getDriverTruckBonusUnitPrice() : null;
        boolean finalized = sheet != null && sheet.isFinalized();

        List<User> drivers = deptResolver.employeesOf(PayrollDepartment.DRIVER);
        List<DriverSalaryRowDto> rows = new ArrayList<>();
        long grand = 0;

        for (User u : drivers) {
            DriverMonthDto dm = driverKmService.monthOf(u, month, year);
            String vt = dm.getVehicleType();
            boolean hasMoto  = !"TRUCK".equals(vt);
            boolean hasTruck = "TRUCK".equals(vt) || "BOTH".equals(vt);

            // Xe máy
            VehicleSubtotalDto moto = null;
            long motoTotal = 0;
            if (hasMoto) {
                double km = dm.getTotalKm() != null ? dm.getTotalKm() : 0;
                int trips = dm.getTotalTripsMotorbike() != null ? dm.getTotalTripsMotorbike() : 0;
                int orders = dm.getTotalOrdersMotorbike() != null ? dm.getTotalOrdersMotorbike() : 0;
                Long fuel = gasPrice != null ? Math.round(km * gasPrice) : null;
                Long bonus = bonusMoto != null ? (long) trips * bonusMoto : null;
                if (fuel != null && bonus != null) motoTotal = fuel + bonus;
                moto = VehicleSubtotalDto.builder()
                        .totalKm(km).totalOrders(orders).totalTrips(trips)
                        .fuelPay(fuel).bonusPay(bonus)
                        .totalSalary(fuel != null && bonus != null ? motoTotal : null)
                        .build();
            }

            // Xe tải
            VehicleSubtotalDto truck = null;
            long truckTotal = 0;
            if (hasTruck) {
                int trips = dm.getTotalTripsTruck() != null ? dm.getTotalTripsTruck() : 0;
                int orders = dm.getTotalOrdersTruck() != null ? dm.getTotalOrdersTruck() : 0;
                Long bonus = bonusTruck != null ? (long) trips * bonusTruck : null;
                if (bonus != null) truckTotal = bonus;
                truck = VehicleSubtotalDto.builder()
                        .totalOrders(orders).totalTrips(trips)
                        .fuelPay(null).bonusPay(bonus)
                        .totalSalary(bonus != null ? truckTotal : null)
                        .build();
            }

            Long grandForDriver = null;
            if ((moto == null || moto.getTotalSalary() != null)
                    && (truck == null || truck.getTotalSalary() != null))
                grandForDriver = motoTotal + truckTotal;
            if (grandForDriver != null) grand += grandForDriver;

            rows.add(DriverSalaryRowDto.builder()
                    .userId(u.getId())
                    .driverId(dm.getDriverId())
                    .driverName(dm.getDriverName())
                    .vehicleType(vt)
                    .motorbike(moto).truck(truck)
                    .grandTotalSalary(grandForDriver)
                    .build());
        }

        return DriverPayrollConfigDto.builder()
                .month(month).year(year)
                .gasPrice(gasPrice)
                .bonusUnitPrice(bonusMoto)
                .truckBonusUnitPrice(bonusTruck)
                .finalized(finalized)
                .finalizedAt(sheet != null ? sheet.getFinalizedAt() : null)
                .finalizedByName(sheet != null ? sheet.getFinalizedByName() : null)
                .grandTotalSalary(grand)
                .rows(rows)
                .build();
    }

    /**
     * OWNER nhập / cập nhật giá xăng + đơn giá thưởng (xe máy & xe tải) cho tháng.
     * Đổi giá gỡ luôn trạng thái Hoàn tất để OWNER kiểm tra rồi bấm Hoàn tất lại.
     */
    @Transactional
    public DriverPayrollConfigDto saveDriverPayrollConfig(int month, int year, Long gasPrice,
                                                          Long bonusUnitPrice, Long truckBonusUnitPrice) {
        validatePastOrCurrentPeriod(month, year);
        if (gasPrice == null || gasPrice < 0) throw new IllegalArgumentException("Giá xăng không hợp lệ.");
        if (bonusUnitPrice == null || bonusUnitPrice < 0)
            throw new IllegalArgumentException("Đơn giá thưởng xe máy không hợp lệ.");
        if (truckBonusUnitPrice == null || truckBonusUnitPrice < 0)
            throw new IllegalArgumentException("Đơn giá thưởng xe tải không hợp lệ.");

        AttendanceSheet sheet = sheetOf(month, year, PayrollDepartment.DRIVER);
        boolean changed = !java.util.Objects.equals(sheet.getDriverGasPrice(), gasPrice)
                || !java.util.Objects.equals(sheet.getDriverBonusUnitPrice(), bonusUnitPrice)
                || !java.util.Objects.equals(sheet.getDriverTruckBonusUnitPrice(), truckBonusUnitPrice);
        sheet.setDriverGasPrice(gasPrice);
        sheet.setDriverBonusUnitPrice(bonusUnitPrice);
        sheet.setDriverTruckBonusUnitPrice(truckBonusUnitPrice);
        if (changed && sheet.isFinalized()) sheet.unfinalize();
        sheetRepo.save(sheet);

        return driverPayrollConfig(month, year);
    }

    /**
     * Chi tiết lương tài xế cho Tab 2 modal — trả về {@link SalaryBreakdownDto}
     * theo đúng chuẩn của {@code HrService.previewSalary} để FE tái dùng thẳng
     * component <code>SalaryBreakdownCards</code>, đảm bảo cùng cách tính bảo
     * hiểm/thuế/GROSS như phần Nhân sự.
     *
     * <p>Khác biệt của tài xế: thêm 2 khoản đặc thù trước khối KPI —
     * <ul>
     *   <li><b>Phụ cấp cơm trưa</b>: 30.000đ mỗi ngày có điểm danh ODO
     *       (chỉ cần start hoặc end ở BẤT KỲ loại xe nào là tính 1 ngày).</li>
     *   <li><b>Phụ cấp xăng xe</b>: {@code totalKm × giá xăng} (chỉ xe máy).</li>
     * </ul>
     * và <b>Thưởng đơn hàng</b> = thưởng xe máy + thưởng xe tải, cộng dồn vào
     * ô "bonus" (KPI vẫn được áp dụng lên phần thưởng này y hệt các bộ phận khác).
     */
    public static final long MEAL_ALLOWANCE_PER_DAY = 30_000L;

    @Transactional(readOnly = true)
    public SalaryBreakdownDto driverSalaryDetail(Long userId, int month, int year) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user " + userId));

        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, PayrollDepartment.DRIVER)
                .orElse(null);
        DriverMonthDto dm = driverKmService.monthOf(u, month, year);

        Long gasPrice   = sheet != null ? sheet.getDriverGasPrice() : null;
        Long bonusMoto  = sheet != null ? sheet.getDriverBonusUnitPrice() : null;
        Long bonusTruck = sheet != null ? sheet.getDriverTruckBonusUnitPrice() : null;

        // ── Đếm NGÀY CÓ ĐIỂM DANH (dayColor != GRAY) — tính phụ cấp cơm ──────
        //   Một ngày chỉ cần có bất kỳ chấm start/end nào ở BẤT KỲ loại xe (moto
        //   HOẶC truck) là được — đã đúng theo logic hasOdoRecord() sẵn có.
        int mealDays = 0;
        if (dm.getDays() != null)
            for (var d : dm.getDays())
                if (d.getDayColor() != null && !"GRAY".equals(d.getDayColor())) mealDays++;
        long mealAllowance = (long) mealDays * MEAL_ALLOWANCE_PER_DAY;

        // ── Phụ cấp xăng xe (chỉ xe máy) + Thưởng đơn hàng (cả 2 loại) ───────
        double km = dm.getTotalKm() != null ? dm.getTotalKm() : 0;
        int tripsMoto = dm.getTotalTripsMotorbike() != null ? dm.getTotalTripsMotorbike() : 0;
        int tripsTruck = dm.getTotalTripsTruck() != null ? dm.getTotalTripsTruck() : 0;
        long gasAllowance = gasPrice != null ? Math.round(km * gasPrice) : 0L;
        long orderBonusMoto = bonusMoto != null ? (long) tripsMoto * bonusMoto : 0L;
        long orderBonusTruck = bonusTruck != null ? (long) tripsTruck * bonusTruck : 0L;
        long orderBonus = orderBonusMoto + orderBonusTruck;

        // Chi tiết thưởng đơn hàng để FE render 3 dòng (xe máy / xe tải / tổng).
        com.nhatnam.server.dto.hr.HrDtos.DriverOrderBonusDto orderBonusDetail = null;
        if (orderBonus > 0) {
            orderBonusDetail = com.nhatnam.server.dto.hr.HrDtos.DriverOrderBonusDto.builder()
                    .motorbikeTrips(tripsMoto).motorbikeAmount(orderBonusMoto)
                    .truckTrips(tripsTruck).truckAmount(orderBonusTruck)
                    .totalAmount(orderBonus)
                    .build();
        }

        // ── Đọc lương cứng + phụ cấp gốc từ EmployeeSalary (nếu có) ──────────
        long baseSalary = 0L;
        Long insSalaryRaw = null;
        int dependents = 0;
        List<AllowanceItemDto> allowances = new ArrayList<>();

        var approved = salaryRepo.findApprovedByUserId(userId);
        if (approved != null && !approved.isEmpty()) {
            var s = approved.get(0);
            baseSalary = s.getBaseSalary() != null ? s.getBaseSalary() : 0L;
            insSalaryRaw = s.getInsuranceSalary();
            dependents = s.getDependents() != null ? s.getDependents() : 0;
            // Copy các khoản phụ cấp gốc (giữ nhãn + cờ taxable của HR)
            if (s.getAllowanceItems() != null) {
                for (var it : s.getAllowanceItems()) {
                    allowances.add(AllowanceItemDto.builder()
                            .label(it.getLabel())
                            .amount(it.getAmount() != null ? it.getAmount() : 0L)
                            .taxable(it.isTaxable())
                            .build());
                }
            } else if (s.getAllowance() != null && s.getAllowance() > 0) {
                allowances.add(AllowanceItemDto.builder()
                        .label("Phụ cấp").amount(s.getAllowance()).taxable(false).build());
            }
        }

        // Chèn 2 khoản đặc thù tài xế Ở ĐẦU danh sách (hiển thị trước các phụ
        // cấp khác — bám đúng thứ tự bạn mô tả).
        // Cơm trưa & xăng xe theo Thông tư 111/2013: không tính vào thu nhập
        // chịu thuế TNCN (trong định mức doanh nghiệp quy định).
        if (mealAllowance > 0)
            allowances.add(0, AllowanceItemDto.builder()
                    .label("Phụ cấp cơm trưa (" + mealDays + " ngày × 30.000)")
                    .amount(mealAllowance).taxable(false).build());
        if (gasAllowance > 0)
            allowances.add(mealAllowance > 0 ? 1 : 0, AllowanceItemDto.builder()
                    .label("Phụ cấp xăng xe (" + fmtNum(km) + " km × " + fmtNum(gasPrice) + "đ)")
                    .amount(gasAllowance).taxable(false).build());

        // ── Build request + gọi HR service để có breakdown chuẩn ─────────────
        //   Thưởng đơn hàng (orderBonus) tách RIÊNG khỏi thưởng KPI — thưởng KPI
        //   giữ nguyên cách của HR (bằng 0 cho tài xế trừ khi có cấu hình riêng),
        //   còn orderBonus cộng thẳng vào net sau khi HR tính xong. Trong FE hiện
        //   thành dòng "Thưởng đơn hàng" đứng trước "Thưởng KPI".
        SalaryRequest req = new SalaryRequest();
        req.setUserId(userId);
        req.setBaseSalary(baseSalary);
        req.setInsuranceSalary(insSalaryRaw);   // null = mặc định = baseSalary
        req.setDependents(dependents);
        req.setAllowances(allowances);
        req.setBonus(0L);
        req.setBonusTaxable(false);
        // Truyền month/year để HR service tự nạp phụ cấp/thưởng import từ
        // MonthlyAdjustment (Chuyên cần, Xăng xe kho…). Nhờ đó Chuyên cần hiển
        // thị đúng nhãn thay vì gộp vào "Thưởng KPI".
        req.setMonth(month);
        req.setYear(year);
        SalaryBreakdownDto dto = hrService.previewSalary(req);

        // Cộng thưởng đơn hàng (không tính thuế) vào lương thực nhận + đánh dấu
        // driverOrderBonus cho FE hiển thị.
        if (dto != null && orderBonus > 0) {
            long curNet = dto.getNetSalary() != null ? dto.getNetSalary() : 0L;
            dto.setNetSalary(curNet + orderBonus);
            if (dto.getNetSalaryExact() != null)
                dto.setNetSalaryExact(dto.getNetSalaryExact() + orderBonus);
        }
        if (dto != null) dto.setDriverOrderBonus(orderBonus);
        if (dto != null && orderBonusDetail != null) dto.setDriverOrderBonusDetail(orderBonusDetail);
        return dto;
    }

    private static String fmtNum(Number n) {
        if (n == null) return "0";
        long v = n.longValue();
        return String.format(java.util.Locale.GERMANY, "%,d", v);
    }

    /** Mở lại tháng đã hoàn tất (nhân viên quay về trạng thái "Đang xử lý lương"). */
    @Transactional
    public AttendanceSheetDto reopenPeriod(int month, int year, PayrollDepartment department) {
        AttendanceSheet sheet = sheetOf(month, year, department);
        sheet.unfinalize();
        sheetRepo.save(sheet);
        log.info("[Attendance] MỞ LẠI lương {} tháng {}/{}", department, month, year);
        return toSheetDto(sheet);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. BẢNG TỔNG HỢP LƯƠNG CỦA CẢ BỘ PHẬN (OWNER xem sau khi Hoàn tất)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 2 bảng OWNER xem sau khi bấm Hoàn tất:
     * <b>Phiếu lương</b> + <b>Chi tiết ngày công</b> của tháng đang chọn.
     */
    @Transactional(readOnly = true)
    public DepartmentPayrollDto departmentPayroll(int month, int year, PayrollDepartment department) {
        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, department).orElse(null);

        List<User> employees = deptResolver.employeesOf(department);

        // ── KPI của tháng — chỉ bộ phận Xưởng ────────────────────────────────
        //
        // Dùng find() chứ KHÔNG getOrCompute(): màn hình này phải phân biệt được
        // "chưa tính KPI" với "đã tính, quỹ bằng 0". getOrCompute sẽ âm thầm tính
        // ngay khi ai đó mở trang, khiến trạng thái "chưa tính" không bao giờ tồn
        // tại và OWNER mất khả năng thấy mình còn thiếu bước nào.
        // KPI được tính ở đúng hai chỗ có chủ đích: bấm "Hoàn tất" và bấm "Tính lại".
        Map<Long, Long> kpiByUser = new HashMap<>();
        FactoryKpiBonus kpi = null;
        if (department.isKpiBonus()) {
            try {
                kpi = kpiService.find(month, year).orElse(null);
                if (kpi != null) {
                    for (FactoryKpiBonusItem i : kpi.getItems()) {
                        if (i.getUser() != null) kpiByUser.put(i.getUser().getId(), nz(i.getAmount()));
                    }
                }
            } catch (Exception e) {
                log.warn("[Attendance] Không lấy được KPI {}/{}: {}", month, year, e.getMessage());
            }
        }

        List<DepartmentPayrollRowDto> rows = new ArrayList<>();
        long totalNet = 0, totalGross = 0, totalKpi = 0;

        for (User u : employees) {
            // Truyền kỳ lương để lương Xưởng được chia theo NGÀY CÔNG của tháng này
            SalaryBreakdownDto s = hrService.getSalaryBreakdownForUser(u.getId(), month, year);
            AttendanceEntry e = department.isAttendanceBased()
                    ? entryRepo.findByUserAndPeriod(u.getId(), month, year).orElse(null)
                    : null;

            long kpiAmount = kpiByUser.getOrDefault(u.getId(), 0L);
            boolean exempt = deptResolver.isAttendanceExempt(u);

            DepartmentPayrollRowDto.DepartmentPayrollRowDtoBuilder rb = DepartmentPayrollRowDto.builder()
                    .userId(u.getId())
                    .userFullName(u.getFullName())
                    .roleLabel(deptResolver.roleLabelOf(u))
                    .payrollRole(u.getPayrollRole() != null ? u.getPayrollRole().name() : null)
                    .department(u.getDepartment())
                    .division(u.getDivision())
                    .kpiBonus(kpiAmount)
                    .attendanceExempt(exempt)
                    .hasAttendance(e != null);

            if (s != null) {
                rb.salaryStatus(s.getStatus())
                        .baseSalary(nz(s.getBaseSalary()))
                        .allowance(nz(s.getAllowance()))
                        .bonus(nz(s.getEffectiveBonus()))
                        .grossSalary(nz(s.getGrossSalary()))
                        .employeeInsuranceTotal(nz(s.getEmployeeInsuranceTotal()))
                        .personalIncomeTax(nz(s.getPersonalIncomeTax()))
                        .netSalary(nz(s.getNetSalary()))
                        .seniorityYears(s.getSeniorityYears())
                        .seniorityPercent(s.getSeniorityPercent())
                        .seniorityAllowance(nz(s.getSeniorityAllowance()));
                totalNet += nz(s.getNetSalary());
                rb.standardBaseSalary(s.getStandardBaseSalary())
                        .salaryDetail(s);
                totalGross += nz(s.getGrossSalary());
            } else {
                rb.salaryStatus("NO_SALARY")
                        .baseSalary(0L).allowance(0L).bonus(0L).grossSalary(0L)
                        .employeeInsuranceTotal(0L).personalIncomeTax(0L).netSalary(0L);
            }
            totalKpi += kpiAmount;

            if (e != null) {
                // Tính lại công chuẩn thay vì đọc số đã lưu — xem ghi chú ở
                // buildAttendanceSummary về các bản ghi import trước khi sửa lỗi.
                rb.standardDays(standardWorkdaysOf(month, year))
                        .actualDays(e.getActualDays())
                        .presentDays(e.getPresentDays())
                        .lateCount(e.getLateCount()).lateMinutes(e.getLateMinutes())
                        .earlyCount(e.getEarlyCount()).earlyMinutes(e.getEarlyMinutes())
                        .employeeCode(e.getEmployeeCode());
            } else if (department.isAttendanceBased() && !exempt) {
                // Bỏ trống với người được miễn chấm công — điền 0 sẽ khiến bảng
                // trông như họ nghỉ cả tháng và bị trừ hết công.
                rb.standardDays(standardWorkdaysOf(month, year))
                        .actualDays(0.0).presentDays(0)
                        .lateCount(0).lateMinutes(0).earlyCount(0).earlyMinutes(0);
            }

            // Tài xế: thay ngày công bằng số km + số đơn
            if (!department.isAttendanceBased()) {
                DriverMonthDto dm = driverKmService.monthOf(u, month, year);
                rb.totalKm(dm.getTotalKm()).totalOrders(dm.getTotalOrders());
            }

            rows.add(rb.build());
        }

        // ── XẾP THEO CẤP BẬC, không theo thứ tự tạo tài khoản ─────────────────
        //   Bảng lương được đọc từ trên xuống nên phải bám cơ cấu tổ chức:
        //   Trưởng xưởng → Quản lý → Trợ lý → Kế toán → Nhân viên → Công nhân →
        //   Bảo vệ. Vị trí lạ (bộ phận khác, chưa set chức vụ) rơi xuống cuối,
        //   trong mỗi bậc thì xếp theo tên để thứ tự ổn định giữa các lần tải.
        rows.sort(Comparator
                .comparingInt((DepartmentPayrollRowDto r) -> payrollRoleRank(r.getPayrollRole()))
                .thenComparing(r -> r.getUserFullName() != null ? r.getUserFullName() : "",
                        String.CASE_INSENSITIVE_ORDER));

        DepartmentPayrollDto.DepartmentPayrollDtoBuilder out = DepartmentPayrollDto.builder()
                .month(month).year(year).periodLabel(periodLabel(month, year))
                .department(department.name()).departmentLabel(department.getLabel())
                .finalized(sheet != null && sheet.isFinalized())
                .attendanceBased(department.isAttendanceBased())
                .hasKpiBonus(department.isKpiBonus())
                .employeeCount(rows.size())
                .totalNetSalary(totalNet)
                .totalGrossSalary(totalGross)
                .totalKpiBonus(totalKpi)
                .kpiComputed(kpi != null)
                .rows(rows);

        if (kpi != null) {
            out.kpiTotalOutputKg(kpi.getTotalOutputKg())
                    .kpiTotalOutputTon(kpi.getTotalOutputTon())
                    .kpiRatePerTon(kpi.getRatePerTon())
                    .kpiBonusPool(kpi.getBonusPool())
                    .kpiCarryOverIn(kpi.getCarryOverIn())
                    .kpiCarryOverOut(kpi.getCarryOverOut())
                    .kpiComputedAt(kpi.getComputedAt())
                    .kpiCarryOverInDetail(
                            kpiService.carryOverInDetail(month, year).stream()
                                    .map(e -> CarryOverEntryDto.builder()
                                            .month(e.getMonth())
                                            .year(e.getYear())
                                            .amount(e.getAmount())
                                            .label("T%d/%d".formatted(e.getMonth(), e.getYear()))
                                            .build())
                                    .toList());
        }

        return out.build();
    }

    /** Chi tiết ngày công của 1 nhân viên — OWNER bấm vào 1 dòng trong bảng. */
    @Transactional(readOnly = true)
    public AttendanceSummaryDto employeeAttendance(Long userId, int month, int year) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên: " + userId));
        PayrollDepartment dept = deptResolver.departmentOf(u);

        AttendanceSheet sheet = dept != null
                ? sheetRepo.findByMonthAndYearAndDepartment(month, year, dept).orElse(null)
                : null;

        double std = sheet != null && sheet.getStandardDays() != null
                ? sheet.getStandardDays() : standardWorkdaysOf(month, year);

        AttendanceEntry entry = entryRepo.findByUserAndPeriod(userId, month, year).orElse(null);
        return buildAttendanceSummary(entry, month, year, std);
    }

    /** Số km theo ngày của 1 tài xế — OWNER bấm vào 1 dòng tài xế. */
    @Transactional(readOnly = true)
    public DriverMonthDto employeeDriverMonth(Long userId, int month, int year) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên: " + userId));
        return driverKmService.monthOf(u, month, year);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NHÂN SỰ
    // ══════════════════════════════════════════════════════════════════════════

    /** Nhân viên của 1 bộ phận (đã loại role kiêm nhiệm). */
    public List<User> employeesOf(PayrollDepartment department) {
        return deptResolver.employeesOf(department);
    }

    /**
     * DANH SÁCH NHÂN SỰ của bộ phận cho modal "Chi tiết bộ phận".
     *
     * <p>Không phụ thuộc kỳ lương nào: đây là ai đang thuộc bộ phận NGAY LÚC NÀY,
     * dùng để đối chiếu trước khi tải bảng chấm công lên. Nhờ vậy OWNER thấy được
     * người mới chưa có hồ sơ lương, hoặc người bị xếp nhầm bộ phận, trước khi
     * phát hiện qua con số lệch ở bảng lương.
     */
    @Transactional(readOnly = true)
    public DepartmentMembersDto departmentMembers(PayrollDepartment department) {
        List<User> users = deptResolver.employeesOf(department);

        // MỘT query cho cả bộ phận thay vì mỗi người một lần — bộ phận 40 người
        // sẽ là 40 lượt truy vấn nếu hỏi lẻ, chỉ để tô một cái nhãn cảnh báo.
        Set<Long> withSalary = users.isEmpty() ? Set.of()
                : salaryRepo.findApprovedByUserIds(users.stream().map(User::getId).toList())
                .stream()
                .map(x -> x.getUser().getId())
                .collect(java.util.stream.Collectors.toSet());

        List<DepartmentMemberDto> members = users.stream()
                .map(u -> DepartmentMemberDto.builder()
                        .userId(u.getId())
                        .fullName(u.getFullName())
                        .position(u.getPosition())
                        .roleLabel(deptResolver.roleLabelOf(u))
                        .division(u.getDivision())
                        .attendanceExempt(deptResolver.isAttendanceExempt(u))
                        .hasSalary(withSalary.contains(u.getId()))
                        .build())
                .toList();

        return DepartmentMembersDto.builder()
                .department(department.name())
                .departmentLabel(department.getLabel())
                .total(members.size())
                .members(members)
                .build();
    }

    /** Giữ lại cho các nơi gọi cũ — nhân sự XƯỞNG. */
    public List<User> factoryEmployees() {
        return deptResolver.employeesOf(PayrollDepartment.FACTORY);
    }

    /** TRUE nếu nhân viên được vào trang Quản lý lương. */
    public boolean hasPayroll(User user) {
        return deptResolver.hasPayroll(user);
    }

    /** TRUE nếu user thuộc nhân sự xưởng. */
    public boolean isFactoryStaff(User user) {
        return deptResolver.departmentOf(user) == PayrollDepartment.FACTORY;
    }

    /** Role xưởng chính của user — dùng cho phần chia thưởng KPI. */
    public Role primaryFactoryRole(User user) {
        return user.getAllRoles().stream()
                .filter(r -> r.name().startsWith(FACTORY_ROLE_PREFIX) || r == Role.SUPER_FACTORY_WORKER)
                .max(Comparator.comparingDouble(
                        r -> FactoryKpiService.ROLE_WEIGHTS.getOrDefault(r, 0.0)))
                .orElse(null);
    }

    /** Bộ phận tính lương của nhân viên. */
    public PayrollDepartment departmentOf(User user) {
        return deptResolver.departmentOf(user);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TIỆN ÍCH
    // ══════════════════════════════════════════════════════════════════════════

    private void requireAttendanceBased(PayrollDepartment d) {
        if (d != null && !d.isAttendanceBased())
            throw new IllegalArgumentException(
                    "Bộ phận %s không dùng bảng chấm công.".formatted(d.getLabel()));
    }

    /** Chặn xem/upload tháng hiện tại hoặc tương lai — chỉ cho tháng ĐÃ QUA. */
    public void validatePastPeriod(int month, int year) {
        YearMonth target = YearMonth.of(year, month);
        YearMonth current = YearMonth.now(VN);
        if (!target.isBefore(current)) {
            throw new IllegalArgumentException(
                    "Chỉ xem/tải được các tháng đã kết thúc. Tháng %d/%d chưa hết.".formatted(month, year));
        }
    }

    /** Cho phép tháng hiện tại + quá khứ, chặn tương lai. */
    public void validateUploadPeriod(int month, int year) {
        YearMonth target = YearMonth.of(year, month);
        if (target.isAfter(YearMonth.now(VN)))
            throw new IllegalArgumentException(
                    "Không thao tác được với tháng tương lai (%d/%d).".formatted(month, year));
    }

    private void validatePastOrCurrentPeriod(int month, int year) {
        validateUploadPeriod(month, year);
    }

    /** Công chuẩn của tháng: T2–T6 = 1 công, T7 = 0.5 công, CN = nghỉ. */
    /**
     * SỐ NGÀY CÔNG CHUẨN CỦA THÁNG = số ngày trong tháng trừ các CHỦ NHẬT.
     *
     * <p>Uỷ quyền cho {@link PayrollTaxCalculator#standardWorkdaysOf} để chỉ có
     * MỘT định nghĩa công chuẩn trong toàn hệ thống: khâu chấm công và khâu chia
     * lương phải dùng chung con số, lệch nhau là sai ngay.
     *
     * <p>Thứ Bảy tính TRÒN 1 công. Bản cũ tính 0,5 nên tháng 7/2026 ra 25 công
     * chuẩn trong khi bảng chấm công chấm đủ 27 ngày — nhân viên nghỉ hơn một
     * ngày vẫn hiện "đủ công".
     */
    public static double standardWorkdaysOf(int month, int year) {
        return PayrollTaxCalculator.standardWorkdaysOf(month, year);
    }

    /**
     * THỨ TỰ HIỂN THỊ theo cấp bậc trong xưởng — số nhỏ đứng trước.
     *
     * <p>Dựa trên tên enum của role hưởng lương chứ không dựa vào nhãn tiếng Việt:
     * nhãn có thể đổi chữ bất cứ lúc nào, còn tên enum thì gắn với schema.
     *
     * <p>Role không nằm trong danh sách (bộ phận khác, hoặc nhân viên chưa được
     * set chức vụ trả lương) nhận thứ hạng cuối để không chen vào giữa bảng.
     */
    private static int payrollRoleRank(String payrollRole) {
        if (payrollRole == null) return 99;
        return switch (payrollRole) {
            case "SUPER_FACTORY_WORKER"      -> 0;   // Trưởng xưởng
            case "FACTORY_MANAGER"           -> 1;   // Quản lý xưởng
            case "FACTORY_STAFF"             -> 2;   // Trợ lý xưởng
            case "FACTORY_ACCOUNTANT"        -> 3;   // Kế toán xưởng
            case "FACTORY_WORKER"            -> 4;   // Nhân viên xưởng
            case "FACTORY_PRODUCTION_WORKER" -> 5;   // Công nhân sản xuất
            case "FACTORY_SECURITY"          -> 6;   // Bảo vệ xưởng — khoán, xếp cuối
            default -> 99;
        };
    }

    public static String periodLabel(Integer month, Integer year) {
        return "Tháng %d/%d".formatted(month, year);
    }

    private static String defaultWeekdayLabel(int weekday) {
        return switch (weekday) {
            case 2 -> "Hai"; case 3 -> "Ba";  case 4 -> "Tư";
            case 5 -> "Năm"; case 6 -> "Sáu"; case 7 -> "Bảy";
            default -> "CN";
        };
    }

    private static String str(Object o) { return o != null ? String.valueOf(o) : null; }
    private static long nz(Long v) { return v != null ? v : 0L; }
    private static double nz(Double v) { return v != null ? v : 0.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static String normalizeName(String s) {
        if (s == null) return "";
        return stripAccents(s.trim().toLowerCase()).replaceAll("\\s+", " ").trim();
    }

    private static String stripAccents(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
    }
}