package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.*;
import com.nhatnam.server.dto.hr.HrDtos.AllowanceItemDto;
import com.nhatnam.server.dto.hr.HrDtos.SalaryBreakdownDto;
import com.nhatnam.server.dto.hr.HrDtos.SalaryRequest;
import com.nhatnam.server.utils.PayrollTaxCalculator;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.entity.MonthlyAdjustment;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.attendance.AttendanceExcelParser;
import com.nhatnam.server.service.attendance.AttendanceExcelParser.DayRecord;
import com.nhatnam.server.service.attendance.AttendanceExcelParser.EmployeeBlock;
import com.nhatnam.server.service.attendance.AttendanceExceptionParser;
import com.nhatnam.server.service.hr.EmployeeRequestService;
import com.nhatnam.server.utils.LeaveBalanceCalculator;
import com.nhatnam.server.service.hr.HrService;
import com.nhatnam.server.service.hr.PayrollDepartmentResolver;
import com.nhatnam.server.service.hr.OfficeBonusCommissionUtil;
import com.nhatnam.server.service.hr.OfficeBonusCommissionUtil.AccountingShare;
import com.nhatnam.server.service.hr.OfficeBonusCommissionUtil.AccountingPoolResult;
import com.nhatnam.server.service.hr.OfficeBonusCommissionUtil.AccountingShareV2;
import com.nhatnam.server.service.hr.OfficeBonusCommissionUtil.SalesBonusResult;
import com.nhatnam.server.service.hr.OfficeBonusCommissionUtil.SalesTier;
import com.nhatnam.server.service.hr.OfficeBonusCommissionUtil.UserRatio;
import com.nhatnam.server.service.hr.OfficeBonusCommissionUtil.UserWeight;
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

    /**
     * Giờ tan ca của nhân viên PART-TIME (chỉ làm buổi sáng).
     * Dùng khi tự động điền giờ ra cho ngày cuối tháng — file chấm công lấy
     * vào ngày cuối tháng nên thường thiếu giờ ra của chính hôm đó.
     */
    public static final LocalTime PART_TIME_END = LocalTime.of(12, 0);

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
    private final MonthlyAdjustmentRepository monthlyAdjustmentRepo;
    private final DriverRepository driverRepo;
    /** Bản CHỐT thâm niên của kỳ — ghi khi OWNER bấm "Hoàn tất". */
    private final EmployeeSeniorityRepository seniorityRepo;
    /** Nguồn sự thật cho ưu đãi từ ĐƠN NHÂN VIÊN đã được OWNER duyệt. */
    private final com.nhatnam.server.service.hr.EmployeeRequestService employeeRequestService;
    private final OfficeBonusResultRepository officeBonusResultRepo;
    private final com.nhatnam.server.repository.SalesBonusOverrideRepository salesBonusOverrideRepo;
    private final com.nhatnam.server.repository.FactoryKpiBonusRepository factoryKpiBonusRepo;
    private final com.nhatnam.server.repository.FactoryKpiBonusItemRepository factoryKpiBonusItemRepo;
    private final com.nhatnam.server.repository.WarehouseBonusRepository warehouseBonusRepo;
    private final com.nhatnam.server.repository.AttendanceBonusRepository attendanceBonusRepo;
    // (10/2026) Thưởng cố định tài xế xe tải — tách khỏi lương tài xế xe máy
    private final com.nhatnam.server.repository.TruckDriverFixedBonusRepository truckDriverFixedBonusRepo;
    private final PaymentTransactionRepository paymentTransactionRepo;
    private final OrderRepository orderRepository;
    private final com.nhatnam.server.service.hr.HolidayService holidayService;

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

        // BẮT ĐẦU TỪ i = 0 (bao gồm tháng hiện tại)
        for (int i = 0; i <= MONTHS_LOOKBACK; i++) {
            YearMonth ym = current.minusMonths(i);
            AttendanceSheet s = department == null ? null
                    : sheetRepo.findByMonthAndYearAndDepartment(
                    ym.getMonthValue(), ym.getYear(), department).orElse(null);

            // (10/2026) Phase 1 refactor: lương được Public toàn công ty qua
            // COMPANY_SENTINEL (= FACTORY sheet). Nhân viên các dept khác
            // (KT/KD/Kho/Driver) không có per-dept sheet.finalized → UI báo
            // sai "Đang xử lý" dù lương đã Public. Fix: coi là finalized khi
            // EITHER per-dept finalized OR company sheet đã PUBLISHED.
            AttendanceSheet company = sheetRepo
                    .findCompanySheet(ym.getMonthValue(), ym.getYear()).orElse(null);
            boolean companyPub = company != null
                    && company.getCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED;
            boolean salaryReady = (s != null && s.isFinalized()) || companyPub;
            // (10/2026) Cờ mới cho FE: thưởng đã Public chưa (hiển thị "Đã có
            // lương/thưởng" thay vì "Đã có lương"). True khi EITHER:
            //   - bonusCalcStatus=PUBLISHED (SALES/ACCOUNTING/DRIVER dùng bonus flow)
            //   - kpiFinalized=true (FACTORY dùng KPI flow — không có bonus_* flags)
            boolean bonusReady = s != null
                    && (s.isBonusFinalized()
                    || s.getBonusCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED
                    || s.isKpiFinalized()
                    || s.getKpiCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED);

            result.add(PeriodOptionDto.builder()
                    .month(ym.getMonthValue())
                    .year(ym.getYear())
                    .label(periodLabel(ym.getMonthValue(), ym.getYear()))
                    .attendanceReady(s != null && s.getFilePath() != null)
                    .finalized(salaryReady)
                    .bonusReady(bonusReady)
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
        // ── THAY validatePastPeriod BẰNG validateViewPeriod ──
        PayrollDepartment dept = deptResolver.departmentOf(user);
        validateViewPeriod(month, year, dept);

        String label = periodLabel(month, year);
        Role payrollRole = deptResolver.payrollRoleOf(user);
        // Lưu ý: dept đã có ở trên, không cần gọi lại
        // PayrollDepartment dept = PayrollDepartment.of(payrollRole); // ← XÓA DÒNG NÀY

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

        // (10/2026) Flow lương mới = COMPANY-WIDE: chỉ có 1 sheet dưới sentinel
        // FACTORY (xem AttendanceSheetRepository.COMPANY_SENTINEL). OWNER Public
        // 1 lần cho cả công ty → chỉ sentinel sheet có calcStatus=PUBLISHED.
        // Per-dept sheet của KT/KD/Kho/Driver có thể KHÔNG tồn tại → nhân viên
        // sẽ bị kẹt ở "Đang xử lý" dù lương toàn cty đã Public.
        //
        // Fix: coi là Finalized khi EITHER per-dept.finalized=true OR company
        // sheet đã PUBLISHED. Giữ nguyên per-dept lookup bên dưới để dùng cho
        // attendance/standardDays etc.
        AttendanceSheet companySheet = sheetRepo.findCompanySheet(month, year).orElse(null);
        boolean companyPublished = companySheet != null
                && companySheet.getCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED;

        boolean salaryReady = (sheet != null && sheet.isFinalized()) || companyPublished;
        if (!salaryReady) {
            return b.status("PROCESSING").kpiStatus("NONE").build();
        }
        // Nếu per-dept sheet chưa có, dùng company sheet làm thay cho đoạn code
        // bên dưới (cần sheet != null để đọc standardDays, uploadedAt, …).
        if (sheet == null) sheet = companySheet;

        // Xác định trạng thái KPI — tách riêng khỏi lương
        // (10/2026) KT/KD KHÔNG có flow "Hoàn tất KPI" riêng — thưởng hoa hồng
        // của họ gắn trực tiếp vào field kpiBonus trên MyPayslipDto. FE check
        // `kpiStatus === 'READY'` mới hiện kpiBonus. Vì vậy khi bonus đã Public
        // (bonusCalcStatus=PUBLISHED), coi kpiStatus = READY cho KT/KD/DRIVER
        // để FE không ẩn phần thưởng hoa hồng/driver bonus.
        boolean bonusAlreadyPublished = sheet.isBonusFinalized()
                || sheet.getBonusCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED;
        String kpiStatus;
        if (sheet.isKpiFinalized() || bonusAlreadyPublished) {
            kpiStatus = "READY";
        } else {
            kpiStatus = "PENDING";
        }
        b.kpiStatus(kpiStatus);

        b.attendanceUploadedAt(sheet.getUploadedAt())
                .finalizedAt(sheet.getFinalizedAt());

        // ── KINH DOANH và KẾ TOÁN: FULL lương, chấm công CHỈ để tính phụ cấp cơm ──
        //
        // Lương = 100% lương cơ bản bất kể số ngày đi làm.
        // Cơm   = 30.000đ × mealDays:
        //           - Có file chấm công → đọc mealDays từ AttendanceEntry (số ngày có vào).
        //           - Không có file → mặc định = standardWorkdays của tháng (full cơm).
        //
        // Thưởng doanh thu: hiển thị thêm sau khi bonusFinalized = true (bước 3).
        //
        // ── TRIỂN KHAI SAU ─────────────────────────────────────────────────────
        // Nếu muốn tính lương theo ngày công (prorate):
        //   double std = attendance.getStandardDays();
        //   double act = attendance.getActualDays();
        //   byAttendance = base * act / std;
        // Hiện tại: byAttendance = base (full lương).
        // ─────────────────────────────────────────────────────────────────────
        if (dept == PayrollDepartment.SALES || dept == PayrollDepartment.ACCOUNTING) {
            // Đọc attendance để hiển thị ngày công / mealDays trên phiếu (nếu có file)
            if (sheet != null && dept.isAttendanceBased()) {
                double defaultStd = sheet.getStandardDays() != null
                        ? sheet.getStandardDays()
                        : standardWorkdaysOf(month, year);
                AttendanceEntry entry = entryRepo.findByUserAndPeriod(user.getId(), month, year).orElse(null);
                if (entry != null) {
                    AttendanceSummaryDto att = buildAttendanceSummary(entry, month, year, defaultStd);
                    b.attendance(att);
                }
            }

            // Lương full — HrService đọc mealDays từ AttendanceEntry (hoặc full nếu null)
            SalaryBreakdownDto detail = hrService.getSalaryBreakdownForUser(user.getId(), month, year);
            // (10/2026) Attach "Thưởng hỗ trợ giao hàng" cho NV KT/KD nào có km/trips
            //   + gắn luôn DriverMonthDto vào b.driver(...) để FE hiện card
            //   "Chi tiết số km theo ngày" cho NV hỗ trợ giao hàng.
            DriverMonthDto supportDm = attachDriverSupportBonusIfAny(user, month, year, detail);
            if (supportDm != null) b.driver(supportDm);
            // (10/2026) Attach "Thưởng hỗ trợ bán hàng" cho NV ko thuộc SALES
            // nhưng có tạo đơn trong tháng (NV kho, trưởng xưởng kiêm bán, …).
            attachSalesSupportBonusIfAny(user, month, year, dept, b);
            b.salaryDetail(detail);

            // Thưởng doanh thu: chỉ hiển thị khi đã PUBLISHED.
            // (10/2026) Dùng CẢ bonusCalcStatus=PUBLISHED LẪN bonusFinalized=true —
            // trong giai đoạn migrate có thể 2 cờ không đồng bộ (OWNER đã bấm
            // Hoàn tất xong, dữ liệu cũ chỉ có bonusFinalized nhưng bonusCalcStatus
            // = NONE, hoặc ngược lại). Lấy OR để NV luôn thấy phiếu khi đã Public.
            long bonusAmount = 0L;
            boolean bonusPublished = sheet != null && (
                    sheet.isBonusFinalized()
                            || sheet.getBonusCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED
            );
            if (bonusPublished) {
                Long bonus = officeBonusResultRepo.sumBonusForUser(month, year, dept, user.getId());
                bonusAmount = bonus != null ? bonus : 0L;

                // (10/2026) Thêm chi tiết cho card "Thưởng doanh thu" trên phiếu:
                //   KT: pool phòng = collected + waived EX-VAT toàn phòng
                //   KD: doanh thu cá nhân (thực thu EX-VAT) + hold cá nhân
                java.time.YearMonth ymBonus = java.time.YearMonth.of(year, month);
                long startMs = ymBonus.atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
                long endMs   = ymBonus.plusMonths(1).atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();

                // (10/2026) WAREHOUSE: attach warehouse_bonus nếu sheet đã PUBLISHED
                if (dept == PayrollDepartment.WAREHOUSE && bonusPublished) {
                    WarehouseBonus wb = warehouseBonusRepo
                            .findByUser_IdAndMonthAndYear(user.getId(), month, year)
                            .orElse(null);
                    if (wb != null && wb.getAmount() != null && wb.getAmount() > 0) {
                        bonusAmount = wb.getAmount();
                    }
                }

                // (10/2026) Attach personal target + achieved % cho MỌI NV có target set
                if (dept == PayrollDepartment.SALES) {
                    SalesBonusOverride ov = salesBonusOverrideRepo
                            .findByUser_IdAndMonthAndYear(user.getId(), month, year)
                            .orElse(null);
                    if (ov != null && ov.getPersonalTarget() != null && ov.getPersonalTarget() > 0) {
                        b.salesPersonalTarget(ov.getPersonalTarget());
                        b.salesAchievedPercent(ov.getAchievedPercent());
                    }
                }

                if (dept == PayrollDepartment.ACCOUNTING) {
                    java.math.BigDecimal poolCollected = nzBd(paymentTransactionRepo.sumRevenueAllOrders(startMs, endMs));
                    java.math.BigDecimal poolWaived    = nzBd(orderRepository.sumWaivedAmountCreatedInRange(startMs, endMs));
                    b.officeBonusPoolTotal(poolCollected.add(poolWaived)
                            .setScale(0, java.math.RoundingMode.HALF_UP).longValue());
                } else { // SALES
                    // (10/2026) Phân biệt rõ 3 chỉ số:
                    //   Doanh thu tháng   = Σ (final − vat) đơn tạo trong tháng (EX-VAT)
                    //   Hold              = (final − paid) × netRatio của đơn chưa chốt
                    //   Chia hoa hồng     = Σ thực thu EX-VAT (payment_transaction)
                    // Trước đây revenue = chia hoa hồng → UI hiển thị sai (dùng cùng
                    // 1 giá trị cho 2 dòng khác nhau).
                    java.math.BigDecimal personalRev   = nzBd(orderRepository.sumFinalAmountCreatedInRangeByUser(startMs, endMs, user.getId()));
                    java.math.BigDecimal personalHold  = nzBd(orderRepository.sumHoldAmountCreatedInRangeByUser(startMs, endMs, user.getId()));
                    java.math.BigDecimal personalChia  = nzBd(paymentTransactionRepo.sumRevenueByUser(startMs, endMs, user.getId()));
                    b.officeBonusPersonalRevenue(personalRev
                            .setScale(0, java.math.RoundingMode.HALF_UP).longValue());
                    b.officeBonusPersonalHold(personalHold
                            .setScale(0, java.math.RoundingMode.HALF_UP).longValue());
                    b.officeBonusPersonalChiaHoaHong(personalChia
                            .setScale(0, java.math.RoundingMode.HALF_UP).longValue());
                }
            }
            b.kpiBonus(bonusAmount);

            long base      = detail != null ? nz(detail.getBaseSalary()) : 0L;
            long allowance = detail != null ? nz(detail.getAllowance())   : 0L;
            long fixedBonus = detail != null ? nz(detail.getEffectiveBonus()) : 0L;
            long netPay    = detail != null ? nz(detail.getNetSalary())   : 0L;

            return b.status(detail != null ? "READY" : "NO_SALARY")
                    .baseSalary(base)
                    .salaryByAttendance(base)   // full lương, không prorate theo công
                    .allowance(allowance)
                    .fixedBonus(fixedBonus)
                    .totalPay(netPay + bonusAmount)
                    .build();
        }

        // ── Bộ phận Tài xế: không có chấm công, thay bằng km theo ngày ────────
        // Lương cơ bản hiện ngay khi finalized; xăng + thưởng chỉ hiện khi kpiFinalized.
        //
        // [FIX 2026] Đổi từ hrService.getSalaryBreakdownForUser() sang
        //   driverSalaryDetail() để phiếu lương của TÀI XẾ có đủ:
        //     · Phụ cấp cơm trưa (30k × ngày điểm danh ODO)
        //     · Tiền xăng + Thưởng đơn hàng gộp thành "Thưởng KPI — đạt 100%"
        //     · Card chi tiết Thưởng KPI (gas/xe máy/xe tải) render dưới phiếu.
        //   Trước khi KPI finalized: gas/orderBonus vẫn bằng 0 (do gasPrice / bonus
        //   unit price = null → driverKpi100 = 0), driver chỉ thấy base + meal.
        //   Sau khi KPI finalized: đầy đủ như trên.
        AttendanceSummaryDto attendance = null;
        // (10/2026) Phase 5 refactor: DRIVER giờ attendanceBased=true (lương theo
        // chấm công chung) nên nhánh !attendanceBased không chạy cho DRIVER nữa
        // → bonus xăng + đơn hàng + driverOrderBonusDetail không được set →
        // phiếu NV tài xế hiển thị Thưởng = 0 dù đã Public. Bổ sung "|| DRIVER"
        // để DRIVER vẫn vào nhánh này (dùng driverSalaryDetail), nhưng vẫn dùng
        // AttendanceEntry CHUNG cho lương cơ bản (xem driverSalaryDetail đã bọc
        // sẵn gọi hrService.previewSalary bên trong).
        if (!dept.isAttendanceBased() || dept == PayrollDepartment.DRIVER) {
            // (10/2026) Driver dùng chấm công chung (Phase 5) — build attendance
            // để FE render AttendanceCalendar bên cạnh DriverCalendar. User muốn
            // "Chi tiết ngày công" ở mọi role.
            if (dept.isAttendanceBased()) {
                double defaultStd = sheet.getStandardDays() != null
                        ? sheet.getStandardDays()
                        : standardWorkdaysOf(month, year);
                AttendanceEntry entry = entryRepo.findByUserAndPeriod(user.getId(), month, year).orElse(null);
                if (entry != null) {
                    b.attendance(buildAttendanceSummary(entry, month, year, defaultStd));
                }
            }

            DriverMonthDto dm = driverKmService.monthOf(user, month, year);
            fillDriverSalary(dm, sheet, true);  // true = chỉ tính bonus khi kpiFinalized

            // Nếu KPI chưa hoàn tất → truyền sheet KHÔNG có driverGasPrice/bonus
            // sang driverSalaryDetail để driverKpi100 = 0. driverSalaryDetail đã tự
            // đọc từ sheetRepo nên nếu OWNER chưa nhập giá xăng thì driverKpi100 = 0
            // tự nhiên — không cần rẽ nhánh.
            SalaryBreakdownDto driverDetail;
            try {
                driverDetail = driverSalaryDetail(user.getId(), month, year);
            } catch (Exception ex) {
                log.warn("[MyPayslip] Không lấy được driverSalaryDetail cho user {}: {}",
                        user.getId(), ex.getMessage());
                driverDetail = hrService.getSalaryBreakdownForUser(user.getId(), month, year);
            }

            // (10/2026) Driver flow mới: dùng bonusCalcStatus=PUBLISHED thay vì
            // kpiFinalized. Vẫn OR với isKpiFinalized / isBonusFinalized cho tương
            // thích dữ liệu cũ. Khi CHƯA Public → ẩn Thưởng KPI 100% (gas +
            // orderBonus) trên phiếu, giữ nguyên lương cơ bản + phụ cấp cơm.
            boolean driverBonusPublished = sheet.isBonusFinalized()
                    || sheet.isKpiFinalized()
                    || sheet.getBonusCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED;
            if (driverDetail != null && !driverBonusPublished) {
                driverDetail.setDriverOrderBonus(0L);
                driverDetail.setDriverOrderBonusDetail(null);
                // Trừ phần bonus (gas + orderBonus) ra khỏi effectiveBonus và net
                // để nhân viên chưa thấy trước khi OWNER chốt.
                Long curBonus = driverDetail.getEffectiveBonus();
                Long curBonusInput = driverDetail.getBonus();
                if (curBonus != null && curBonus > 0) {
                    long hide = curBonus;
                    driverDetail.setEffectiveBonus(0L);
                    driverDetail.setEffectiveBonusKpiOnly(0L);
                    driverDetail.setBonus(0L);
                    if (driverDetail.getNetSalary() != null)
                        driverDetail.setNetSalary(Math.max(0L, driverDetail.getNetSalary() - hide));
                    if (driverDetail.getNetSalaryExact() != null)
                        driverDetail.setNetSalaryExact(Math.max(0L, driverDetail.getNetSalaryExact() - hide));
                }
                // curBonusInput không cần dùng — comment để giữ ngữ nghĩa rõ ràng
                if (curBonusInput != null) { /* no-op */ }
            }

            b.salaryDetail(driverDetail);

            long driverBaseSalary = driverDetail != null ? nz(driverDetail.getBaseSalary()) : 0L;
            long driverAllowance  = driverDetail != null ? nz(driverDetail.getAllowance())  : 0L;
            long driverBonus      = driverDetail != null ? nz(driverDetail.getEffectiveBonus()) : 0L;
            long driverNet        = driverDetail != null ? nz(driverDetail.getNetSalary())  : 0L;

            // (10/2026) Set kpiBonus + kpiStatus cho driver branch. Ưu tiên
            // driverOrderBonusDetail.totalAmount; fallback dùng effectiveBonus
            // (= tổng xăng + bonus) khi detail chưa populate — chẳng hạn khi
            // driverSalaryDetail trả breakdown gộp sẵn trong effectiveBonus.
            long driverTotalBonus = 0L;
            if (driverDetail != null) {
                if (driverDetail.getDriverOrderBonusDetail() != null
                        && driverDetail.getDriverOrderBonusDetail().getTotalAmount() != null
                        && driverDetail.getDriverOrderBonusDetail().getTotalAmount() > 0) {
                    driverTotalBonus = driverDetail.getDriverOrderBonusDetail().getTotalAmount();
                } else if (driverDetail.getEffectiveBonus() != null) {
                    driverTotalBonus = driverDetail.getEffectiveBonus();
                }
            }
            b.kpiBonus(driverTotalBonus);
            b.kpiStatus(driverBonusPublished ? "READY" : "PENDING");

            // (10/2026) PHIẾU LƯƠNG chỉ gồm: Lương CB + Phụ cấp + Thưởng IMPORT.
            // Thưởng tiền xăng + đơn hàng tài xế đã hiển thị ở CARD RIÊNG
            // "Thưởng giao hàng tháng N" → phải TRỪ driverKpi100 khỏi netSalary
            // + effectiveBonus để không bị đếm 2 lần.
            if (driverDetail != null && driverBonusPublished && driverTotalBonus > 0) {
                Long eff = driverDetail.getEffectiveBonus();
                if (eff != null && eff >= driverTotalBonus) {
                    driverDetail.setEffectiveBonus(eff - driverTotalBonus);
                    if (driverDetail.getEffectiveBonusKpiOnly() != null) {
                        driverDetail.setEffectiveBonusKpiOnly(
                                Math.max(0L, driverDetail.getEffectiveBonusKpiOnly() - driverTotalBonus));
                    }
                }
                Long bonusInput = driverDetail.getBonus();
                if (bonusInput != null && bonusInput >= driverTotalBonus) {
                    driverDetail.setBonus(bonusInput - driverTotalBonus);
                }
                if (driverDetail.getNetSalary() != null) {
                    driverDetail.setNetSalary(Math.max(0L, driverDetail.getNetSalary() - driverTotalBonus));
                }
                if (driverDetail.getNetSalaryExact() != null) {
                    driverDetail.setNetSalaryExact(Math.max(0L, driverDetail.getNetSalaryExact() - driverTotalBonus));
                }
            }

            return b.driver(dm)
                    .status(driverDetail != null ? "READY" : "NO_SALARY")
                    .baseSalary(driverBaseSalary)
                    .salaryByAttendance(driverBaseSalary) // tài xế full lương cơ bản
                    .allowance(driverAllowance)
                    .fixedBonus(driverBonus)
                    .totalPay(driverNet)  // driverNet đã bao gồm base + meal + KPI 100%
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

        // ── Thưởng KPI sản xuất: CHỈ bộ phận Xưởng, CHỈ khi KPI đã hoàn tất ───
        long kpiAmount = 0L;
        if (dept.isKpiBonus() && sheet.isKpiFinalized()) {
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
        // (10/2026) Attach "Thưởng hỗ trợ giao hàng" cho NV FACTORY/WAREHOUSE
        // nào có km/trips trong tháng (sau khi Thưởng Tài xế đã Public).
        //   + gắn luôn DriverMonthDto vào b.driver(...) để FE hiện card
        //   "Chi tiết số km theo ngày" cho NV hỗ trợ giao hàng.
        DriverMonthDto supportDm = attachDriverSupportBonusIfAny(user, month, year, detail);
        if (supportDm != null) b.driver(supportDm);
        attachSalesSupportBonusIfAny(user, month, year, dept, b);
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

        // (10/2026) Load ngày lễ của tháng + map tên ngày lễ
        Set<LocalDate> holidayDates;
        Map<LocalDate, String> holidayNames = new HashMap<>();
        try {
            holidayDates = holidayService.datesOfMonth(month, year);
            for (com.nhatnam.server.entity.Holiday h : holidayService.entitiesOfMonth(month, year)) {
                if (h.getDate() != null) holidayNames.put(h.getDate(), h.getName());
            }
        } catch (Exception ex) {
            log.warn("[Attendance] Không load được ngày lễ cho {}/{}: {}", month, year, ex.getMessage());
            holidayDates = java.util.Set.of();
        }

        // (10/2026) Load các phiếu nghỉ phép ĐÃ DUYỆT của NV trong tháng.
        //   • Có FULL_DAY_OFF duyệt → ngày đó = LEAVE (hồng).
        //   • Nếu ngày thường không chấm công + KHÔNG có phiếu duyệt → UNPAID (xám).
        Set<Integer> approvedFullDayOff = new HashSet<>();
        Set<Integer> approvedHalfDay    = new HashSet<>();
        if (entry != null && entry.getUser() != null) {
            try {
                for (com.nhatnam.server.entity.AttendanceLeaveRequest lr :
                        leaveRepo.findByUser_IdAndYearAndMonth(entry.getUser().getId(), year, month)) {
                    if (lr == null || !lr.isEffective() || lr.getDay() == null) continue;
                    com.nhatnam.server.enumtype.AttendanceExceptionType t = lr.getType();
                    if (t == null) continue;
                    if (t.isFullDayCredit()) approvedFullDayOff.add(lr.getDay());
                    else if (t == com.nhatnam.server.enumtype.AttendanceExceptionType.HALF_DAY_MORNING_OFF
                            || t == com.nhatnam.server.enumtype.AttendanceExceptionType.HALF_DAY_AFTERNOON_OFF)
                        approvedHalfDay.add(lr.getDay());
                }
            } catch (Exception ex) {
                log.warn("[Attendance] Không load được phiếu nghỉ: {}", ex.getMessage());
            }

            // (10/2026) BỔ SUNG: ngoài AttendanceLeaveRequest (parse từ file
            // chấm công), còn phải cộng cả EmployeeRequest (phiếu HR tự tạo)
            // ĐÃ DUYỆT. Trước đây 2 luồng tách rời khiến "Phiếu nghỉ phép"
            // trên page HR duyệt rồi mà UI công của NV vẫn hiện "Nghỉ không
            // lương" + lương ngày đó = 0.
            try {
                var dayEffects = employeeRequestService.effectsForUser(
                        entry.getUser().getId(), month, year);
                for (var e : dayEffects.entrySet()) {
                    int day = e.getKey();
                    var eff = e.getValue();
                    if (eff == null || eff.zeroDay()) continue;
                    if (eff.fullDayCredit())    approvedFullDayOff.add(day);
                    else if (eff.minCredit() > 0) approvedHalfDay.add(day);
                }
            } catch (Exception ex) {
                log.warn("[Attendance] Không load được EmployeeRequest DayEffect: {}", ex.getMessage());
            }
        }

        List<AttendanceDayDto> days = new ArrayList<>(len);
        for (int d = 1; d <= len; d++) {
            LocalDate date = LocalDate.of(year, month, d);
            int weekday = date.getDayOfWeek() == DayOfWeek.SUNDAY ? 8
                    : date.getDayOfWeek().getValue() + 1;   // T2=2 … T7=7, CN=8
            boolean isHoliday = holidayDates.contains(date);

            Map<String, Object> r = daily.get(d);

            AttendanceDayDto.AttendanceDayDtoBuilder db = AttendanceDayDto.builder()
                    .day(d).weekday(weekday)
                    .weekdayLabel(r != null && r.get("w") != null
                            ? str(r.get("w")) : defaultWeekdayLabel(weekday))
                    .value(0.0).type("OFF");

            if (r != null) {
                double value = r.get("v") instanceof Number n ? n.doubleValue() : 0.0;
                String type = str(r.get("t"));
                String inStr  = str(r.get("in"));
                String outStr = str(r.get("out"));

                // (10/2026) Phase 7 writer (CompanyAttendanceService.serializeDaily)
                // KHÔNG ghi "v" / "t" vào dailyJson, nên các nhân viên KT/KD/XSX
                // dù có chấm công vào/ra vẫn hiển thị công=0 và không có màu.
                // Fallback: tính "value" + "type" từ in/out ngay tại đây.
                //
                //   Rule (theo yêu cầu người dùng):
                //     • Đi đúng giờ, về đúng giờ (chênh ≤ 5 phút)  → WORK, value=1
                //       (parttime → value=0.5, type=WORK → vẫn tô xanh lá)
                //     • Trễ hoặc về sớm > 5 phút                   → HALF, value=0.5 → vàng
                //     • Không có in và out                         → giữ OFF
                //
                //   LEAVE / UNPAID / BUSINESS / EXCEPTION sẽ KHÔNG bị override vì
                //   dailyJson ở các case đó có sẵn "t" (parser đọc ký hiệu P/K/CT).
                // Suy ra type từ ký hiệu "sym" trước (nghỉ phép / không phép ko có in/out)
                if ((type == null || type.isBlank())) {
                    String sym = str(r.get("sym"));
                    if (sym != null && !sym.isBlank()) {
                        String up = sym.trim().toUpperCase();
                        if (up.equals("P") || up.equals("CP") || up.contains("PHÉP") || up.contains("PHEP")) {
                            type = "LEAVE";
                            if (value <= 0) value = 1.0;
                        } else if (up.equals("K") || up.equals("KP") || up.contains("KHÔNG PHÉP") || up.contains("KHONG PHEP")) {
                            type = "UNPAID";
                        } else if (up.equals("CT") || up.contains("CÔNG TÁC") || up.contains("CONG TAC")) {
                            type = "BUSINESS";
                            if (value <= 0) value = 1.0;
                        }
                    }
                }

                if ((type == null || type.isBlank()) && value <= 0
                        && (inStr != null || outStr != null)) {
                    boolean isPartTime = entry != null
                            && Boolean.TRUE.equals(entry.getPartTime());
                    LocalTime expectedStart = SHIFT_START;
                    LocalTime expectedEnd   = isPartTime ? PART_TIME_END : SHIFT_END;

                    LocalTime inTime  = parseHHmmSafe(inStr);
                    LocalTime outTime = parseHHmmSafe(outStr);

                    int lateMin  = (inTime  != null && inTime.isAfter(expectedStart))
                            ? (int) java.time.Duration.between(expectedStart, inTime).toMinutes()
                            : 0;
                    int earlyMin = (outTime != null && outTime.isBefore(expectedEnd))
                            ? (int) java.time.Duration.between(outTime, expectedEnd).toMinutes()
                            : 0;

                    // Có mặt (ít nhất 1 đầu chấm công) → tính công
                    //   value = 1 - (lateMin + earlyMin) / shiftMinutes
                    //   TRUNCATE 2 chữ số thập phân (KHÔNG làm tròn lên).
                    //   VD: trễ 43' → 1 - 43/480 = 0.9104... → 0.91
                    //       về sớm 8' → 1 - 8/480 = 0.9833... → 0.98
                    //   Full công tối đa 1.0 (parttime 0.5).
                    final int GRACE = 5;
                    double shiftMin = isPartTime
                            ? java.time.Duration.between(SHIFT_START, PART_TIME_END).toMinutes()
                            : (java.time.Duration.between(SHIFT_START, SHIFT_END).toMinutes()
                            - java.time.Duration.between(LUNCH_START, LUNCH_END).toMinutes());
                    double maxVal = isPartTime ? 0.5 : 1.0;

                    if (inTime != null && outTime != null
                            && lateMin <= GRACE && earlyMin <= GRACE) {
                        type  = "WORK";
                        value = maxVal;
                    } else if (inTime != null && outTime != null) {
                        // Trừ công theo tỷ lệ phút trễ + về sớm (truncate 2dp)
                        double missing = (lateMin + earlyMin) / shiftMin;
                        double raw = maxVal * Math.max(0.0, 1.0 - missing);
                        // Truncate (floor) tới 2 chữ số
                        value = Math.floor(raw * 100.0) / 100.0;
                        type  = value >= maxVal ? "WORK" : "HALF";
                    } else {
                        // Chỉ có 1 đầu chấm công → nửa công
                        type  = "HALF";
                        value = Math.min(maxVal, 0.5);
                    }
                }

                // Fallback cuối: nếu vẫn chưa có type, suy ra từ value
                if (type == null || type.isBlank())
                    type = value >= 1 ? "WORK" : value > 0 ? "HALF" : "OFF";

                db.value(value).type(type)
                        .checkIn(inStr)
                        .checkOut(outStr)
                        .lateMinutes(r.get("late")  instanceof Number n2 ? n2.intValue() : null)
                        .earlyMinutes(r.get("early") instanceof Number n3 ? n3.intValue() : null)
                        .workedMinutes(r.get("wk")   instanceof Number n4 ? n4.intValue() : null)
                        .requiredMinutes(r.get("rq") instanceof Number n5 ? n5.intValue() : null)
                        .exception(str(r.get("ex")))
                        .windowStart(str(r.get("ws")))
                        .windowEnd(str(r.get("we")))
                        .sessions(readSessions(r.get("ss")));
            }

            // (10/2026) Phân loại ngày KHÔNG chấm công theo phiếu nghỉ phép.
            //   - Có phiếu FULL_DAY_OFF đã duyệt → LEAVE (hồng), value=1 (ko trừ lương).
            //   - Có phiếu HALF_DAY đã duyệt → LEAVE (hồng), value=0.5.
            //   - Không có phiếu, và là NGÀY THƯỜNG (không CN, không lễ) →
            //     UNPAID (xám) → "nghỉ không phép, có trừ lương".
            //   - CN / Lễ sẽ được override bởi logic phía dưới (SUNDAY / HOLIDAY).
            {
                // Lấy value / type HIỆN TẠI từ builder (vì có thể vừa được set phía trên)
                AttendanceDayDto preview = db.build();
                boolean noPunch = (preview.getCheckIn() == null || preview.getCheckIn().isBlank())
                        && (preview.getCheckOut() == null || preview.getCheckOut().isBlank());
                boolean currentlyOff = "OFF".equals(preview.getType())
                        || (preview.getValue() == null || preview.getValue() <= 0);

                // (10/2026) Parttime: 1 công hệ thống = 0.5 — cap value để
                // UI không hiện "1 công" cho ngày paid-leave của parttime.
                boolean isPartTime = entry != null && Boolean.TRUE.equals(entry.getPartTime());
                double maxCreditPerDay = isPartTime ? 0.5 : 1.0;

                if (noPunch && currentlyOff) {
                    boolean isSunday = date.getDayOfWeek() == DayOfWeek.SUNDAY;
                    if (approvedFullDayOff.contains(d)) {
                        db.type("LEAVE").value(maxCreditPerDay);
                    } else if (approvedHalfDay.contains(d)) {
                        db.type("LEAVE").value(Math.min(0.5, maxCreditPerDay));
                    } else if (!isSunday && !isHoliday) {
                        // Ngày thường không chấm công + không có phiếu nghỉ → nghỉ ko phép
                        db.type("UNPAID").value(0.0);
                    }
                } else if (!noPunch && approvedFullDayOff.contains(d)) {
                    // (10/2026 — Issue 2) Có chấm công + có phiếu nghỉ phép
                    // CẢ NGÀY/NỬA NGÀY đã duyệt bù vào phần thiếu → ngày này
                    // đủ công của ngày đó (fulltime 1.0, parttime 0.5), UI tô
                    // xanh WORK thay vì vàng (nửa công).
                    double curVal = preview.getValue() != null ? preview.getValue() : 0.0;
                    if (curVal < maxCreditPerDay) db.value(maxCreditPerDay);
                    db.type("WORK");
                }
            }

            // (10/2026) Override khi là NGÀY LỄ (áp dụng cho mọi NV):
            //   - type = HOLIDAY → FE tô xanh dương
            //   - value = 1.0 (full công — luật lao động), KHÔNG trừ lương
            //   - exception = tên ngày lễ (nếu có) hoặc "Nghỉ lễ theo luật lao động"
            //   - Giữ nguyên checkIn/checkOut nếu NV vẫn đi làm (tăng ca lễ)
            if (isHoliday) {
                db.type("HOLIDAY");
                // Chỉ bump value lên 1.0 nếu NV ko đi làm; nếu đi làm (value>0),
                // giữ value hiện tại (có thể >1 do nhân hệ số lễ).
                Double curVal = null;
                if (r != null && r.get("v") instanceof Number n) curVal = n.doubleValue();
                if (curVal == null || curVal <= 0) db.value(1.0);
                String name = holidayNames.get(date);
                db.exception(name != null && !name.isBlank()
                        ? "Nghỉ lễ: " + name
                        : "Nghỉ lễ theo luật lao động");
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
                .leaveMinutesUsed(entry != null ? entry.getLeaveMinutesUsed() : null)
                .leaveBalanceMinutesAfter(entry != null ? entry.getLeaveBalanceMinutesAfter() : null)
                .days(days)
                .build();
    }

    @SuppressWarnings("unchecked")
    /**
     * Parse "HH:mm" hoặc "HH:mm:ss" → LocalTime; null-safe, không throw.
     * Dùng trong buildAttendanceSummary để tính công từ chuỗi in/out.
     */
    private static LocalTime parseHHmmSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            // LocalTime.parse hỗ trợ cả "HH:mm" và "HH:mm:ss"
            return LocalTime.parse(s.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

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
                        .kpiFinalized(false)
                        .hasKpiBonus(true)
                        .hasSalesBonus(department == PayrollDepartment.SALES || department == PayrollDepartment.ACCOUNTING)
                        .bonusFinalized(false)
                        // SALES, ACCOUNTING, WAREHOUSE, DRIVER: luôn canFinalize = true (xem
                        // chú thích ở toSheetDto — Phase 7 đã gộp upload về company sheet).
                        .canFinalize(!department.isAttendanceBased()
                                || department == PayrollDepartment.SALES
                                || department == PayrollDepartment.ACCOUNTING
                                || department == PayrollDepartment.WAREHOUSE
                                || department == PayrollDepartment.DRIVER)
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
                // SALES, ACCOUNTING, WAREHOUSE, DRIVER: luôn canFinalize = true.
                //
                // FIX (10/2026 — Phase 7): sau khi gộp upload về CompanyPayrollPanel,
                // file chấm công KHÔNG còn gắn theo bộ phận nữa — chỉ 1 file duy
                // nhất gắn vào "company sheet" (dept = FACTORY sentinel). Nghĩa là
                // sheet DRIVER / ACCOUNTING / SALES / WAREHOUSE per-dept KHÔNG BAO
                // GIỜ có filePath → điều kiện cũ chặn tab DRIVER không load được
                // bảng lương xem trước (triệu chứng: trang Tài xế chỉ hiện km +
                // thưởng đơn, không có "Phiếu lương — Tài xế").
                //
                // Lifecycle "Hoàn tất / Mở lại" nay chạy ở CompanyPayrollPanel đầu
                // trang nên cờ canFinalize ở đây chỉ còn một tác dụng duy nhất:
                // cho FE biết CÓ nên gọi departmentPayroll để nạp bảng xem trước
                // hay không. Trả true cho mọi dept trừ FACTORY — FACTORY vẫn cần
                // file (và filePath của company sheet == filePath của sheet này
                // vì cùng là FACTORY sentinel).
                .canFinalize(!d.isAttendanceBased() || s.getFilePath() != null
                        || d == PayrollDepartment.SALES || d == PayrollDepartment.ACCOUNTING
                        || d == PayrollDepartment.WAREHOUSE
                        || d == PayrollDepartment.DRIVER)
                .kpiFinalized(s.isKpiFinalized())
                .kpiFinalizedAt(s.getKpiFinalizedAt())
                .kpiFinalizedByName(s.getKpiFinalizedByName())
                // Tất cả bộ phận đều có thể có KPI/bonus
                .hasKpiBonus(true)
                // Bước 3 (thưởng doanh thu) chỉ dành cho SALES và ACCOUNTING
                .bonusFinalized(s.isBonusFinalized())
                .bonusFinalizedAt(s.getBonusFinalizedAt())
                .bonusFinalizedByName(s.getBonusFinalizedByName())
                // (10/2026) State 3 bước: NONE / CALCULATED / PUBLISHED
                .bonusCalcStatus(s.getBonusCalcStatus() != null ? s.getBonusCalcStatus().name() : "NONE")
                .bonusCalculatedAt(s.getBonusCalculatedAt())
                .bonusCalculatedByName(s.getBonusCalculatedByName())
                .bonusPublishedAt(s.getBonusPublishedAt())
                .bonusPublishedByName(s.getBonusPublishedByName())
                .hasSalesBonus(d == PayrollDepartment.SALES || d == PayrollDepartment.ACCOUNTING)
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

            // ── Xác định trạng thái part-time: ưu tiên snapshot cũ → hiện tại ──
            //   Khi tính LẠI tháng cũ (VD T8), nếu đã có snapshot partTime trong
            //   AttendanceEntry cũ thì dùng, không lấy từ EmployeeSalary hiện tại.
            boolean isPartTime = false;
            AttendanceEntry existingEntry = entryRepo.findByUserAndPeriod(u.getId(), month, year).orElse(null);
            if (existingEntry != null && existingEntry.getPartTime() != null) {
                isPartTime = existingEntry.getPartTime();
            } else {
                // Lần đầu tính: lấy từ EmployeeSalary hiện tại
                var salaries = salaryRepo.findAllByUserIdOrderByCreatedAtDesc(u.getId());
                if (!salaries.isEmpty() && Boolean.TRUE.equals(salaries.get(0).getPartTime())) {
                    isPartTime = true;
                }
            }

            AttendanceEntry entry = buildEntry(sheet, u, m.block(), month, year, standardDays,
                    deptExceptions,
                    leavesByUser.getOrDefault(u.getId(), Map.of()),
                    requestEffects.getOrDefault(u.getId(), Map.of()),
                    warnings, isPartTime);
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
    // ── HẰNG SỐ NGÀY PHÉP ──────────────────────────────────────────────────────

    /** Số phút trong 1 ngày phép (8 tiếng = 480 phút). */
    public static final int LEAVE_MINUTES_PER_DAY = 480;

    /**
     * Du di trễ/sớm (phút) cho bộ phận FACTORY và ACCOUNTING.
     * Trong phạm vi này (5 phút trễ + 5 phút sớm) vẫn tính đủ công,
     * không trừ ngày phép.
     */
    public static final int GRACE_MINUTES = 5;

    /**
     * BỘ PHẬN ĐƯỢC ÁP DỤNG LOGIC TRỪ VÀO NGÀY PHÉP trước khi trừ lương.
     * (FACTORY và ACCOUNTING)
     */
    /**
     * TRƯỚC Phase 6: chỉ FACTORY và ACCOUNTING trừ trễ/sớm vào phép.
     *
     * <p>PHASE 6 (10/2026): mở rộng cho CẢ 5 bộ phận — tất cả đều dùng quỹ
     * phép để bù trừ TRỄ. Tuy nhiên, các bộ phận NGOÀI XƯỞNG (Kế toán, Sales,
     * Kho, Tài xế) KHÔNG TRỪ về sớm nữa (chỉ trừ về sớm cho XƯỞNG). Xem
     * logic phân biệt trong vòng lặp `buildEntry` — biến {@code deductEarly}.
     */
    private boolean isLeaveFirstDept(PayrollDepartment dept) {
        return dept != null;   // Phase 6: tất cả bộ phận đều áp quỹ phép cho TRỄ.
    }

    /**
     * PHASE 6: Chỉ XƯỞNG SX trừ về sớm. Kế toán / Sales / Kho / Tài xế không
     * còn bị trừ về sớm (không trừ phép, không trừ lương).
     */
    private boolean deductEarlyLeave(PayrollDepartment dept) {
        return dept == PayrollDepartment.FACTORY;
    }

    private AttendanceEntry buildEntry(AttendanceSheet sheet, User user, EmployeeBlock block,
                                       int month, int year, double standardDays,
                                       Map<Integer, AttendanceException> deptExceptions,
                                       Map<Integer, AttendanceLeaveRequest> myLeaves,
                                       Map<Integer, com.nhatnam.server.service.hr.EmployeeRequestService.DayEffect> myEffects,
                                       List<String> warnings, boolean isPartTime) {
        List<Map<String, Object>> daily = new ArrayList<>();
        double actualDays = 0;
        int presentDays = 0;
        int totalLate = 0, totalEarly = 0, totalWorked = 0;
        int lateDays = 0, earlyDays = 0;

        // Công của các ngày nghỉ CÓ LƯƠNG và ngày nghỉ KHÔNG LƯƠNG được đếm
        // riêng để hiện lên phiếu lương, thay vì trộn hết vào actualDays.
        double paidLeaveDays = 0, unpaidLeaveDays = 0, mealDays = 0;

        // ── NGÀY CUỐI THÁNG — tham chiếu để tự động fill giờ ra ─────────────
        int lastDayOfMonth = java.time.YearMonth.of(year, month).lengthOfMonth();

        // ── NGÀY PHÉP CÒN LẠI (phút) — dùng để bù trừ trễ/sớm cho FACTORY/ACCOUNTING ──
        PayrollDepartment dept = deptResolver.departmentOf(user);
        boolean applyLeaveFirst = isLeaveFirstDept(dept);
        // PHASE 6: Chỉ FACTORY trừ về sớm. Các bộ phận khác bỏ qua
        // số phút về sớm — không trừ phép, không trừ lương.
        boolean deductEarly = deductEarlyLeave(dept);

        // ── TỔNG PHÚT PHÉP GỐC (từ LeaveRequest đã duyệt) ──────────────────
        // Lấy số phút phép gốc của năm — KHÔNG trừ leaveMinutesUsed cũ.
        // leaveMinutesUsed sẽ được tính lại từ đầu trong vòng lặp bên dưới.
        // Cách này đảm bảo: mở lại → tính lại → kết quả luôn đúng, không cộng dồn.
        int leaveBalanceMinutes = 0;
        if (applyLeaveFirst) {
            try {
                com.nhatnam.server.dto.hr.EmployeeRequestDtos.LeaveBalanceDto bal =
                        employeeRequestService.leaveBalance(user.getId(), year);
                double remainingDays = bal.getRemainingDays() != null ? bal.getRemainingDays() : 0.0;
                // Giữ nguyên số phút (không làm tròn) để tránh thiệt cho nhân viên.
                // CLAMP về ≥ 0: nếu quỹ đã âm (do đơn vượt quỹ hoặc dữ liệu test cũ)
                // thì coi như hết phép — tuyệt đối không cho lần tính lương này
                // trừ THÊM âm vào leaveMinutesUsed / leaveBalanceMinutesAfter.
                leaveBalanceMinutes = (int) Math.max(0, remainingDays * LEAVE_MINUTES_PER_DAY);
            } catch (Exception e) {
                log.warn("[Attendance] Không lấy được ngày phép của {} để bù trừ: {}", user.getFullName(), e.getMessage());
            }
        }
        int leaveMinutesUsed = 0; // tổng phút phép đã dùng bù trễ/sớm — tính lại từ đầu

        for (DayRecord d : block.getDays()) {
            if (d.getDate() == null) continue;

            if (d.getDate().getMonthValue() != month || d.getDate().getYear() != year) {
                warnings.add("Nhân viên \"%s\": bỏ qua ngày %s không thuộc tháng %d/%d"
                        .formatted(block.getEmployeeName(), d.getDate(), month, year));
                continue;
            }

            // ── PHASE 6: TẤT CẢ ngày thiếu giờ ra → mặc định 17:00 ─────────
            //
            // Trước Phase 6 chỉ patch ngày CUỐI THÁNG; giờ patch cho MỌI ngày
            // nhân viên có quẹt thẻ vào nhưng quên quẹt ra. Dùng cờ
            // {@code "defaultedOut": true} trong dailyJson để UI xem chi tiết
            // highlight được "ngày này thiếu giờ ra, hệ thống fill 17:00".
            //
            // Điều kiện áp dụng:
            //   1. Là ngày đi làm (T2–T7, không phải CN)
            //   2. Không phải ngày lễ được nghỉ
            //   3. Có chấm công vào nhưng không có chấm công ra
            DayRecord effectiveD = d;
            boolean isWorkday = d.getDate().getDayOfWeek() != java.time.DayOfWeek.SUNDAY;
            boolean isHoliday = isHolidayOff(d.getDate().getDayOfMonth(), deptExceptions);
            boolean defaultedOut = false;
            if (isWorkday && !isHoliday
                    && d.hasPunch()
                    && d.firstIn() != null
                    && d.lastOut() == null) {
                LocalTime defaultOut = isPartTime ? PART_TIME_END : SHIFT_END;
                effectiveD = patchLastOut(d, defaultOut);
                defaultedOut = true;
            }

            DayPlan w = resolvePlan(effectiveD.getDate().getDayOfMonth(), deptExceptions, myLeaves, myEffects);
            DayResult r = resolveDay(effectiveD, w, isPartTime);

            // ── YÊU CẦU 1: Trừ trễ/sớm vào ngày phép trước ──
            // PHASE 6: Chỉ FACTORY trừ về sớm. Các bộ phận khác bỏ qua.
            int adjustedLate  = r.late();
            int adjustedEarly = deductEarly ? r.early() : 0;
            double adjustedValue = r.value();

            if (applyLeaveFirst && !isPartTime && r.late() + r.early() > 0
                    && !"LEAVE".equals(r.type()) && !"UNPAID".equals(r.type())) {

                // Du di 5 phút:
                //   - Trễ/sớm ≤ GRACE_MINUTES → bỏ qua hoàn toàn, không phạt.
                //   - Trễ/sớm > GRACE_MINUTES → phạt TOÀN BỘ số phút thực tế (không chỉ phần vượt).
                //     Ví dụ: trễ 8 phút → phạt đúng 8 phút (không phải 8-5=3 phút).
                int penaltyLate  = r.late()  > GRACE_MINUTES ? r.late()  : 0;
                // PHASE 6: Kế toán / Sales / Kho / Tài xế không bị phạt về sớm.
                int penaltyEarly = (deductEarly && r.early() > GRACE_MINUTES) ? r.early() : 0;
                int totalPenaltyMinutes = penaltyLate + penaltyEarly;

                // adjustedLate/Early = 0 khi trong ngưỡng du di, = số thực khi vượt
                adjustedLate  = penaltyLate;
                adjustedEarly = penaltyEarly;

                if (totalPenaltyMinutes > 0) {
                    // Trừ vào quỹ phép trước (giữ nguyên số phút, không làm tròn)
                    int fromLeave = Math.min(totalPenaltyMinutes, leaveBalanceMinutes);
                    leaveBalanceMinutes -= fromLeave;
                    leaveMinutesUsed    += fromLeave;
                    int remainPenalty    = totalPenaltyMinutes - fromLeave;

                    // Phần vượt phép → trừ ngày công (giữ nguyên số thực, không làm tròn)
                    if (remainPenalty > 0) {
                        double deductFraction = (double) remainPenalty / LEAVE_MINUTES_PER_DAY;
                        adjustedValue = Math.max(0.0, r.value() - deductFraction);
                    }
                }
            }

            actualDays  += adjustedValue;
            totalLate   += adjustedLate;
            totalEarly  += adjustedEarly;
            totalWorked += r.worked();
            if (effectiveD.hasPunch())   presentDays++;
            if (r.mealEligible())        mealDays++;
            if (adjustedLate  > 0)       lateDays++;
            if (adjustedEarly > 0)       earlyDays++;

            if ("LEAVE".equals(r.type()))  paidLeaveDays   += r.value();
            if ("UNPAID".equals(r.type())) unpaidLeaveDays += 1.0;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("d", effectiveD.getDate().getDayOfMonth());
            m.put("w", effectiveD.getWeekdayLabel());
            m.put("v", round4(adjustedValue));
            m.put("t", r.type());

            LocalTime in = effectiveD.firstIn(), out = effectiveD.lastOut();
            if (in  != null) m.put("in",  in.format(HHMM));
            if (out != null) m.put("out", out.format(HHMM));
            // PHASE 6: cờ cho UI biết giờ ra là FILL mặc định, không phải chấm thật.
            if (defaultedOut) m.put("defaultedOut", true);
            // PHASE 6b: cờ WFH để UI tô màu riêng (xanh lá) và show badge "Làm ở nhà".
            //   Lấy từ DayEffect — merge() đã set wfh=true khi có đơn WORK_FROM_HOME
            //   APPROVED_PAID cho ngày này.
            {
                var _effect = myEffects.get(effectiveD.getDate().getDayOfMonth());
                if (_effect != null && _effect.wfh()) m.put("wfh", true);
            }
            if (adjustedLate  > 0) m.put("late",   adjustedLate);
            if (adjustedEarly > 0) m.put("early",  adjustedEarly);
            if (r.late()  != adjustedLate)  m.put("rawLate",  r.late());
            if (r.early() != adjustedEarly) m.put("rawEarly", r.early());
            if (r.worked()   > 0) m.put("wk", r.worked());
            if (r.required() > 0) m.put("rq", r.required());
            if (r.note()   != null) m.put("ex", r.note());
            m.put("ws", w.start().format(HHMM));
            m.put("we", w.end().format(HHMM));
            if (w.deduction() > 0) m.put("dd", w.deduction());

            if (effectiveD.getSessions() != null && !effectiveD.getSessions().isEmpty()) {
                List<Map<String, Object>> ss = new ArrayList<>();
                for (AttendanceExcelParser.Session sess : effectiveD.getSessions()) {
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
                .actualDays(round4(actualDays))
                .presentDays(presentDays)
                .mealDays(round2(mealDays))
                .leaveDays(round2(paidLeaveDays))
                .unpaidDays(round2(unpaidLeaveDays))
                .leaveMinutesUsed(leaveMinutesUsed)
                .leaveBalanceMinutesAfter(leaveBalanceMinutes) // phút phép còn lại sau kỳ
                .overtimeHours(0.0)
                .lateCount(lateDays)
                .lateMinutes(totalLate)
                .earlyCount(earlyDays)
                .earlyMinutes(totalEarly)
                .machineTotalHours(block.getTotalHours())
                .machineTotalWorkUnits(block.getTotalWorkUnits())
                .dailyJson(dailyJson)
                .partTime(isPartTime)
                .build();
    }

    /**
     * Tạo bản sao của DayRecord với giờ ra (lastOut) được patch về giờ chỉ định.
     * Dùng cho ngày cuối tháng khi file chấm công thiếu giờ ra.
     */
    private DayRecord patchLastOut(DayRecord original, LocalTime defaultOut) {
        if (original.getSessions() == null || original.getSessions().isEmpty()) return original;

        List<AttendanceExcelParser.Session> patched = new ArrayList<>();
        List<AttendanceExcelParser.Session> origSessions = original.getSessions();

        for (int i = 0; i < origSessions.size(); i++) {
            AttendanceExcelParser.Session s = origSessions.get(i);
            if (i == origSessions.size() - 1 && s.getOut() == null && s.getIn() != null) {
                // Patch giờ ra của session cuối cùng
                patched.add(AttendanceExcelParser.Session.builder()
                        .in(s.getIn()).out(defaultOut).build());
            } else {
                patched.add(s);
            }
        }

        // Trả về DayRecord mới với sessions đã patch
        return AttendanceExcelParser.DayRecord.builder()
                .date(original.getDate())
                .weekdayLabel(original.getWeekdayLabel())
                .sessions(patched)
                .symbol(original.getSymbol())
                .lateMinutes(original.getLateMinutes())
                .earlyMinutes(original.getEarlyMinutes())
                .overtimeMinutes(original.getOvertimeMinutes())
                .rawHours(original.getRawHours())
                .rawWorkUnits(original.getRawWorkUnits())
                .build();
    }

    /** Làm tròn về 4 chữ số thập phân (dùng cho ngày công có lẻ sau khi trừ phép). */
    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * Kiểm tra ngày có phải ngày lễ được nghỉ không.
     * Ngày lễ = deptExceptions có type {@code FULL_DAY_OFF} cho ngày đó.
     */
    private static boolean isHolidayOff(int dayOfMonth,
                                        Map<Integer, AttendanceException> deptExceptions) {
        AttendanceException ex = deptExceptions.get(dayOfMonth);
        return ex != null
                && ex.getType() == com.nhatnam.server.enumtype.AttendanceExceptionType.FULL_DAY_OFF;
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
        int expectedMinutes() {
            return (int) java.time.Duration.between(start, end).toMinutes();
        }
    }

    /** Bộ dựng {@link DayPlan} — gom nhiều nguồn ưu đãi cho cùng một ngày. */
    private static final class DayPlanBuilder {
        LocalTime start = SHIFT_START, end = SHIFT_END;
        boolean fullCredit = false, zeroDay = false, mealPaidLeave = false;
        double minCredit = 0.0, deduction = 0.0;
        LocalTime excusedFrom = null, excusedTo = null;
        final List<String> labels = new ArrayList<>();

        DayPlan build() {
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
            case FULL_DAY_OFF -> { b.fullCredit = true; }

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
            // KHÔNG set mealPaidLeave: nghỉ phép có lương cũng không ăn cơm giữa
            // ca. Phụ cấp cơm theo Thông tư 111/2013 chỉ áp cho ngày thực sự
            // có mặt tại nơi làm việc.
        }

        if (eff.shiftStart() != null) b.start = maxTime(b.start, eff.shiftStart());
        if (eff.shiftEnd() != null)   b.end   = minTime(b.end, eff.shiftEnd());

        if (eff.excusedFrom() != null && eff.excusedTo() != null) {
            b.excusedFrom = minTime(b.excusedFrom, eff.excusedFrom());
            b.excusedTo   = maxTime(b.excusedTo, eff.excusedTo());
        }

        b.deduction += eff.deduction();
        b.minCredit = Math.max(b.minCredit, eff.minCredit());
    }

    /**
     * Kết quả công của một ngày.
     *
     * @param mealEligible ngày này có được tính phụ cấp cơm không
     */
    private record DayResult(double value, String type, int late, int early,
                             int worked, int required, boolean mealEligible, String note) {}

    private DayResult resolveDay(DayRecord d, DayPlan p, boolean isPartTime) {
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

        // ── TỰ NHẬN NỬA BUỔI cho FULL-TIME (không có đơn nghỉ nửa buổi đã nộp) ──
        //   Trường hợp thực tế: nhân viên chỉ đến làm buổi sáng (đi 8:14, về
        //   12:30) hoặc chỉ buổi chiều, không nộp đơn xin nghỉ nửa buổi. Trước
        //   đây tính 0 công vì worked < 4h; giờ cho 0.5 công nếu pattern rõ.
        //
        //   Điều kiện áp dụng — TẤT CẢ phải đúng:
        //     - Full-time (part-time đã có logic 0.5 công riêng ở dưới)
        //     - Ca là 8:00-17:00 chuẩn (chưa bị đơn khác dời)
        //     - minCredit chưa được set (chưa có đơn nghỉ nửa buổi nào áp dụng)
        //     - Pattern chấm công khớp SÁNG-ONLY hoặc CHIỀU-ONLY
        //     - Làm ít nhất 2h trong nửa buổi tương ứng (chống lách bằng cách
        //       quẹt thẻ 5 phút rồi về)
        if (!isPartTime && p.minCredit() < 0.5
                && p.start().equals(SHIFT_START) && p.end().equals(SHIFT_END)) {
            p = autoDetectHalfDay(p, firstIn, lastOut);
        }

        int late  = Math.max(0, minutesBetween(p.start(), firstIn));
        int early = Math.max(0, minutesBetween(lastOut, p.end()));

        // ── Phần trùng LUNCH nằm TRONG CA — dùng cho cả hai phép tính bên dưới ──
        //   Trước đây `presentDuringLunch` chỉ so với [12:00,13:00] mà không
        //   biên với ca — khi ca bị rút còn 8:00-12:00 (nghỉ chiều / tự nhận),
        //   một session ra lúc 12:30 sẽ bị TRỪ 30 phút lunch không có trong ca
        //   → worked bị tụt ảo 30 phút. Nay dùng cùng giao [ca ∩ lunch] cho cả
        //   `lunchAllowance` và `presentDuringLunch` để loại bug này.
        LocalTime lunchInShiftStart = maxTime(p.start(), LUNCH_START);
        LocalTime lunchInShiftEnd   = minTime(p.end(),   LUNCH_END);
        boolean shiftContainsLunch = lunchInShiftStart != null
                && lunchInShiftEnd != null
                && lunchInShiftStart.isBefore(lunchInShiftEnd);

        int present = 0, presentDuringLunch = 0;
        if (d.getSessions() != null) {
            for (AttendanceExcelParser.Session ss : d.getSessions()) {
                if (ss.getIn() == null || ss.getOut() == null) continue;
                present += overlapMinutes(ss.getIn(), ss.getOut(), p.start(), p.end());
                if (shiftContainsLunch) {
                    presentDuringLunch += overlapMinutes(ss.getIn(), ss.getOut(),
                            lunchInShiftStart, lunchInShiftEnd);
                }
            }
        }

        int lunchAllowance = shiftContainsLunch
                ? minutesBetween(lunchInShiftStart, lunchInShiftEnd)
                : 0;

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

        // ── TÍNH CÔNG THEO SỐ GIỜ LÀM THỰC TẾ ────────────────────────────────
        //
        //   PART-TIME (ca chuẩn ~4 tiếng):
        //     Có quẹt thẻ = 0.5 công. Đi trễ / về sớm KHÔNG trừ (chỉ tính KPI).
        //     Ngưỡng tối thiểu 2 tiếng để loại trường hợp quẹt rồi đi về ngay.
        //
        //   FULL-TIME (ca chuẩn 8 tiếng):
        //     ≥ 6 tiếng (360 phút)  → 1.0 công
        //     ≥ 4 tiếng (240 phút)  → 0.5 công (làm nửa buổi)
        //     < 4 tiếng             → 0 công
        //
        //   Đi trễ / về sớm CHỈ ghi nhận KPI, KHÔNG trừ lương.
        double value;
        if (isPartTime) {
            // Part-time: quẹt thẻ + làm ít nhất 2 tiếng = 0.5 công
            value = worked >= 120 ? 0.5 : 0.0;
        } else if (worked >= 360) {
            value = 1.0;
        } else if (worked >= 240) {
            value = 0.5;
        } else {
            value = 0.0;
        }

        value = clamp01(value - p.deduction());

        // Mức sàn của ngày nghỉ nửa buổi áp cả khi có đi làm nhưng thiếu giờ.
        value = Math.max(value, p.minCredit());

        // ── PHỤ CẤP CƠM: chỉ khi làm TỪ 6 TIẾNG trở lên ──────────────────
        //   Dưới 6 tiếng (360 phút) không được phụ cấp cơm của ngày đó.
        boolean mealEligible = worked >= 360;

        // Type phản ánh đúng giá trị công: 1.0=WORK, 0.5=HALF, 0=OFF
        String dayType = value >= 1.0 ? "WORK" : value > 0 ? "HALF" : "MISSING";
        return new DayResult(value, dayType, late, early, worked, required, mealEligible, p.label());
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, round2(v)));
    }

    /**
     * TỰ NHẬN NỬA BUỔI cho FULL-TIME khi pattern chấm công rõ ràng chỉ sáng
     * hoặc chỉ chiều — dùng khi nhân viên KHÔNG nộp đơn xin nghỉ nửa buổi
     * (nếu có đơn thì {@code applyLegacy}/{@code applyEffect} đã set minCredit
     * = 0.5 và caller đã bỏ qua auto-detect này).
     *
     * <p>Ngưỡng {@value #AUTO_HALF_MIN_WORK_MINUTES} phút = 2h trong nửa buổi:
     * đủ để phân biệt "làm nửa buổi hợp lệ" với "quẹt thẻ rồi biến mất".
     *
     * @return DayPlan mới với ca ngắn còn nửa buổi và minCredit = 0.5 nếu
     *         nhận được; hoặc DayPlan gốc nếu không khớp pattern.
     */
    private DayPlan autoDetectHalfDay(DayPlan p, LocalTime firstIn, LocalTime lastOut) {
        // ── SÁNG-ONLY: vào trước 12:00, ra không sau 13:00 ─────────────────
        if (firstIn.isBefore(LUNCH_START) && !lastOut.isAfter(LUNCH_END)) {
            int workedMorning = overlapMinutes(firstIn, lastOut, SHIFT_START, LUNCH_START);
            if (workedMorning >= AUTO_HALF_MIN_WORK_MINUTES) {
                return new DayPlan(SHIFT_START, LUNCH_START,
                        false, false,
                        0.5,
                        p.excusedFrom(), p.excusedTo(),
                        p.deduction(),
                        false, // không tính cơm cho nửa buổi
                        joinLabel(p.label(), "Tự nhận nửa buổi sáng"));
            }
        }

        // ── CHIỀU-ONLY: vào từ 12:00 trở đi (dù chưa hết trưa), ra sau 13:00 ──
        if (!firstIn.isBefore(LUNCH_START) && lastOut.isAfter(LUNCH_END)) {
            int workedAfternoon = overlapMinutes(firstIn, lastOut, LUNCH_END, SHIFT_END);
            if (workedAfternoon >= AUTO_HALF_MIN_WORK_MINUTES) {
                return new DayPlan(LUNCH_END, SHIFT_END,
                        false, false,
                        0.5,
                        p.excusedFrom(), p.excusedTo(),
                        p.deduction(),
                        false,
                        joinLabel(p.label(), "Tự nhận nửa buổi chiều"));
            }
        }

        return p;
    }

    /** Ngưỡng tối thiểu để tự nhận nửa buổi — 120 phút = 2 tiếng. */
    private static final int AUTO_HALF_MIN_WORK_MINUTES = 120;

    private static String joinLabel(String existing, String add) {
        if (existing == null || existing.isBlank()) return add;
        return existing + " · " + add;
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

    /**
     * Phase 2 public wrapper — cho {@code CompanyAttendanceService} tái sử dụng
     * cùng logic lưu file đĩa, cùng cấu trúc thư mục {@code MM_YYYY/<DEPT>/}.
     * Nội bộ (các method per-department cũ) vẫn gọi private {@code storeFile}.
     */
    public String storeAttendanceFile(MultipartFile file, int month, int year,
                                      PayrollDepartment department, String prefix, List<String> warnings) {
        return storeFile(file, month, year, department, prefix, warnings);
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
     * OWNER bấm "HOÀN TẤT LƯƠNG" cho tháng + bộ phận.
     * Từ lúc này nhân viên thấy lương cơ bản + phụ cấp.
     * KPI / bonus vẫn hiển thị "Đang tính" cho đến khi gọi {@link #finalizeKpi}.
     */
    @Transactional
    public AttendanceSheetDto finalizePeriod(int month, int year,
                                             PayrollDepartment department, User actor) {
        validatePastOrCurrentPeriod(month, year);

        AttendanceSheet sheet = sheetOf(month, year, department);

        // SALES, ACCOUNTING và WAREHOUSE: file chấm công TUỲ CHỌN. Không có file
        // thì HrService tự trả full lương cơ bản + phụ cấp cơm đủ công chuẩn.
        // FACTORY: bắt buộc phải có file mới cho hoàn tất. (Phase 1: MANAGEMENT đã gỡ.)
        boolean requireFile = department.isAttendanceBased()
                && department != PayrollDepartment.SALES
                && department != PayrollDepartment.ACCOUNTING
                && department != PayrollDepartment.WAREHOUSE;
        if (requireFile && sheet.getFilePath() == null)
            throw new IllegalArgumentException(
                    "Chưa có bảng chấm công của %s tháng %d/%d — không thể hoàn tất."
                            .formatted(department.getLabel(), month, year));

        // Tính lại lần cuối theo FILE MỚI NHẤT trước khi chốt.
        // SALES/ACCOUNTING: chỉ tính lại nếu có file (để cập nhật mealDays).
        // Nếu không có file → HrService sẽ tính cơm mặc định = standardDays × 30.000.
        if (department.isAttendanceBased() && sheet.getFilePath() != null)
            recalculateFromStoredSheet(month, year, department, new ArrayList<>());

        sheet.setFinalized(true);
        sheet.setFinalizedAt(System.currentTimeMillis());
        sheet.setFinalizedByName(actor != null ? actor.getFullName() : null);
        sheetRepo.save(sheet);

        // CHỐT THÂM NIÊN của kỳ.
        computeSeniority(month, year, department, actor);

        log.info("[Attendance] HOÀN TẤT LƯƠNG {} tháng {}/{} bởi {}",
                department, month, year, actor != null ? actor.getFullName() : "?");
        return toSheetDto(sheet);
    }

    /**
     * OWNER bấm "HOÀN TẤT KPI / THƯỞNG" cho tháng + bộ phận.
     * Từ lúc này nhân viên thấy KPI và bonus.
     *
     * <p>Áp dụng đồng nhất cho mọi bộ phận — không phân biệt Xưởng / Tài xế / Kế toán.
     * OWNER phải gọi {@link #finalizePeriod} trước, rồi mới gọi hàm này.
     */
    @Transactional
    public AttendanceSheetDto finalizeKpi(int month, int year,
                                          PayrollDepartment department, User actor) {
        validatePastOrCurrentPeriod(month, year);

        AttendanceSheet sheet = sheetOf(month, year, department);

        // FIX (11/2026): KHÔNG còn bắt buộc hoàn tất Lương trước khi hoàn tất
        // KPI/Thưởng — theo yêu cầu, Thưởng/KPI và Lương là 2 phần tách biệt
        // (áp dụng chung cho FACTORY/ACCOUNTING/SALES/DRIVER).

        // Tính lại KPI/bonus theo bộ phận
        switch (department) {
            case FACTORY -> {
                // Xưởng: tính lại thưởng KPI sản xuất theo sản lượng
                kpiService.recompute(month, year);
            }
            case DRIVER -> {
                // Tài xế: tính phụ cấp xăng xe cho nhân viên ngoài bộ phận tài xế
                // (giá xăng và đơn giá có thể null nếu chưa nhập — không bắt buộc)
                if (sheet.getDriverGasPrice() != null || sheet.getDriverBonusUnitPrice() != null) {
                    computeNonDriverFuelAllowance(month, year,
                            sheet.getDriverGasPrice(),
                            sheet.getDriverBonusUnitPrice(),
                            sheet.getDriverTruckBonusUnitPrice());
                }
            }
            case ACCOUNTING, SALES, WAREHOUSE -> {
                // Các bộ phận khác: bonus đã được import qua file Excel (monthly_adjustment).
                // Không cần tính lại — chỉ chốt cờ để nhân viên thấy.
                log.info("[Attendance] Hoàn tất KPI/Thưởng {} tháng {}/{}: bonus từ file import.",
                        department, month, year);
            }
        }

        sheet.setKpiFinalized(true);
        sheet.setKpiFinalizedAt(System.currentTimeMillis());
        sheet.setKpiFinalizedByName(actor != null ? actor.getFullName() : null);
        sheetRepo.save(sheet);

        log.info("[Attendance] HOÀN TẤT KPI {} tháng {}/{} bởi {}",
                department, month, year, actor != null ? actor.getFullName() : "?");
        return toSheetDto(sheet);
    }

    /**
     * Mở lại KPI/Thưởng — nhân viên quay về trạng thái "Đang tính thưởng".
     * Không ảnh hưởng đến trạng thái lương đã hoàn tất.
     */
    @Transactional
    public AttendanceSheetDto reopenKpi(int month, int year, PayrollDepartment department) {
        AttendanceSheet sheet = sheetOf(month, year, department);
        sheet.unfinalizeKpi();
        sheetRepo.save(sheet);
        log.info("[Attendance] MỞ LẠI KPI {} tháng {}/{}", department, month, year);
        return toSheetDto(sheet);
    }

    /**
     * [LEGACY] Chốt thâm niên của kỳ — đã tắt tính năng 2026 (xem body).
     * Mốc đếm năm trước đây là NGÀY CUỐI CỦA KỲ LƯƠNG, không phải lúc bấm nút.
     */
    @Transactional
    public int computeSeniority(int month, int year, PayrollDepartment department, User actor) {
        // [ĐÃ BỎ 2026] Công ty ngừng dùng phụ cấp thâm niên → không cần chốt.
        // Giữ hàm để nơi gọi (finalizePeriod) không phải đổi. Bản chốt cũ trong
        // employee_seniority KHÔNG bị xóa — chỉ dừng tạo bản mới. Muốn khôi
        // phục thì restore body cũ từ git.
        log.debug("[Seniority] Bỏ qua chốt thâm niên (đã tắt tính năng) — {} tháng {}/{}",
                department, month, year);
        return 0;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LƯƠNG TÀI XẾ — cấu hình giá xăng / đơn giá thưởng + bảng lương
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Điền các trường lương vào {@link DriverMonthDto} theo cấu hình trên sheet.
     * Chỉ trả lương xe máy (tương thích ngược cho màn hình cũ của tài xế).
     */
    private void fillDriverSalary(DriverMonthDto dm, AttendanceSheet sheet, boolean requireKpiFinalized) {
        if (dm == null) return;
        boolean finalized = sheet != null && sheet.isFinalized();
        boolean kpiFinalized = sheet != null && sheet.isKpiFinalized();
        dm.setFinalized(finalized);

        Long gasPrice = sheet != null ? sheet.getDriverGasPrice() : null;
        Long bonusUnitPrice = sheet != null ? sheet.getDriverBonusUnitPrice() : null;
        dm.setGasPrice(gasPrice);
        dm.setBonusUnitPrice(bonusUnitPrice);

        // Chỉ hiện tiền xăng + thưởng khi KPI đã hoàn tất (hoặc không yêu cầu)
        boolean canCompute = gasPrice != null && bonusUnitPrice != null
                && (!requireKpiFinalized || kpiFinalized);
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

        // (10/2026) CACHE HIT: nếu sheet có driverBonusCachedJson (được tạo
        // ngay sau khi OWNER bấm "Tính thưởng"), parse & return ngay — không
        // tính lại. Giảm từ ~2.5s xuống <50ms vì bỏ qua query driver_km +
        // orders + attendance_entry.
        if (sheet != null && sheet.getDriverBonusCachedJson() != null
                && !sheet.getDriverBonusCachedJson().isBlank()) {
            try {
                return MAPPER.readValue(sheet.getDriverBonusCachedJson(),
                        DriverPayrollConfigDto.class);
            } catch (Exception e) {
                log.warn("[Payroll] Cache driver bonus bị corrupt {}/{}: {} — fallback tính live",
                        month, year, e.getMessage());
                // Rơi xuống nhánh tính live bên dưới.
            }
        }

        return computeDriverPayrollConfigLive(month, year);
    }

    /**
     * (10/2026) Tính driver payroll config LIVE — tách khỏi
     * {@link #driverPayrollConfig(int, int)} để {@link #calculateDriverBonus}
     * có thể gọi trực tiếp để lấy snapshot cache mà không chạy cache-check
     * (vì mục tiêu là tạo cache mới, cache cũ không còn ý nghĩa).
     */
    @Transactional(readOnly = true)
    public DriverPayrollConfigDto computeDriverPayrollConfigLive(int month, int year) {
        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, PayrollDepartment.DRIVER)
                .orElse(null);

        Long gasPrice = sheet != null ? sheet.getDriverGasPrice() : null;
        Long bonusMoto = sheet != null ? sheet.getDriverBonusUnitPrice() : null;
        Long bonusTruck = sheet != null ? sheet.getDriverTruckBonusUnitPrice() : null;
        boolean finalized = sheet != null && sheet.isFinalized();

        // Bao gồm CẢ tài xế đang bị khoá tài khoản: lương tài xế = số km × giá
        // xăng + số lượt × đơn giá, dựa trên công việc THỰC TẾ đã làm. Tài khoản
        // bị khoá không phải lý do để trừ lương cho phần đã chạy.
        List<User> allDrivers = deptResolver.employeesWithLockedOf(PayrollDepartment.DRIVER);

        // (10/2026) Tách thành 2 nhóm theo chức vụ:
        //   • Tài xế xe máy (position != "Tài xế xe tải"): tính theo km × giá xăng
        //     + lượt × đơn giá. VẪN được tính lượt xe tải nếu kiêm (dm.vehicleType=BOTH).
        //   • Tài xế xe tải (position == "Tài xế xe tải"): thưởng CỐ ĐỊNH, không
        //     tính theo lượt. Fetch số đã lưu ở TruckDriverFixedBonus.
        //
        // Legacy data (position = "Tài xế giao nhận" hoặc rỗng) được coi là tài
        // xế xe máy để không phá vỡ dữ liệu cũ.
        java.util.Map<Long, Long> fixedBonusByUser = new java.util.HashMap<>();
        truckDriverFixedBonusRepo.findByMonthAndYear(month, year).forEach(fb -> {
            if (fb.getUser() != null)
                fixedBonusByUser.put(fb.getUser().getId(), fb.getAmount() != null ? fb.getAmount() : 0L);
        });

        List<User> drivers        = new ArrayList<>();
        List<User> truckDrivers   = new ArrayList<>();
        for (User u : allDrivers) {
            if (isTruckDriverPosition(u.getPosition())) truckDrivers.add(u);
            else                                        drivers.add(u);
        }

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
                    && (truck == null || truck.getTotalSalary() != null)) {
                // (10/2026) Làm tròn LÊN bước 5.000
                grandForDriver = roundUpTo(motoTotal + truckTotal, 5_000L);
            }
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

        // ── Nhân viên NGOÀI bộ phận Tài xế có hoạt động giao hàng ──────────
        //
        // (10/2026) FIX: PHẢI loại trừ CẢ allDrivers (gồm xe máy + xe tải) khỏi
        // nhóm "hỗ trợ giao hàng". Trước đây chỉ loại `drivers` (xe máy) → tài
        // xế xe tải vẫn lọt vào bảng HỖ TRỢ GIAO HÀNG nếu có hoạt động ODO/đơn
        // trong tháng. Tài xế xe tải đã có section riêng với thưởng cố định,
        // không được hiện thêm ở nhóm hỗ trợ.
        List<DriverSalaryRowDto> nonDriverRows = buildNonDriverRows(
                month, year, gasPrice, bonusMoto, bonusTruck, allDrivers);

        // ── (10/2026) Tài xế xe tải — thưởng cố định ───────────────────────
        List<DriverSalaryRowDto> truckDriverRows = new ArrayList<>();
        for (User u : truckDrivers) {
            long fixed = fixedBonusByUser.getOrDefault(u.getId(), 0L);
            truckDriverRows.add(DriverSalaryRowDto.builder()
                    .userId(u.getId())
                    .driverName(u.getFullName())
                    .vehicleType("TRUCK_FIXED")
                    .truckDriver(true)
                    .fixedTruckBonus(fixed)
                    .grandTotalSalary(fixed)         // = fixed, không round
                    .position(u.getPosition())
                    .motorbike(null).truck(null)      // emdash tất cả cột tính theo lượt
                    .build());
            grand += fixed;
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
                .truckDriverRows(truckDriverRows)
                .nonDriverRows(nonDriverRows)
                .build();
    }

    /**
     * (10/2026) Chức vụ "Tài xế xe tải" → nhận thưởng cố định, không tính lượt.
     * Legacy "Tài xế giao nhận" / null được coi là xe máy để không phá dữ liệu cũ.
     * Compare không phân biệt hoa thường + trim.
     */
    private static boolean isTruckDriverPosition(String position) {
        if (position == null) return false;
        return position.trim().equalsIgnoreCase("Tài xế xe tải");
    }

    /** (10/2026) Upsert thưởng cố định tài xế xe tải cho 1 user/tháng. */
    @Transactional
    public void upsertTruckDriverFixedBonus(Long userId, int month, int year, long amount) {
        if (userId == null) throw new IllegalArgumentException("userId null");
        if (amount < 0) throw new IllegalArgumentException("Thưởng cố định không âm");
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user " + userId));
        if (!isTruckDriverPosition(u.getPosition()))
            throw new IllegalArgumentException(
                    "User " + u.getFullName() + " không có chức vụ 'Tài xế xe tải' — không nhập được thưởng cố định.");

        com.nhatnam.server.entity.TruckDriverFixedBonus row =
                truckDriverFixedBonusRepo.findByUser_IdAndMonthAndYear(userId, month, year)
                        .orElseGet(() -> com.nhatnam.server.entity.TruckDriverFixedBonus.builder()
                                .user(u).month(month).year(year).amount(0L).build());
        row.setAmount(amount);
        truckDriverFixedBonusRepo.save(row);
    }

    /**
     * (10/2026) Upsert BATCH — gọi khi OWNER bấm "Tính thưởng" trong modal
     * với toàn bộ list thưởng cố định cho từng tài xế xe tải cùng lúc.
     * Entry với amount null bị bỏ qua.
     */
    @Transactional
    public void upsertTruckDriverFixedBonuses(int month, int year,
                                              java.util.Map<Long, Long> amountByUserId) {
        if (amountByUserId == null || amountByUserId.isEmpty()) return;
        amountByUserId.forEach((uid, amt) -> {
            if (uid != null && amt != null) upsertTruckDriverFixedBonus(uid, month, year, amt);
        });
    }

    /**
     * Dựng danh sách nhân viên NGOÀI bộ phận Tài xế nhưng có hoạt động giao hàng.
     * Dữ liệu chỉ để HIỂN THỊ — khoản phụ cấp thực tế đã lưu ở MonthlyAdjustment.
     */
    private List<DriverSalaryRowDto> buildNonDriverRows(int month, int year,
                                                        Long gasPrice, Long bonusMoto, Long bonusTruck,
                                                        List<User> driverDeptUsers) {
        Set<Long> driverDeptIds = driverDeptUsers.stream().map(User::getId).collect(Collectors.toSet());
        List<Driver> allDrivers = driverRepo.findBySystemDriverFalseOrderByNameAsc();
        List<DriverSalaryRowDto> result = new ArrayList<>();

        for (Driver driver : allDrivers) {
            if (driver.getUser() == null) continue;
            User u = driver.getUser();
            if (driverDeptIds.contains(u.getId())) continue;

            DriverMonthDto dm = driverKmService.monthOf(u, month, year);
            double km = dm.getTotalKm() != null ? dm.getTotalKm() : 0;
            int tripsMoto = dm.getTotalTripsMotorbike() != null ? dm.getTotalTripsMotorbike() : 0;
            int tripsTrk = dm.getTotalTripsTruck() != null ? dm.getTotalTripsTruck() : 0;

            if (km <= 0 && tripsMoto <= 0 && tripsTrk <= 0) continue;

            VehicleSubtotalDto moto = null;
            long motoTotal = 0;
            if (km > 0 || tripsMoto > 0) {
                Long fuel = gasPrice != null ? Math.round(km * gasPrice) : null;
                Long bonus = bonusMoto != null ? (long) tripsMoto * bonusMoto : null;
                if (fuel != null && bonus != null) motoTotal = fuel + bonus;
                moto = VehicleSubtotalDto.builder()
                        .totalKm(km).totalTrips(tripsMoto)
                        .fuelPay(fuel).bonusPay(bonus)
                        .totalSalary(fuel != null && bonus != null ? motoTotal : null)
                        .build();
            }

            VehicleSubtotalDto truck = null;
            long truckTotal = 0;
            if (tripsTrk > 0) {
                Long bonus = bonusTruck != null ? (long) tripsTrk * bonusTruck : null;
                if (bonus != null) truckTotal = bonus;
                truck = VehicleSubtotalDto.builder()
                        .totalTrips(tripsTrk)
                        .bonusPay(bonus)
                        .totalSalary(bonus != null ? truckTotal : null)
                        .build();
            }

            Long grandTotal = null;
            if ((moto == null || moto.getTotalSalary() != null)
                    && (truck == null || truck.getTotalSalary() != null))
                grandTotal = roundUpTo(motoTotal + truckTotal, 5_000L);

            result.add(DriverSalaryRowDto.builder()
                    .userId(u.getId())
                    .driverId(dm.getDriverId())
                    .driverName(u.getFullName())
                    .vehicleType(dm.getVehicleType())
                    .motorbike(moto).truck(truck)
                    .grandTotalSalary(grandTotal)
                    .department(u.getDepartment())
                    .position(u.getPosition())
                    .nonDriver(true)
                    .build());
        }
        return result;
    }

    /**
     * OWNER nhập / cập nhật giá xăng + đơn giá thưởng (xe máy & xe tải) cho tháng.
     * Đổi giá chỉ gỡ trạng thái "Hoàn tất KPI" (không gỡ "Hoàn tất Lương"),
     * vì giá xăng thuộc phần bonus/KPI, không phải lương cơ bản.
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
        // Đổi giá → gỡ KPI đã hoàn tất (không ảnh hưởng lương cơ bản)
        if (changed && sheet.isKpiFinalized()) sheet.unfinalizeKpi();
        sheetRepo.save(sheet);

        // Không tính lại phụ cấp xăng ngay — sẽ tính khi OWNER bấm "Hoàn tất KPI"
        return driverPayrollConfig(month, year);
    }

    /**
     * (10/2026) TÍNH THƯỞNG TÀI XẾ — flow mới 3-state giống KT/KD.
     *
     * <p>Lưu 3 config (gas + 2 unit prices) vào sheet DRIVER + đánh dấu
     * {@code bonusCalcStatus = CALCULATED}. Nhân viên tài xế CHƯA thấy thưởng
     * cho đến khi OWNER bấm "Hoàn tất" (= Public → publishBonus).
     *
     * <p>KHÁC với {@link #saveDriverPayrollConfig}: không reset KPI finalized —
     * flow mới dùng bonusCalcStatus thay cho kpiFinalized để quyết định visibility.
     */
    @Transactional
    public AttendanceSheetDto calculateDriverBonus(int month, int year,
                                                   Long gasPrice, Long bonusUnitPrice, Long truckBonusUnitPrice, User actor) {
        validatePastOrCurrentPeriod(month, year);
        if (gasPrice == null || gasPrice < 0) throw new IllegalArgumentException("Giá xăng không hợp lệ.");
        if (bonusUnitPrice == null || bonusUnitPrice < 0)
            throw new IllegalArgumentException("Đơn giá thưởng xe máy không hợp lệ.");
        if (truckBonusUnitPrice == null || truckBonusUnitPrice < 0)
            throw new IllegalArgumentException("Đơn giá thưởng xe tải không hợp lệ.");

        AttendanceSheet sheet = sheetOf(month, year, PayrollDepartment.DRIVER);
        sheet.setDriverGasPrice(gasPrice);
        sheet.setDriverBonusUnitPrice(bonusUnitPrice);
        sheet.setDriverTruckBonusUnitPrice(truckBonusUnitPrice);
        // Đánh dấu CALCULATED — chưa Public cho tài xế thấy.
        sheet.markBonusCalculated(actor != null ? actor.getFullName() : "system");
        // (10/2026) Clear cache cũ trước khi tính mới — driverPayrollConfig sẽ
        // tính lại live và tự cache lại qua đường dẫn miss cache ở dưới.
        sheet.setDriverBonusCachedJson(null);
        sheetRepo.save(sheet);

        // Compute rows LIVE 1 lần + cache vào JSON để các lần load sau đọc cache.
        try {
            DriverPayrollConfigDto freshRows = computeDriverPayrollConfigLive(month, year);
            String cached = MAPPER.writeValueAsString(freshRows);
            sheet.setDriverBonusCachedJson(cached);
            sheetRepo.save(sheet);
        } catch (Exception ex) {
            log.warn("[Payroll] Không cache được driver rows cho {}/{}: {}", month, year, ex.getMessage());
        }

        log.info("[Payroll] TÍNH THƯỞNG TÀI XẾ {}/{} bởi {} (gas={}, moto={}, truck={})",
                month, year, actor != null ? actor.getFullName() : "system",
                gasPrice, bonusUnitPrice, truckBonusUnitPrice);
        return toSheetDto(sheet);
    }

    /** Nhãn phụ cấp xăng xe tự động cho nhân viên ngoài bộ phận Tài xế. */
    private static final String NON_DRIVER_FUEL_LABEL = "Phụ cấp xăng xe";
    private static final String NON_DRIVER_DELIVERY_LABEL = "Phụ cấp giao hàng";

    /**
     * Tính phụ cấp xăng xe + phụ cấp giao hàng cho nhân viên KHÔNG thuộc bộ phận
     * Tài xế nhưng có dữ liệu giao hàng (điểm danh ODO / đơn hàng) trong tháng.
     *
     * <p>Tách thành 2 khoản riêng biệt:
     * <ul>
     *   <li><b>Phụ cấp xăng xe</b>: tổng km × đơn giá xăng — chi phí xăng thực tế.</li>
     *   <li><b>Phụ cấp giao hàng</b>: lượt xe máy × đơn giá + lượt xe tải × đơn giá — thưởng theo đơn.</li>
     * </ul>
     *
     * Nhãn kèm chi tiết (km, số lượt) để nhân viên và OWNER đều thấy rõ cơ sở tính.
     */
    @Transactional
    public void computeNonDriverFuelAllowance(int month, int year,
                                              Long gasPrice, Long bonusMoto, Long bonusTruck) {
        // Xoá khoản cũ của kỳ trước khi tính lại — cả 2 nhãn (dùng prefix vì label có chi tiết động)
        monthlyAdjustmentRepo.deleteByPeriodTypeAndLabelPrefix(month, year,
                MonthlyAdjustment.Type.ALLOWANCE, NON_DRIVER_FUEL_LABEL);
        monthlyAdjustmentRepo.deleteByPeriodTypeAndLabelPrefix(month, year,
                MonthlyAdjustment.Type.ALLOWANCE, NON_DRIVER_DELIVERY_LABEL);
        // Xoá cả nhãn cũ (giao hàng) nếu còn tồn tại từ phiên bản trước
        monthlyAdjustmentRepo.deleteByPeriodTypeAndLabelPrefix(month, year,
                MonthlyAdjustment.Type.ALLOWANCE, "Phụ cấp xăng xe (giao hàng)");
        monthlyAdjustmentRepo.flush();

        if (gasPrice == null && bonusMoto == null && bonusTruck == null) return;

        // Tìm tất cả Driver entity thật (không phải system driver)
        List<Driver> allDrivers = driverRepo.findBySystemDriverFalseOrderByNameAsc();
        Set<Long> driverDeptUserIds = deptResolver.employeesOf(PayrollDepartment.DRIVER)
                .stream().map(User::getId).collect(Collectors.toSet());

        long now = System.currentTimeMillis();
        List<MonthlyAdjustment> toSave = new ArrayList<>();

        for (Driver driver : allDrivers) {
            if (driver.getUser() == null) continue;
            User u = driver.getUser();
            // Bỏ qua nhân viên thuộc bộ phận Tài xế — họ đã được tính ở bảng lương tài xế
            if (driverDeptUserIds.contains(u.getId())) continue;

            DriverMonthDto dm = driverKmService.monthOf(u, month, year);

            double km = dm.getTotalKm() != null ? dm.getTotalKm() : 0;
            int tripsMoto = dm.getTotalTripsMotorbike() != null ? dm.getTotalTripsMotorbike() : 0;
            int tripsTrk = dm.getTotalTripsTruck() != null ? dm.getTotalTripsTruck() : 0;
            int ordersMoto = dm.getTotalOrdersMotorbike() != null ? dm.getTotalOrdersMotorbike() : 0;
            int ordersTrk = dm.getTotalOrdersTruck() != null ? dm.getTotalOrdersTruck() : 0;

            // Chỉ tính nếu có hoạt động giao hàng trong tháng
            if (km <= 0 && tripsMoto <= 0 && tripsTrk <= 0) continue;

            PayrollDepartment dept = deptResolver.departmentOf(u);
            String deptCode = dept != null ? dept.name() : null;

            // ── Khoản 1: PHỤ CẤP XĂNG XE (km × giá xăng) ────────────────
            long fuelPay = gasPrice != null ? Math.round(km * gasPrice) : 0L;
            if (fuelPay > 0) {
                String fuelLabel = NON_DRIVER_FUEL_LABEL
                        + " (" + fmtNum(km) + " km × " + fmtNum(gasPrice) + "đ)";
                toSave.add(MonthlyAdjustment.builder()
                        .user(u)
                        .month(month).year(year)
                        .type(MonthlyAdjustment.Type.ALLOWANCE)
                        .department(deptCode)
                        .label(fuelLabel)
                        .amount(fuelPay)
                        .createdAt(now)
                        .build());
            }

            // ── Khoản 2: PHỤ CẤP GIAO HÀNG (lượt × đơn giá thưởng) ──────
            long bonusMotoAmt = bonusMoto != null ? (long) tripsMoto * bonusMoto : 0L;
            long bonusTruckAmt = bonusTruck != null ? (long) tripsTrk * bonusTruck : 0L;
            long deliveryPay = bonusMotoAmt + bonusTruckAmt;
            if (deliveryPay > 0) {
                String deliveryLabel = NON_DRIVER_DELIVERY_LABEL;
                List<String> parts = new ArrayList<>();
                if (tripsMoto > 0 && bonusMoto != null)
                    parts.add(tripsMoto + " lượt xe máy × " + fmtNum(bonusMoto) + "đ");
                if (tripsTrk > 0 && bonusTruck != null)
                    parts.add(tripsTrk + " lượt xe tải × " + fmtNum(bonusTruck) + "đ");
                if (!parts.isEmpty())
                    deliveryLabel += " (" + String.join(" + ", parts) + ")";
                toSave.add(MonthlyAdjustment.builder()
                        .user(u)
                        .month(month).year(year)
                        .type(MonthlyAdjustment.Type.ALLOWANCE)
                        .department(deptCode)
                        .label(deliveryLabel)
                        .amount(deliveryPay)
                        .createdAt(now)
                        .build());
            }

            log.info("[DriverFuel] {} ({}) — {}: {}km → xăng {}đ | moto {}lượt/{}đơn, tải {}lượt/{}đơn → giao hàng {}đ",
                    u.getFullName(), u.getId(), deptCode, km, fuelPay,
                    tripsMoto, ordersMoto, tripsTrk, ordersTrk, deliveryPay);
        }

        if (!toSave.isEmpty()) {
            monthlyAdjustmentRepo.saveAll(toSave);
            log.info("[DriverFuel] Đã lưu {} khoản phụ cấp cho nhân viên ngoài bộ phận Tài xế, tháng {}/{}",
                    toSave.size(), month, year);
        }
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

        // ── TỔNG "Thưởng KPI 100%" của tài xế ────────────────────────────────
        //   Gom TIỀN XĂNG + THƯỞNG ĐƠN HÀNG (xe máy + xe tải) thành một dòng
        //   duy nhất "Thưởng KPI — đạt 100%" trên phiếu lương, thay vì tách gas
        //   ra allowances và orderBonus lên riêng. Card chi tiết bên dưới (do FE
        //   render từ driverOrderBonusDetail) vẫn giữ đủ 3 hạng mục.
        long driverKpi100Raw = gasAllowance + orderBonus; // = gas + moto + truck
        // (10/2026 — user request) Làm tròn LÊN theo bước 5.000 cho tổng thưởng
        // giao hàng cuối cùng. VD 204.584 → 205.000.
        long driverKpi100 = roundUpTo(driverKpi100Raw, 5_000L);

        com.nhatnam.server.dto.hr.HrDtos.DriverOrderBonusDto orderBonusDetail = null;
        if (driverKpi100 > 0) {
            orderBonusDetail = com.nhatnam.server.dto.hr.HrDtos.DriverOrderBonusDto.builder()
                    .motorbikeTrips(tripsMoto).motorbikeAmount(orderBonusMoto)
                    .truckTrips(tripsTruck).truckAmount(orderBonusTruck)
                    .gasKm(km).gasPrice(gasPrice).gasAmount(gasAllowance)
                    .totalAmount(driverKpi100)
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

        // ── Chỉ giữ PHỤ CẤP CƠM TRƯA trong allowances ────────────────────────
        //   Tiền xăng đã CHUYỂN sang "Thưởng KPI — đạt 100%" nên KHÔNG chèn vào
        //   allowances nữa (nếu chèn sẽ bị đếm 2 lần).
        //   Cơm trưa theo Thông tư 111/2013: không tính vào thu nhập chịu thuế
        //   TNCN (trong định mức doanh nghiệp quy định).
        if (mealAllowance > 0)
            allowances.add(0, AllowanceItemDto.builder()
                    .label("Phụ cấp cơm trưa (" + mealDays + " ngày × 30.000)")
                    .amount(mealAllowance).taxable(false).build());

        // ── Build request + gọi HR service để có breakdown chuẩn ─────────────
        //   Truyền driverKpi100 là BONUS KHÔNG CHỊU THUẾ — HR tính GROSS/NET có
        //   sẵn phần này rồi nên KHÔNG cộng lại ở dưới, tránh double count.
        SalaryRequest req = new SalaryRequest();
        req.setUserId(userId);
        req.setBaseSalary(baseSalary);
        req.setInsuranceSalary(insSalaryRaw);   // null = mặc định = baseSalary
        req.setDependents(dependents);
        req.setAllowances(allowances);
        req.setBonus(driverKpi100);
        req.setBonusTaxable(false);
        // Truyền month/year để HR service tự nạp phụ cấp/thưởng import từ
        // MonthlyAdjustment (Chuyên cần, Xăng xe kho…). Nhờ đó Chuyên cần hiển
        // thị đúng nhãn thay vì gộp vào "Thưởng KPI".
        req.setMonth(month);
        req.setYear(year);
        SalaryBreakdownDto dto = hrService.previewSalary(req);

        // ── Ghi cờ để FE hiện dòng "Thưởng KPI — đạt 100%" + card chi tiết ──
        //   HR đã gộp driverKpi100 vào effectiveBonus (do req.bonus truyền vào).
        //   Ép kpiPercent = 100 cho tài xế — không phụ thuộc PayrollInputProvider —
        //   để label "đạt 100%" luôn đúng. driverOrderBonus / Detail để FE render
        //   thêm card "Chi tiết Thưởng KPI 100%" (xăng + xe máy + xe tải) bên dưới.
        if (dto != null) {
            dto.setKpiPercent(100.0);
            dto.setDriverOrderBonus(driverKpi100);
            if (orderBonusDetail != null) dto.setDriverOrderBonusDetail(orderBonusDetail);
        }
        return dto;
    }

    private static String fmtNum(Number n) {
        if (n == null) return "0";
        double d = n.doubleValue();
        if (d == Math.floor(d)) {
            return String.format(java.util.Locale.GERMANY, "%,d", n.longValue());
        }
        return String.format(java.util.Locale.GERMANY, "%,.1f", d);
    }

    /**
     * (10/2026) Nếu NV này có hỗ trợ giao hàng (km/trips > 0 trong tháng) VÀ
     * Thưởng Tài xế đã Public → compute "phụ cấp xăng + bonus đơn hàng" như
     * driverSalaryDetail làm cho tài xế chính, rồi attach vào
     * {@code salaryDetail.driverOrderBonusDetail} để FE hiển thị card
     * "Thưởng hỗ trợ giao hàng" trên phiếu lương của họ.
     *
     * <p>No-op nếu đã có detail sẵn (NV tài xế chính), DRIVER sheet chưa Public,
     * hoặc tháng này không có km/trips.
     */
    /**
     * Trả về {@link DriverMonthDto} (daily km/trips breakdown) để caller gắn
     * vào {@code b.driver(dm)} — FE hiển thị card "Chi tiết số km theo ngày"
     * cho NV hỗ trợ giao hàng. Trả null nếu NV không có km/trips hoặc tháng
     * tài xế chưa publish bonus.
     */
    /**
     * (10/2026) Attach "Thưởng HỖ TRỢ BÁN HÀNG" cho NV ko thuộc SALES nhưng
     * có đơn trong tháng. Flow:
     *   • Skip nếu NV đã thuộc SALES (bonus chính của họ, không phải "hỗ trợ").
     *   • Skip nếu sheet SALES chưa Public.
     *   • Query office_bonus_item (dept=SALES, user_id, month, year) → nếu có
     *     bonus_amount > 0 → set salesSupportBonus + salesSupportRevenue.
     */
    private void attachSalesSupportBonusIfAny(User user, int month, int year,
                                              PayrollDepartment dept, MyPayslipDto.MyPayslipDtoBuilder b) {
        if (dept == PayrollDepartment.SALES) return;  // đã là bonus chính
        AttendanceSheet salesSheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, PayrollDepartment.SALES)
                .orElse(null);
        if (salesSheet == null) return;
        boolean salesPub = salesSheet.isBonusFinalized()
                || salesSheet.getBonusCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED;
        if (!salesPub) return;

        Long bonus = officeBonusResultRepo.sumBonusForUser(month, year,
                PayrollDepartment.SALES, user.getId());
        if (bonus == null || bonus <= 0) return;
        b.salesSupportBonus(bonus);

        // Snapshot doanh thu chia hoa hồng (ex-VAT) cá nhân để hiển thị trên card
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        long startMs = ym.atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
        long endMs   = ym.plusMonths(1).atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
        java.math.BigDecimal rev = nzBd(paymentTransactionRepo.sumRevenueByUser(startMs, endMs, user.getId()));
        b.salesSupportRevenue(rev.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact());
    }

    private DriverMonthDto attachDriverSupportBonusIfAny(User user, int month, int year, SalaryBreakdownDto detail) {
        if (detail == null) return null;
        if (detail.getDriverOrderBonusDetail() != null
                && detail.getDriverOrderBonusDetail().getTotalAmount() != null
                && detail.getDriverOrderBonusDetail().getTotalAmount() > 0) return null;

        AttendanceSheet driverSheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, PayrollDepartment.DRIVER)
                .orElse(null);
        if (driverSheet == null) return null;
        boolean driverBonusPub = driverSheet.isBonusFinalized()
                || driverSheet.isKpiFinalized()
                || driverSheet.getBonusCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED;
        if (!driverBonusPub) return null;

        Long gasPrice   = driverSheet.getDriverGasPrice();
        Long bonusMoto  = driverSheet.getDriverBonusUnitPrice();
        Long bonusTruck = driverSheet.getDriverTruckBonusUnitPrice();

        DriverMonthDto dm;
        try { dm = driverKmService.monthOf(user, month, year); }
        catch (Exception e) { return null; }
        if (dm == null) return null;

        double km = dm.getTotalKm() != null ? dm.getTotalKm() : 0;
        int tripsMoto = dm.getTotalTripsMotorbike() != null ? dm.getTotalTripsMotorbike() : 0;
        int tripsTruck = dm.getTotalTripsTruck() != null ? dm.getTotalTripsTruck() : 0;
        long gasAllowance = gasPrice != null ? Math.round(km * gasPrice) : 0L;
        long orderBonusMoto = bonusMoto != null ? (long) tripsMoto * bonusMoto : 0L;
        long orderBonusTruck = bonusTruck != null ? (long) tripsTruck * bonusTruck : 0L;
        long totalRaw = gasAllowance + orderBonusMoto + orderBonusTruck;
        if (totalRaw <= 0) return null;
        // (10/2026) Làm tròn LÊN theo bước 5.000 cho đồng bộ với tài xế
        long total = roundUpTo(totalRaw, 5_000L);

        detail.setDriverOrderBonusDetail(com.nhatnam.server.dto.hr.HrDtos.DriverOrderBonusDto.builder()
                .motorbikeTrips(tripsMoto).motorbikeAmount(orderBonusMoto)
                .truckTrips(tripsTruck).truckAmount(orderBonusTruck)
                .gasKm(km).gasPrice(gasPrice).gasAmount(gasAllowance)
                .totalAmount(total)
                .build());
        return dm;
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

        // Bảng lương của bộ phận: LẤY CẢ nhân viên đang bị khoá tài khoản để
        // vẫn tính đủ lương theo chấm công đã có (nhân viên có thể đi làm rồi
        // mới bị khoá trong tháng). Phần THƯỞNG/KPI cho họ sẽ tự bằng 0 ở dưới
        // (kpiByUser không có entry, HrService.getSalaryBreakdownForUser zero
        // bonus khi user bị khoá).
        List<User> employees = deptResolver.employeesWithLockedOf(department);

        // ── KPI của tháng — chỉ bộ phận Xưởng, CHỈ khi KPI đã hoàn tất ────────
        //
        // Dùng find() chứ KHÔNG getOrCompute(): màn hình này phải phân biệt được
        // "chưa tính KPI" với "đã tính, quỹ bằng 0". getOrCompute sẽ âm thầm tính
        // ngay khi ai đó mở trang, khiến trạng thái "chưa tính" không bao giờ tồn
        // tại và OWNER mất khả năng thấy mình còn thiếu bước nào.
        // KPI được tính ở đúng hai chỗ có chủ đích: bấm "Hoàn tất KPI" và bấm "Tính lại".
        boolean kpiReady = sheet != null && sheet.isKpiFinalized();
        Map<Long, Long> kpiByUser = new HashMap<>();
        FactoryKpiBonus kpi = null;
        if (department.isKpiBonus() && kpiReady) {
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

            // ── FIX (10/2026): LOẠI 2 NHÓM khỏi bảng phiếu lương preview ─────
            //
            //   (a) Nhân viên không có hồ sơ lương APPROVED. Trước đây các
            //       dòng này hiện với 0đ + nhãn "chưa có hồ sơ lương" làm bảng
            //       lộn xộn + bị cộng nhầm vào các export. Nay chỉ hiển thị
            //       người đã có hồ sơ được duyệt.
            //
            //   (b) Nhân viên {@link PayrollDepartmentResolver#isAttendanceExempt}
            //       — hiện là FACTORY_SECURITY (Bảo vệ Q9). Đây là đơn vị thuê
            //       ngoài khoán trọn tháng, không nằm trong hệ thống lương công
            //       ty: không có hồ sơ lương, không chấm công, không export
            //       cùng với CBCNV khác.
            //
            //   NO_SALARY cũ (null status) và REJECTED đều bị loại — chỉ status
            //   == "APPROVED" là qua. SalaryBreakdown của người không có hồ sơ
            //   có status null, không phải "APPROVED" nên bị filter.
            if (deptResolver.isAttendanceExempt(u)) continue;
            if (s == null || !"APPROVED".equalsIgnoreCase(s.getStatus())) continue;

            AttendanceEntry e = department.isAttendanceBased()
                    ? entryRepo.findByUserAndPeriod(u.getId(), month, year).orElse(null)
                    : null;

            long kpiAmount = kpiByUser.getOrDefault(u.getId(), 0L);
            boolean exempt = false;   // đã filter ở trên, giữ biến để tương thích code dưới

            DepartmentPayrollRowDto.DepartmentPayrollRowDtoBuilder rb = DepartmentPayrollRowDto.builder()
                    .userId(u.getId())
                    .userFullName(u.getFullName())
                    .roleLabel(deptResolver.roleLabelOf(u))
                    .payrollRole(u.getPayrollRole() != null ? u.getPayrollRole().name() : null)
                    .department(u.getDepartment())
                    .division(u.getDivision())
                    .kpiBonus(kpiAmount)
                    .attendanceExempt(exempt)
                    .hasAttendance(e != null)
                    // (10/2026) Owner yêu cầu: ai thai sản thì bảng lương
                    // hiển thị "Đang nghỉ thai sản theo chế độ" merged, FE
                    // dựa vào cờ này để ẩn số liệu công/phụ cấp/thưởng.
                    .maternityLeave(com.nhatnam.server.service.FactoryKpiService.isMaternityLeave(u));

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
                //
                // FIX (10/2026): hiển thị actualDays từ SalaryBreakdown (đã
                // bao gồm công lễ + ManualAttendanceOverrides), không phải
                // entry.actualDays thô. Trước đây hai nguồn lệch nhau:
                //   - Tiến Vinh: entry.actualDays = 19.5 (chấm công), nhưng
                //     HrService dùng 21.5 (+ 2 công lễ) tính lương 5.142.635đ.
                //     Preview hiện 19.5/26 nhưng tiền tính theo 21.5 → user
                //     thấy "19.5 công sao được 5.1 triệu".
                //   - Tuấn Tài: entry.actualDays = 18 nhưng override = 19.
                // Giờ hiển thị số HrService đã dùng → tiền và công khớp nhau.
                Double displayDays = (s != null && s.getActualWorkdays() != null)
                        ? s.getActualWorkdays()
                        : e.getActualDays();
                rb.standardDays(standardWorkdaysOf(month, year))
                        .actualDays(displayDays)
                        .presentDays(e.getPresentDays())
                        .lateCount(e.getLateCount()).lateMinutes(e.getLateMinutes())
                        .earlyCount(e.getEarlyCount()).earlyMinutes(e.getEarlyMinutes())
                        .employeeCode(e.getEmployeeCode());
            } else if (department.isAttendanceBased() && !exempt) {
                // SALES / ACCOUNTING / WAREHOUSE: file chấm công tuỳ chọn. Không có file
                // thì HrService đã trả full lương + phụ cấp cơm đủ công chuẩn — cột
                // "ngày công" cũng phải hiển thị đủ để bảng không mâu thuẫn với cột
                // lương (0 công nhưng lương full trông như lỗi).
                //
                // FACTORY bắt buộc có file (Phase 1: MANAGEMENT đã gỡ), nên vào
                // nhánh này chỉ khi nhân viên vắng toàn tháng → giữ 0 công.
                double std = standardWorkdaysOf(month, year);
                boolean fileOptional = department == PayrollDepartment.SALES
                        || department == PayrollDepartment.ACCOUNTING
                        || department == PayrollDepartment.WAREHOUSE;
                rb.standardDays(std)
                        .actualDays(fileOptional ? std : 0.0)
                        .presentDays(fileOptional ? (int) Math.round(std) : 0)
                        .lateCount(0).lateMinutes(0).earlyCount(0).earlyMinutes(0);
            }

            // Tài xế: thay ngày công bằng số km + số đơn
            if (!department.isAttendanceBased()) {
                DriverMonthDto dm = driverKmService.monthOf(u, month, year);
                rb.totalKm(dm.getTotalKm()).totalOrders(dm.getTotalOrders());
            }

            // ── netReceived — KHỚP với bank/salary export ─────────────────
            //   = roundUpToThousand( baseSalary + Σ visible allowances )
            //   Visible: tất cả allowance của breakdown trừ các khoản auto-
            //   generated từ hỗ trợ giao hàng và (với tài xế) phụ cấp xăng xe.
            long baseForNet = nz(s.getBaseSalary());
            long allowanceSum = 0L;
            if (s.getAllowances() != null) {
                for (var a : s.getAllowances()) {
                    if (a == null || a.getLabel() == null) continue;
                    String label = a.getLabel();
                    // Bỏ auto-generated km/giao hàng — khớp isAutoGeneratedDeliveryAllowance
                    // ở BankPaymentExportService (keyword "km ×" / "lượt ×" trong nhãn).
                    if (label.contains("km ×") || label.contains("lượt ×")) continue;
                    // Tài xế: bỏ phụ cấp xăng xe (đã tính riêng ở file thưởng)
                    String base = label.replaceAll("\\s*\\(.*\\)\\s*", "").trim();
                    if (!department.isAttendanceBased()
                            && "Phụ cấp xăng xe".equals(base)) continue;
                    allowanceSum += nz(a.getAmount());
                }
            }
            long net = com.nhatnam.server.utils.ManualAttendanceOverrides
                    .roundUpToThousand(baseForNet + allowanceSum);
            rb.netReceived(net);

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
                .kpiFinalized(sheet != null && sheet.isKpiFinalized())
                .attendanceBased(department.isAttendanceBased())
                .hasKpiBonus(department.isKpiBonus())
                .employeeCount(rows.size())
                .totalNetSalary(totalNet)
                .totalGrossSalary(totalGross)
                .totalKpiBonus(sheet != null && sheet.isKpiFinalized() ? totalKpi : 0L)
                .kpiComputed(kpi != null && sheet != null && sheet.isKpiFinalized())
                .rows(rows);

        if (kpi != null && sheet != null && sheet.isKpiFinalized()) {
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
    public void validateViewPeriod(int month, int year, PayrollDepartment department) {
        YearMonth target = YearMonth.of(year, month);
        YearMonth current = YearMonth.now(VN);

        // Tháng đã qua → luôn cho phép
        if (target.isBefore(current)) {
            return;
        }

        // Tháng hiện tại hoặc tương lai → chỉ cho phép nếu đã chốt
        if (department != null) {
            AttendanceSheet sheet = sheetRepo
                    .findByMonthAndYearAndDepartment(month, year, department)
                    .orElse(null);
            if (sheet != null && sheet.isFinalized()) {
                return; // Đã chốt → cho phép xem
            }
        }

        throw new IllegalArgumentException(
                "Tháng %d/%d chưa được chốt hoặc chưa kết thúc.".formatted(month, year));
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

    // ══════════════════════════════════════════════════════════════════════════
    // BONUS TREND — biến động thưởng 12 tháng (10/2026)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Trend 12 tháng gần nhất cho 1 phòng.
     * KHÔNG tính lại — chỉ đọc data đã lưu (office_bonus_result, sales_bonus_override,
     * factory_kpi_bonus, attendance_sheet.driver_bonus_cached_json).
     */
    @Transactional(readOnly = true)
    public BonusTrendDto getBonusTrend(PayrollDepartment dept) {
        // (10/2026) Hiển thị 12 tháng, cột phải nhất = THÁNG HIỆN TẠI − 1
        // (thưởng tháng hiện tại chưa tính xong).
        java.time.YearMonth newest = java.time.YearMonth.now(VN).minusMonths(1);
        java.time.YearMonth start  = newest.minusMonths(11);

        List<BonusTrendMonthDto> months = new ArrayList<>(12);
        Map<Long, BonusTrendUserDto> userIndex = new LinkedHashMap<>();

        for (int i = 0; i < 12; i++) {
            java.time.YearMonth ym = start.plusMonths(i);
            int m = ym.getMonthValue(); int y = ym.getYear();
            Map<String, Object> stats = new LinkedHashMap<>();
            boolean hasData = false;

            switch (dept) {
                case SALES -> hasData = buildSalesTrendMonth(m, y, stats, userIndex);
                case ACCOUNTING -> hasData = buildOfficeTrendMonth(m, y, PayrollDepartment.ACCOUNTING, stats, userIndex);
                case DRIVER -> hasData = buildDriverTrendMonth(m, y, stats, userIndex);
                case FACTORY -> hasData = buildFactoryTrendMonth(m, y, stats, userIndex);
                case WAREHOUSE -> hasData = buildWarehouseTrendMonth(m, y, stats, userIndex);
            }

            months.add(BonusTrendMonthDto.builder()
                    .month(m).year(y)
                    .stats(stats)
                    .published(hasData)
                    .build());
        }

        // Enrich: xác định isSupport + roleRank cho mỗi user theo dept đang xem
        List<BonusTrendUserDto> users = new ArrayList<>(userIndex.values());
        for (BonusTrendUserDto bu : users) {
            User u = userRepo.findById(bu.getUserId()).orElse(null);
            if (u == null) continue;
            PayrollDepartment userDept = deptResolver.departmentOf(u);
            boolean isSupport = userDept != null && userDept != dept;
            bu.setSupport(isSupport);
            if (isSupport) {
                bu.setSupportLabel(switch (dept) {
                    case SALES -> "hỗ trợ bán hàng";
                    case DRIVER -> "hỗ trợ giao hàng";
                    case FACTORY -> "hỗ trợ sản xuất";
                    case ACCOUNTING -> "hỗ trợ kế toán";
                    case WAREHOUSE -> "hỗ trợ kho";
                });
                bu.setRoleRank(9999);  // support staff đẩy xuống cuối
            } else {
                bu.setRoleRank(roleSortOrderIn(dept, u));
            }
        }

        // Sort: non-support theo roleRank ASC, tie-break latest-month DESC
        //       → sau đó support cuối cùng, sort theo latest-month DESC
        String lastKey = ymKey(newest.getMonthValue(), newest.getYear());
        users.sort((a, b) -> {
            boolean sA = Boolean.TRUE.equals(a.getSupport());
            boolean sB = Boolean.TRUE.equals(b.getSupport());
            if (sA != sB) return sA ? 1 : -1;
            if (!sA) {
                // Non-support: role rank asc
                int cmp = Integer.compare(
                        a.getRoleRank() != null ? a.getRoleRank() : 9999,
                        b.getRoleRank() != null ? b.getRoleRank() : 9999);
                if (cmp != 0) return cmp;
            }
            // Tie-break (hoặc support): latest-month desc
            long aLast = a.getAmountsByMonth() != null && a.getAmountsByMonth().get(lastKey) != null
                    ? a.getAmountsByMonth().get(lastKey) : 0L;
            long bLast = b.getAmountsByMonth() != null && b.getAmountsByMonth().get(lastKey) != null
                    ? b.getAmountsByMonth().get(lastKey) : 0L;
            if (aLast != bLast) return Long.compare(bLast, aLast);
            return (a.getFullName() != null ? a.getFullName() : "")
                    .compareTo(b.getFullName() != null ? b.getFullName() : "");
        });

        return BonusTrendDto.builder()
                .department(dept.name())
                .departmentLabel(dept.getLabel())
                .months(months)
                .users(users)
                .build();
    }

    private String ymKey(int month, int year) {
        return String.format("%d-%02d", year, month);
    }

    /**
     * BONUS SUMMARY — tổng hợp thưởng 12 tháng, group theo phòng → sort chức vụ.
     * Mỗi NV = SUM thưởng từ TẤT CẢ nguồn (nếu NV kiêm hỗ trợ liên phòng).
     */
    @Transactional(readOnly = true)
    public BonusSummaryDto getBonusSummary() {
        // Thứ tự phòng theo yêu cầu: KD → SX → KT → Kho → TX
        PayrollDepartment[] DEPT_ORDER = {
                PayrollDepartment.SALES,
                PayrollDepartment.FACTORY,
                PayrollDepartment.ACCOUNTING,
                PayrollDepartment.WAREHOUSE,
                PayrollDepartment.DRIVER,
        };

        // 1) Lấy trend từng phòng → gộp amount per user
        Map<Long, Map<String, Long>> userTotals = new LinkedHashMap<>();  // userId → monthKey → sum
        List<BonusTrendMonthDto> monthsRef = null;

        for (PayrollDepartment d : DEPT_ORDER) {
            BonusTrendDto t = getBonusTrend(d);
            if (monthsRef == null) monthsRef = t.getMonths();
            for (BonusTrendUserDto u : t.getUsers()) {
                if (u.getUserId() == null || u.getAmountsByMonth() == null) continue;
                Map<String, Long> totals = userTotals.computeIfAbsent(u.getUserId(),
                        k -> new LinkedHashMap<>());
                u.getAmountsByMonth().forEach((k, v) -> {
                    if (v != null && v > 0) totals.merge(k, v, Long::sum);
                });
            }
        }

        // 2) Group users theo PHÒNG CHÍNH + sort theo role rank
        Map<PayrollDepartment, List<BonusTrendUserDto>> byDept = new LinkedHashMap<>();
        for (PayrollDepartment d : DEPT_ORDER) byDept.put(d, new ArrayList<>());
        List<BonusTrendUserDto> orphans = new ArrayList<>();

        for (Map.Entry<Long, Map<String, Long>> en : userTotals.entrySet()) {
            Long uid = en.getKey();
            User u = userRepo.findById(uid).orElse(null);
            if (u == null) continue;
            PayrollDepartment mainDept = deptResolver.departmentOf(u);
            BonusTrendUserDto row = BonusTrendUserDto.builder()
                    .userId(uid)
                    .fullName(u.getFullName())
                    .roleLabel(deptResolver.roleLabelOf(u))
                    .deleted(u.isDeleted())
                    .locked(u.isLockAccount())
                    .amountsByMonth(en.getValue())
                    .roleRank(mainDept != null ? roleSortOrderIn(mainDept, u) : 9999)
                    .build();
            if (mainDept != null && byDept.containsKey(mainDept)) byDept.get(mainDept).add(row);
            else orphans.add(row);
        }

        // Sort trong từng phòng theo roleRank asc, tie-break theo tên
        for (List<BonusTrendUserDto> list : byDept.values()) {
            list.sort((a, b) -> {
                int cmp = Integer.compare(a.getRoleRank() != null ? a.getRoleRank() : 9999,
                        b.getRoleRank() != null ? b.getRoleRank() : 9999);
                if (cmp != 0) return cmp;
                return (a.getFullName() != null ? a.getFullName() : "")
                        .compareTo(b.getFullName() != null ? b.getFullName() : "");
            });
        }

        // 3) Build groups + totals
        List<BonusSummaryGroupDto> groups = new ArrayList<>();
        Map<String, Long> grandTotals = new LinkedHashMap<>();
        for (PayrollDepartment d : DEPT_ORDER) {
            List<BonusTrendUserDto> rows = byDept.get(d);
            Map<String, Long> deptTotals = new LinkedHashMap<>();
            for (BonusTrendUserDto r : rows) {
                r.getAmountsByMonth().forEach((k, v) -> {
                    deptTotals.merge(k, v, Long::sum);
                    grandTotals.merge(k, v, Long::sum);
                });
            }
            groups.add(BonusSummaryGroupDto.builder()
                    .department(d.name())
                    .departmentLabel(d.getLabel())
                    .users(rows)
                    .totalsByMonth(deptTotals)
                    .build());
        }

        return BonusSummaryDto.builder()
                .months(monthsRef != null ? monthsRef : new ArrayList<>())
                .groups(groups)
                .grandTotalsByMonth(grandTotals)
                .build();
    }

    private BonusTrendUserDto ensureUser(Map<Long, BonusTrendUserDto> idx, User u) {
        if (u == null) return null;
        return idx.computeIfAbsent(u.getId(), id -> BonusTrendUserDto.builder()
                .userId(u.getId())
                .fullName(u.getFullName())
                .roleLabel(deptResolver.roleLabelOf(u))
                .deleted(u.isDeleted())
                .locked(u.isLockAccount())
                .amountsByMonth(new LinkedHashMap<>())
                .build());
    }

    private boolean buildSalesTrendMonth(int month, int year,
                                         Map<String, Object> stats,
                                         Map<Long, BonusTrendUserDto> userIndex) {
        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, PayrollDepartment.SALES)
                .orElse(null);
        if (sheet != null) {
            if (sheet.getSalesBonusDeptRevenue() != null) stats.put("deptRevenue", sheet.getSalesBonusDeptRevenue());
            if (sheet.getSalesBonusTarget() != null) stats.put("target", sheet.getSalesBonusTarget());
            if (sheet.getSalesBonusStepUnitPrice() != null) stats.put("stepUnitPrice", sheet.getSalesBonusStepUnitPrice());
            if (sheet.getSalesBonusStepAmount() != null) stats.put("stepAmount", sheet.getSalesBonusStepAmount());
        }
        List<SalesBonusOverride> overrides = salesBonusOverrideRepo.findByMonthAndYear(month, year);
        if (overrides.isEmpty()) return false;
        long pool = 0L;
        for (SalesBonusOverride ov : overrides) {
            if (ov.getUser() == null || ov.getTotalBonus() == null || ov.getTotalBonus() <= 0) continue;
            BonusTrendUserDto u = ensureUser(userIndex, ov.getUser());
            if (u != null) u.getAmountsByMonth().put(ymKey(month, year), ov.getTotalBonus());
            pool += ov.getTotalBonus();
        }
        stats.put("totalPool", pool);
        return true;
    }

    private boolean buildOfficeTrendMonth(int month, int year, PayrollDepartment dept,
                                          Map<String, Object> stats,
                                          Map<Long, BonusTrendUserDto> userIndex) {
        OfficeBonusResult result = officeBonusResultRepo
                .findByMonthAndYearAndDepartment(month, year, dept)
                .orElse(null);
        if (result == null) return false;

        if (result.getTotalRevenue() != null)
            stats.put("deptRevenue", result.getTotalRevenue().setScale(0, RoundingMode.HALF_UP).longValueExact());
        if (result.getTotalBonusPool() != null)
            stats.put("totalPool", result.getTotalBonusPool());
        // (10/2026) ACCOUNTING: thêm commissionPercent dept-wide
        if (dept == PayrollDepartment.ACCOUNTING && result.getAccountingPercent() != null)
            stats.put("commissionPercent", result.getAccountingPercent().doubleValue());

        String key = ymKey(month, year);
        for (OfficeBonusItem item : result.getItems()) {
            if (item.getUser() == null || item.getBonusAmount() == null || item.getBonusAmount() <= 0) continue;
            BonusTrendUserDto u = ensureUser(userIndex, item.getUser());
            if (u != null) {
                u.getAmountsByMonth().put(key, item.getBonusAmount());
                // Per-user ratioPercent chỉ hiện cho ACCOUNTING
                if (dept == PayrollDepartment.ACCOUNTING && item.getRatioPercent() != null) {
                    if (u.getRatioByMonth() == null) u.setRatioByMonth(new LinkedHashMap<>());
                    u.getRatioByMonth().put(key, item.getRatioPercent().doubleValue());
                }
            }
        }
        return true;
    }

    private boolean buildDriverTrendMonth(int month, int year,
                                          Map<String, Object> stats,
                                          Map<Long, BonusTrendUserDto> userIndex) {
        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, PayrollDepartment.DRIVER)
                .orElse(null);
        boolean published = sheet != null && (sheet.isBonusFinalized()
                || sheet.getBonusCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED);
        if (sheet != null) {
            if (sheet.getDriverGasPrice() != null) stats.put("gasPrice", sheet.getDriverGasPrice());
            if (sheet.getDriverBonusUnitPrice() != null) stats.put("bikeUnitPrice", sheet.getDriverBonusUnitPrice());
            if (sheet.getDriverTruckBonusUnitPrice() != null) stats.put("truckUnitPrice", sheet.getDriverTruckBonusUnitPrice());
        }
        if (sheet == null || sheet.getDriverBonusCachedJson() == null
                || sheet.getDriverBonusCachedJson().isBlank()) return false;

        try {
            DriverPayrollConfigDto cfg = MAPPER.readValue(sheet.getDriverBonusCachedJson(),
                    DriverPayrollConfigDto.class);
            long pool = 0L;
            java.util.List<DriverSalaryRowDto> all = new java.util.ArrayList<>();
            if (cfg.getRows() != null) all.addAll(cfg.getRows());
            if (cfg.getNonDriverRows() != null) all.addAll(cfg.getNonDriverRows());
            for (DriverSalaryRowDto row : all) {
                if (row.getUserId() == null) continue;
                long raw = row.getGrandTotalSalary() != null ? row.getGrandTotalSalary() : 0L;
                // (10/2026) Làm tròn LÊN bước 5.000
                long amount = roundUpTo(raw, 5_000L);
                if (amount <= 0) continue;
                User u = userRepo.findById(row.getUserId()).orElse(null);
                if (u == null) continue;
                BonusTrendUserDto d = ensureUser(userIndex, u);
                if (d != null) d.getAmountsByMonth().put(ymKey(month, year), amount);
                pool += amount;
            }
            stats.put("totalPool", pool);
        } catch (Exception e) {
            log.warn("[Trend] Parse driver cache {}/{} lỗi: {}", month, year, e.getMessage());
            return false;
        }
        return published;
    }

    /**
     * (10/2026) Warehouse Trend — luôn hiển thị TẤT CẢ NV phòng Kho (ko tính thưởng),
     * amount lấy từ warehouse_bonus (OWNER nhập tay).
     */
    private boolean buildWarehouseTrendMonth(int month, int year,
                                             Map<String, Object> stats,
                                             Map<Long, BonusTrendUserDto> userIndex) {
        // Luôn thêm toàn bộ NV phòng Kho vào index (kể cả tháng ko có thưởng)
        List<User> whMembers = deptResolver.employeesOf(PayrollDepartment.WAREHOUSE);
        for (User u : whMembers) ensureUser(userIndex, u);

        // (10/2026) Hiển thị amount cả draft (CALCULATED) + PUBLISHED
        //   (NV chỉ thấy phiếu khi PUBLISHED — xem getMyPayslip, nhưng admin
        //   biến động muốn thấy cả nháp để kiểm tra).
        long pool = 0L;
        for (WarehouseBonus wb : warehouseBonusRepo.findByMonthAndYear(month, year)) {
            if (wb.getUser() == null || wb.getAmount() == null || wb.getAmount() <= 0) continue;
            BonusTrendUserDto u = ensureUser(userIndex, wb.getUser());
            if (u != null) u.getAmountsByMonth().put(ymKey(month, year), wb.getAmount());
            pool += wb.getAmount();
        }
        stats.put("totalPool", pool);
        return pool > 0;
    }

    /**
     * UPSERT thưởng kho cho 1 NV trong 1 tháng.
     * Chỉ cho NV đang thuộc phòng Kho.
     * Mark sheet WAREHOUSE = CALCULATED (chưa Public).
     */
    @Transactional
    public void upsertWarehouseBonus(Long userId, int month, int year, long amount, User actor) {
        User u = userRepo.findById(userId).orElseThrow(() ->
                new IllegalArgumentException("Không tìm thấy NV id=" + userId));
        PayrollDepartment d = deptResolver.departmentOf(u);
        if (d != PayrollDepartment.WAREHOUSE)
            throw new IllegalArgumentException("NV không thuộc phòng Kho");

        WarehouseBonus wb = warehouseBonusRepo
                .findByUser_IdAndMonthAndYear(userId, month, year)
                .orElse(WarehouseBonus.builder().user(u).month(month).year(year).build());
        wb.setAmount(Math.max(0, amount));
        wb.setUpdatedByName(actor != null ? actor.getFullName() : "SYSTEM");
        warehouseBonusRepo.save(wb);

        // Mark sheet WAREHOUSE = CALCULATED (chưa Public — chưa hiện cho NV)
        AttendanceSheet sheet = sheetOf(month, year, PayrollDepartment.WAREHOUSE);
        if (sheet.getBonusCalcStatus() != com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED) {
            sheet.markBonusCalculated(actor != null ? actor.getFullName() : "SYSTEM");
            sheetRepo.save(sheet);
        }
    }

    /**
     * PUBLIC thưởng kho tháng — bấm "Hoàn tất".
     * NV phòng Kho sẽ thấy phiếu thưởng tháng này.
     */
    @Transactional
    public void publishWarehouseBonus(int month, int year, User actor) {
        AttendanceSheet sheet = sheetOf(month, year, PayrollDepartment.WAREHOUSE);
        sheet.markBonusPublished(actor != null ? actor.getFullName() : "SYSTEM");
        sheetRepo.save(sheet);
        log.info("[Payroll] Public thưởng KHO {}/{} bởi {}", month, year,
                actor != null ? actor.getFullName() : "SYSTEM");
    }

    /** UNPUBLIC thưởng kho — chuyển về CALCULATED, NV không còn thấy. */
    @Transactional
    public void unpublishWarehouseBonus(int month, int year, User actor) {
        AttendanceSheet sheet = sheetOf(month, year, PayrollDepartment.WAREHOUSE);
        sheet.markBonusUnpublished();
        sheetRepo.save(sheet);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ATTENDANCE BONUS (10/2026) — thưởng chuyên cần per-user per-month
    // ══════════════════════════════════════════════════════════════════════

    /**
     * List NV theo phòng + sort theo chức vụ (dành cho modal "Thưởng chuyên cần").
     * Bao gồm cả NV đã nghỉ/khoá để OWNER vẫn thấy data cũ.
     * Trả về cả amount tháng hiện tại + amount tháng gần nhất trước đó (placeholder).
     */
    @Transactional(readOnly = true)
    public List<AttendanceBonusRowDto> listAttendanceBonusForMonth(int month, int year) {
        PayrollDepartment[] DEPT_ORDER = {
                PayrollDepartment.SALES,
                PayrollDepartment.FACTORY,
                PayrollDepartment.ACCOUNTING,
                PayrollDepartment.WAREHOUSE,
                PayrollDepartment.DRIVER,
        };
        // Snapshot tháng hiện tại
        Map<Long, Long> currentAmounts = new HashMap<>();
        for (AttendanceBonus ab : attendanceBonusRepo.findByMonthAndYear(month, year)) {
            if (ab.getUser() != null) currentAmounts.put(ab.getUser().getId(), ab.getAmount());
        }

        List<AttendanceBonusRowDto> result = new ArrayList<>();
        for (PayrollDepartment d : DEPT_ORDER) {
            List<User> members = deptResolver.employeesWithLockedOf(d).stream()
                    .sorted(Comparator.comparingInt((User u) -> roleSortOrderIn(d, u))
                            .thenComparing(User::getFullName))
                    .toList();
            for (User u : members) {
                Long cur = currentAmounts.get(u.getId());
                Long prev = findPreviousAttendanceBonusAmount(u.getId(), month, year);
                result.add(AttendanceBonusRowDto.builder()
                        .userId(u.getId())
                        .fullName(u.getFullName())
                        .roleLabel(deptResolver.roleLabelOf(u))
                        .department(d.name())
                        .departmentLabel(d.getLabel())
                        .deleted(u.isDeleted())
                        .locked(u.isLockAccount())
                        .amount(cur != null ? cur : 0L)
                        .previousAmount(prev != null ? prev : 0L)
                        .build());
            }
        }
        return result;
    }

    private Long findPreviousAttendanceBonusAmount(Long userId, int month, int year) {
        List<AttendanceBonus> list = attendanceBonusRepo.findLatestBefore(userId, month, year);
        if (list.isEmpty()) return null;
        AttendanceBonus latest = list.get(0);
        return latest.getAmount();
    }

    @Transactional
    public void upsertAttendanceBonus(Long userId, int month, int year, long amount, User actor) {
        User u = userRepo.findById(userId).orElseThrow(() ->
                new IllegalArgumentException("Không tìm thấy NV id=" + userId));
        AttendanceBonus ab = attendanceBonusRepo
                .findByUser_IdAndMonthAndYear(userId, month, year)
                .orElse(AttendanceBonus.builder().user(u).month(month).year(year).build());
        ab.setAmount(Math.max(0, amount));
        ab.setUpdatedByName(actor != null ? actor.getFullName() : "SYSTEM");
        attendanceBonusRepo.save(ab);
    }

    /** Helper cho PDF: lookup amount per user cho 1 tháng. */
    @Transactional(readOnly = true)
    public long getAttendanceBonusAmount(Long userId, int month, int year) {
        return attendanceBonusRepo
                .findByUser_IdAndMonthAndYear(userId, month, year)
                .map(AttendanceBonus::getAmount)
                .orElse(0L);
    }

    private boolean buildFactoryTrendMonth(int month, int year,
                                           Map<String, Object> stats,
                                           Map<Long, BonusTrendUserDto> userIndex) {
        FactoryKpiBonus kpi = factoryKpiBonusRepo.findByMonthAndYear(month, year).orElse(null);
        if (kpi == null) return false;

        if (kpi.getTotalOutputKg() != null)
            stats.put("totalOutputKg", kpi.getTotalOutputKg().setScale(0, RoundingMode.HALF_UP).longValueExact());
        if (kpi.getRatePerTon() != null) stats.put("ratePerTon", kpi.getRatePerTon());
        if (kpi.getBonusPool() != null) stats.put("bonusPool", kpi.getBonusPool());

        for (FactoryKpiBonusItem item : factoryKpiBonusItemRepo.findByKpiBonus_Id(kpi.getId())) {
            if (item.getUser() == null || item.getAmount() == null || item.getAmount() <= 0) continue;
            BonusTrendUserDto u = ensureUser(userIndex, item.getUser());
            if (u != null) {
                u.getAmountsByMonth().put(ymKey(month, year), item.getAmount());
                // (10/2026) Set fixedAmount flag nếu bất kỳ tháng nào NV ở role fixed
                if (Boolean.TRUE.equals(item.getFixedAmountRole())) u.setFixedAmount(true);
            }
        }
        return true;
    }

    /**
     * Làm tròn LÊN tới bội số của {@code step} (step phải > 0).
     * VD roundUpTo(204_584, 5_000) = 205_000; roundUpTo(0, 5_000) = 0.
     */
    private static long roundUpTo(long v, long step) {
        if (step <= 0 || v <= 0) return Math.max(0, v);
        long rem = v % step;
        return rem == 0 ? v : v + (step - rem);
    }
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

    // ══════════════════════════════════════════════════════════════════════════
    // KPI & BONUS PLACEHOLDER — KẾ TOÁN VÀ KINH DOANH
    // ══════════════════════════════════════════════════════════════════════════


    // ══════════════════════════════════════════════════════════════════════════
    // HOÀN TẤT / MỞ LẠI THƯỞNG DOANH THU (chỉ SALES và ACCOUNTING)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Tính và chốt thưởng doanh thu cho SALES hoặc ACCOUNTING.
     *
     * <p>Flow:
     * <ol>
     *   <li>Xác định khoảng thời gian = đầu/cuối tháng (giờ VN).</li>
     *   <li>Query {@link PaymentTransactionRepository} lấy tổng tiền THỰC THU
     *       trong khoảng đó (chỉ đơn PENDING_PAYMENT / COMPLETED).</li>
     *   <li>Tính thưởng theo thang doanh thu.</li>
     *   <li>Xoá {@link OfficeBonusResult} cũ (nếu có) rồi ghi mới.</li>
     *   <li>Set {@code bonusFinalized = true} trên {@link AttendanceSheet}.</li>
     * </ol>
     *
     * <h3>Thang thưởng</h3>
     * <pre>
     * R ≥ 100tr  → bonusUnit = 400.000đ / 100tr (nhân với số lần 100tr, phần lẻ nội suy)
     * 70tr ≤ R &lt; 100tr → nội suy tuyến tính 0đ → 400.000đ (trong dải 70–100tr)
     * R &lt; 70tr   → 0đ (triển khai sau)
     * </pre>
     *
     * <h3>Phân chia</h3>
     * <ul>
     *   <li>ACCOUNTING: chia đều cho nhân viên có {@code receiveBonus = true}.</li>
     *   <li>SALES: mỗi seller tính riêng từ doanh thu đơn của họ.</li>
     * </ul>
     *
     * <p>Phải gọi sau khi đã {@link #finalizeKpi} (kpiFinalized = true).
     */
    @Transactional
    public OfficeBonusSummaryDto finalizeBonus(int month, int year,
                                               PayrollDepartment department, User actor) {
        return finalizeBonus(month, year, department, actor, (FinalizeBonusRequest) null);
    }

    /**
     * Legacy signature (giữ tương thích): nhận 1 unitPrice / 100tr.
     * Chuyển về {@link #finalizeBonus(int, int, PayrollDepartment, User, FinalizeBonusRequest)}
     * bằng cách gói unitPrice vào request body.
     */
    @Transactional
    public OfficeBonusSummaryDto finalizeBonus(int month, int year,
                                               PayrollDepartment department,
                                               User actor, Long unitPrice) {
        FinalizeBonusRequest req = unitPrice == null
                ? null
                : FinalizeBonusRequest.builder().unitPrice(unitPrice).build();
        return finalizeBonus(month, year, department, actor, req);
    }

    /**
     * FLOW MỚI 2026 — tính hoa hồng từ cấu hình OWNER nhập trong modal.
     *
     * <h3>SALES</h3> dùng {@code req.salesTiers}: tính per-seller, mốc cao
     * nhất trước. Phần dư không đủ mốc thấp nhất → bỏ. Số cuối cùng làm tròn
     * LÊN 5.000.
     *
     * <h3>ACCOUNTING</h3> dùng {@code req.accountingPercent} + {@code req.accountingRatios}:
     * <pre>
     *   pool_raw = collectedRevenue × accountingPercent / 100
     *   share_i  = pool_raw × ratio_i / 100  (ceil 5.000)
     *   dư       = pool_raw × (100 − Σ ratio) / 100   (lưu, không chia)
     * </pre>
     *
     * <h3>LOG (yêu cầu nghiệp vụ 2026)</h3> Mỗi lần tính, in log doanh thu
     * được chia hoa hồng + doanh thu đang hold + waived + pool + dư.
     *
     * <h3>Chống đếm trùng</h3> Doanh thu lấy từ {@code PaymentTransaction}
     * (ghi nhận từng lần thu). Mỗi lần thu được tính 1 lần duy nhất vào tháng
     * có {@code createdAt} rơi vào — KHÔNG dùng {@code paid_amount} cộng dồn.
     * Phần {@code waived} (đơn COMPLETED còn chênh) được cộng vào doanh thu
     * chia hoa hồng của tháng mà đơn được tạo (bỏ số lẻ 1 lần duy nhất).
     */
    @Transactional
    public OfficeBonusSummaryDto finalizeBonus(int month, int year,
                                               PayrollDepartment department,
                                               User actor, FinalizeBonusRequest req) {
        validatePastOrCurrentPeriod(month, year);

        if (department != PayrollDepartment.SALES && department != PayrollDepartment.ACCOUNTING)
            throw new IllegalArgumentException(
                    "Hoàn tất Thưởng chỉ áp dụng cho SALES và ACCOUNTING, không phải " + department.getLabel());

        AttendanceSheet sheet = sheetOf(month, year, department);

        // Biên tháng theo giờ VN
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        long startMs = ym.atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
        long endMs   = ym.plusMonths(1).atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();

        long now = System.currentTimeMillis();
        String actorName = actor != null ? actor.getFullName() : "system";

        // ── Chuẩn hoá request: tách rõ 3 flow ───────────────────────────
        //   (1) flow MỚI SALES   : req.salesTiers != empty
        //   (2) flow MỚI ACCOUNT : req.accountingPercent + accountingRatios
        //   (3) flow LEGACY      : req.unitPrice != null, các field mới null
        boolean hasTiers      = req != null && req.getSalesTiers() != null && !req.getSalesTiers().isEmpty();
        boolean hasAcctConfig = req != null && req.getAccountingPercent() != null
                && req.getAccountingRatios() != null && !req.getAccountingRatios().isEmpty();
        Long legacyUnitPrice  = req != null ? req.getUnitPrice() : null;
        if (legacyUnitPrice != null && legacyUnitPrice <= 0)
            throw new IllegalArgumentException("Đơn giá hoa hồng phải > 0.");

        // Xoá kết quả cũ trước khi tính lại
        officeBonusResultRepo.deleteByMonthAndYearAndDepartment(month, year, department);
        officeBonusResultRepo.flush();

        OfficeBonusResult result;
        List<OfficeBonusItem> items = new ArrayList<>();

        // Doanh thu tổng (cho log, giống preview)
        java.math.BigDecimal deptCollected = nzBd(paymentTransactionRepo.sumRevenueAllOrders(startMs, endMs));
        java.math.BigDecimal deptHold      = nzBd(orderRepository.sumHoldAmountCreatedInRange(startMs, endMs));
        java.math.BigDecimal deptWaived    = nzBd(orderRepository.sumWaivedAmountCreatedInRange(startMs, endMs));
        java.math.BigDecimal deptMonthOrd  = nzBd(orderRepository.sumFinalAmountCreatedInRange(startMs, endMs));

        if (department == PayrollDepartment.ACCOUNTING) {
            // ── Doanh thu để tính hoa hồng = ĐÃ THU + WAIVED (bỏ số lẻ) ──
            //   Phần waived là chênh của đơn COMPLETED đã được kế toán xác nhận
            //   "bỏ số lẻ". Theo nghiệp vụ: tính như đã thu để chia hoa hồng.
            java.math.BigDecimal totalRev = deptCollected.add(deptWaived);
            long txCount = paymentTransactionRepo.countTransactionsAllOrders(startMs, endMs);

            // Nhân viên kế toán có receiveBonus
            List<User> bonusEligible = deptResolver.employeesOf(PayrollDepartment.ACCOUNTING)
                    .stream()
                    .filter(u -> !Boolean.FALSE.equals(u.getReceiveBonus()))
                    .sorted(Comparator.comparingInt(u -> roleSortOrderIn(PayrollDepartment.ACCOUNTING, u)))
                    .toList();
            Map<Long, User> userById = bonusEligible.stream()
                    .collect(java.util.stream.Collectors.toMap(User::getId, u -> u, (a,b) -> a));

            long pool;
            long undistributed = 0L;
            String ratiosJson = null;
            java.math.BigDecimal acctPct = null;
            List<AccountingShareV2> shares;

            if (hasAcctConfig) {
                // ── FLOW MỚI: % phòng + tỉ lệ từng người ──
                acctPct = req.getAccountingPercent();
                if (acctPct.signum() <= 0)
                    throw new IllegalArgumentException("% hoa hồng phòng phải > 0.");
                if (acctPct.compareTo(new BigDecimal("100")) > 0)
                    throw new IllegalArgumentException("% hoa hồng phòng không được > 100.");

                List<UserRatio> userRatios = req.getAccountingRatios().stream()
                        .filter(ur -> ur.getUserId() != null && userById.containsKey(ur.getUserId()))
                        .map(ur -> new UserRatio(ur.getUserId(),
                                ur.getRatio() == null ? BigDecimal.ZERO : ur.getRatio()))
                        .toList();

                AccountingPoolResult poolRes =
                        OfficeBonusCommissionUtil.splitAccountingByRatios(totalRev, acctPct, userRatios);
                shares = poolRes.shares();
                pool = poolRes.distributedTotal();
                undistributed = poolRes.undistributed();

                try {
                    ratiosJson = MAPPER.writeValueAsString(req.getAccountingRatios());
                } catch (Exception ignored) {}

            } else if (legacyUnitPrice != null) {
                // ── LEGACY unitPrice/100tr: chia theo weight KTT=2, CV=1 ──
                java.math.BigDecimal rawPool =
                        OfficeBonusCommissionUtil.rawCommission(totalRev, legacyUnitPrice);
                List<UserWeight> weights = bonusEligible.stream()
                        .map(u -> new UserWeight(u.getId(),
                                OfficeBonusCommissionUtil.accountingWeightOf(deptResolver.payrollRoleOf(u))))
                        .toList();
                List<AccountingShare> legacyShares =
                        OfficeBonusCommissionUtil.splitAccountingPool(rawPool, weights);
                shares = legacyShares.stream()
                        .map(s -> new AccountingShareV2(s.userId(), s.bonusAmount(),
                                s.bonusAmount(), null))
                        .toList();
                pool = shares.stream().mapToLong(AccountingShareV2::bonusAmount).sum();

            } else {
                // ── LEGACY tier-based bonus: chia đều ──
                long tierPool = calcBonusFromRevenue(totalRev);
                long perPerson = bonusEligible.isEmpty() ? 0L
                        : (tierPool / bonusEligible.size() / 100_000) * 100_000;
                pool = perPerson * (long) bonusEligible.size();
                final long pp = perPerson;
                shares = bonusEligible.stream()
                        .map(u -> new AccountingShareV2(u.getId(), pp, pp, null))
                        .toList();
            }

            final java.math.BigDecimal finalTotalRev = totalRev;
            final long finalTxCount = txCount;
            final long finalUndist = undistributed;
            final String finalRatiosJson = ratiosJson;
            final java.math.BigDecimal finalAcctPct = acctPct;

            result = OfficeBonusResult.builder()
                    .month(month).year(year).department(department)
                    .totalRevenue(finalTotalRev)
                    .totalBonusPool(pool)
                    .commissionUnitPrice(legacyUnitPrice)        // null khi flow mới
                    .accountingPercent(finalAcctPct)
                    .accountingRatiosJson(finalRatiosJson)
                    .undistributedAmount(finalUndist)
                    .transactionCount((int) finalTxCount)
                    .computedAt(now).computedByName(actorName)
                    .build();
            result = officeBonusResultRepo.save(result);

            Map<Long, AccountingShareV2> byUser = new HashMap<>();
            for (AccountingShareV2 s : shares) byUser.put(s.userId(), s);

            for (User u : bonusEligible) {
                AccountingShareV2 s = byUser.get(u.getId());
                OfficeBonusItem item = OfficeBonusItem.builder()
                        .bonusResult(result)
                        .user(u)
                        .userFullName(u.getFullName())
                        .roleLabel(deptResolver.roleLabelOf(u))
                        .revenue(finalTotalRev)
                        .bonusAmount(s != null ? s.bonusAmount() : 0L)
                        .rawBonusAmount(s != null ? s.rawShare() : 0L)
                        .ratioPercent(s != null ? s.ratio() : null)
                        .kpiPercent(100.0)
                        .transactionCount((int) finalTxCount)
                        .build();
                items.add(item);
            }

        } else {
            // ───────────────────── SALES ─────────────────────
            List<User> sellers = buildSalesBonusEligible(startMs, endMs);

            // Config
            List<SalesTier> tiers = null;
            String tiersJson = null;
            if (hasTiers) {
                tiers = req.getSalesTiers().stream()
                        .filter(t -> t.getThreshold() != null && t.getUnitPrice() != null
                                && t.getThreshold().signum() > 0 && t.getUnitPrice() > 0)
                        .map(t -> new SalesTier(t.getThreshold(), t.getUnitPrice()))
                        .toList();
                if (tiers.isEmpty())
                    throw new IllegalArgumentException("Cần ít nhất 1 mốc thưởng hợp lệ (threshold > 0, unitPrice > 0).");
                try {
                    tiersJson = MAPPER.writeValueAsString(req.getSalesTiers());
                } catch (Exception ignored) {}
            }

            java.math.BigDecimal deptTotalRev = java.math.BigDecimal.ZERO;
            long deptPool = 0L;
            int  deptTxCount = 0;
            long deptUndistributed = 0L;      // Σ unmatchedRevenue chuyển sang long để log

            result = OfficeBonusResult.builder()
                    .month(month).year(year).department(department)
                    .commissionUnitPrice(legacyUnitPrice)
                    .salesTiersJson(tiersJson)
                    .computedAt(now).computedByName(actorName)
                    .build();
            result = officeBonusResultRepo.save(result);

            for (User u : sellers) {
                // Doanh thu chia hoa hồng cho seller = ĐÃ THU + WAIVED (bỏ số lẻ)
                java.math.BigDecimal sellerCollected =
                        nzBd(paymentTransactionRepo.sumRevenueByUser(startMs, endMs, u.getId()));
                java.math.BigDecimal sellerWaived =
                        nzBd(orderRepository.sumWaivedAmountCreatedInRangeByUser(startMs, endMs, u.getId()));
                java.math.BigDecimal sellerRev = sellerCollected.add(sellerWaived);
                long sellerTx = paymentTransactionRepo.countTransactionsByUser(startMs, endMs, u.getId());

                long sellerBonus;
                long sellerRaw;
                if (hasTiers) {
                    SalesBonusResult br = OfficeBonusCommissionUtil.salesCommissionTiered(sellerRev, tiers);
                    sellerBonus = br.bonusAmount();
                    sellerRaw   = br.rawBonus();
                    // Cộng doanh thu không đủ mốc vào tổng dư của phòng
                    if (br.unmatchedRevenue() != null)
                        deptUndistributed += br.unmatchedRevenue().setScale(0, RoundingMode.HALF_UP).longValueExact();
                } else if (legacyUnitPrice != null) {
                    sellerBonus = OfficeBonusCommissionUtil.salesCommission(sellerRev, legacyUnitPrice);
                    sellerRaw   = sellerBonus;
                } else {
                    long raw = calcBonusFromRevenue(sellerRev);
                    sellerBonus = (raw / 100_000) * 100_000;
                    sellerRaw   = raw;
                }

                deptTotalRev = deptTotalRev.add(sellerRev);
                deptPool    += sellerBonus;
                deptTxCount += (int) sellerTx;

                OfficeBonusItem item = OfficeBonusItem.builder()
                        .bonusResult(result)
                        .user(u)
                        .userFullName(u.getFullName())
                        .roleLabel(deptResolver.roleLabelOf(u))
                        .revenue(sellerRev)
                        .bonusAmount(sellerBonus)
                        .rawBonusAmount(sellerRaw)
                        .kpiPercent(100.0)
                        .transactionCount((int) sellerTx)
                        .build();
                items.add(item);
            }

            result.setTotalRevenue(deptTotalRev);
            result.setTotalBonusPool(deptPool);
            result.setTransactionCount(deptTxCount);
            result.setUndistributedAmount(deptUndistributed);
            result = officeBonusResultRepo.save(result);
        }

        // Lưu items
        final OfficeBonusResult savedResult = result;
        items.forEach(i -> i.setBonusResult(savedResult));
        savedResult.getItems().addAll(items);
        officeBonusResultRepo.save(savedResult);

        // (10/2026) Chỉ đánh dấu CALCULATED — chưa Public cho nhân viên thấy.
        // OWNER sẽ bấm "Public thưởng" ở bước riêng để nhân viên xem phiếu.
        // bonusFinalized vẫn giữ = false ở bước này; chỉ bật = true khi Public.
        sheet.markBonusCalculated(actorName);
        sheetRepo.save(sheet);

        // ─────────────────────────── LOG (yêu cầu nghiệp vụ) ───────────────────────────
        log.info("[Bonus] ╔══════════════════════════════════════════════════════════════════");
        log.info("[Bonus] ║ TÍNH HOA HỒNG {} tháng {}/{} — actor: {}",
                department, month, year, actorName);
        log.info("[Bonus] ║ Doanh thu đã thu EX-VAT (chia HH)  = {}đ",
                fmtVn(savedResult.getTotalRevenue()));
        log.info("[Bonus] ║ Doanh thu đang hold EX-VAT         = {}đ",
                fmtVn(deptHold));
        log.info("[Bonus] ║ Doanh thu waived EX-VAT            = {}đ (đã gộp vào \"đã thu\")",
                fmtVn(deptWaived));
        log.info("[Bonus] ║ Doanh thu đơn tạo trong tháng EX-VAT = {}đ",
                fmtVn(deptMonthOrd));
        log.info("[Bonus] ║ Pool chia                          = {}đ",
                String.format("%,d", savedResult.getTotalBonusPool()));
        log.info("[Bonus] ║ Số dư (không chia cho ai)          = {}đ",
                String.format("%,d", savedResult.getUndistributedAmount() == null
                        ? 0L : savedResult.getUndistributedAmount()));
        if (savedResult.getSalesTiersJson() != null)
            log.info("[Bonus] ║ Config SALES tiers              = {}", savedResult.getSalesTiersJson());
        if (savedResult.getAccountingPercent() != null)
            log.info("[Bonus] ║ Config ACCOUNTING percent       = {}%", savedResult.getAccountingPercent());
        if (savedResult.getAccountingRatiosJson() != null)
            log.info("[Bonus] ║ Config ACCOUNTING ratios        = {}", savedResult.getAccountingRatiosJson());
        log.info("[Bonus] ╚══════════════════════════════════════════════════════════════════");

        return toOfficeBonusSummaryDto(savedResult);
    }

    private static String fmtVn(java.math.BigDecimal v) {
        if (v == null) return "0";
        return String.format("%,.0f", v);
    }

    /**
     * Thứ tự sort theo chức vụ trong 1 bộ phận — dùng trong finalizeBonus +
     * getOfficeBonusPreview để KTT luôn đứng trước chuyên viên, trưởng phòng
     * trước nhân viên. Role không có → cuối bảng.
     */
    private int roleSortOrderIn(PayrollDepartment dept, User u) {
        Role r = deptResolver.payrollRoleOf(u);
        if (r == null) return Integer.MAX_VALUE;
        int idx = dept.getRoles().indexOf(r);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    /**
     * Danh sách người được chia HOA HỒNG KINH DOANH trong 1 tháng:
     * <ul>
     *   <li>Nhân viên phòng Kinh doanh (có {@code receiveBonus != false}) —
     *       luôn hiển thị dù trong tháng không có đơn.</li>
     *   <li>Nhân viên NGOÀI phòng Kinh doanh (vd Kho) có TẠO ít nhất 1 đơn
     *       trong tháng — lương vẫn tính theo phòng gốc, nhưng thưởng doanh
     *       thu tính ở đây.</li>
     * </ul>
     *
     * <p>Sort: SELLER theo thứ tự chức vụ (Trưởng phòng → NV), rồi đến extras
     * (các bộ phận khác có tạo đơn) sort theo tên.
     */
    private List<User> buildSalesBonusEligible(long startMs, long endMs) {
        // 1) SALES core — luôn có
        List<User> salesCore = deptResolver.employeesOf(PayrollDepartment.SALES).stream()
                .filter(u -> !Boolean.FALSE.equals(u.getReceiveBonus()))
                .sorted(Comparator.comparingInt(u -> roleSortOrderIn(PayrollDepartment.SALES, u)))
                .toList();
        Set<Long> salesIds = salesCore.stream().map(User::getId).collect(java.util.stream.Collectors.toSet());

        // 2) Extras — nhân viên ngoài SALES có tạo đơn trong tháng
        //    Load tất cả qua findById và filter: khoá/xoá/no-dept/no-receive-bonus bị loại.
        //    Người không có PayrollDepartment (OWNER/ADMIN/HR...) cũng loại — họ không
        //    nhận thưởng doanh thu kể cả khi tạo đơn hộ.
        List<Long> creatorIds = orderRepository.findDistinctOrderCreatorsInRange(startMs, endMs);
        List<User> extras = creatorIds.stream()
                .filter(id -> !salesIds.contains(id))
                .map(id -> userRepo.findById(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(u -> !u.isLockAccount())
                .filter(u -> !u.isDeleted())
                .filter(u -> !Boolean.FALSE.equals(u.getReceiveBonus()))
                .filter(u -> deptResolver.departmentOf(u) != null)
                .sorted(Comparator.comparing(u -> u.getFullName() != null ? u.getFullName() : ""))
                .toList();

        List<User> result = new ArrayList<>(salesCore);
        result.addAll(extras);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SALES BONUS v2 — target + 1 mốc + override per-seller (10/2026)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TÍNH THƯỞNG SALES V2 — tạo preview để OWNER review trước khi Public.
     *
     * <p>Flow:
     * <ol>
     *   <li>OWNER bấm "Tính thưởng" → modal nhập 3 input: target, stepAmount, stepUnitPrice.</li>
     *   <li>BE chạy hàm này: tính doanh thu phòng, so với target → multiplier (1.0 hoặc 0.5).</li>
     *   <li>Mỗi seller: steps = floor(revenue/stepAmount); remainder = revenue - steps×stepAmount.</li>
     *   <li>Default row: stepUnitPrice × multiplier, remainderBonus = 0.</li>
     *   <li>Upsert vào {@code sales_bonus_override} (giữ owner-edited rows nếu đã có).</li>
     *   <li>Mark sheet CALCULATED (chưa Public).</li>
     *   <li>Trả về preview để FE mở ReviewModal.</li>
     * </ol>
     *
     * <p>Lưu config (target/stepAmount/stepUnitPrice/multiplier) vào sheet để
     * mở lại tháng cũ vẫn thấy state cũ.
     */
    @Transactional
    public SalesBonusPreviewDto previewSalesBonusV2(int month, int year,
                                                    long target, long stepAmount,
                                                    long stepUnitPrice,
                                                    List<SalesBonusTierDto> tiers,
                                                    User actor) {
        if (stepAmount <= 0) throw new IllegalArgumentException("Bước doanh thu phải > 0.");
        if (stepUnitPrice <= 0) throw new IllegalArgumentException("Đơn giá/bước phải > 0.");
        if (target < 0) throw new IllegalArgumentException("Target không âm.");

        PayrollDepartment dept = PayrollDepartment.SALES;
        AttendanceSheet sheet = sheetOf(month, year, dept);

        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        long startMs = ym.atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
        long endMs   = ym.plusMonths(1).atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();

        List<User> sellers = buildSalesBonusEligible(startMs, endMs);

        // 1) Doanh thu phòng — 2 số khác nhau:
        //    • commissionRev (= collected + waived): dùng tính TIỀN THƯỞNG per bước
        //    • orderRev     (= Σ final - vat đơn tạo trong tháng): dùng tính KPI % đạt target
        //    Lý do: target cá nhân so với tổng DT tháng (order-based), không phải DT chia HH.
        long deptRevenue = 0L;                    // commissionRev (dùng tính bước thưởng)
        long deptOrderRevenue = 0L;               // orderRev (dùng tính % đạt target DEPT)
        Map<Long, Long> sellerRevenues = new LinkedHashMap<>();
        Map<Long, Long> sellerOrderRevenues = new LinkedHashMap<>();
        for (User u : sellers) {
            java.math.BigDecimal collected =
                    nzBd(paymentTransactionRepo.sumRevenueByUser(startMs, endMs, u.getId()));
            java.math.BigDecimal waived =
                    nzBd(orderRepository.sumWaivedAmountCreatedInRangeByUser(startMs, endMs, u.getId()));
            long rev = collected.add(waived).setScale(0, RoundingMode.HALF_UP).longValueExact();
            sellerRevenues.put(u.getId(), rev);
            deptRevenue += rev;

            java.math.BigDecimal orderRev =
                    nzBd(orderRepository.sumFinalAmountCreatedInRangeByUser(startMs, endMs, u.getId()));
            long oRev = orderRev.setScale(0, RoundingMode.HALF_UP).longValueExact();
            sellerOrderRevenues.put(u.getId(), oRev);
            deptOrderRevenue += oRev;
        }

        // 2) Dept-wide multiplier — ladder theo % đạt DEPT target.
        //    Dùng ORDER revenue (giống UI) để % đồng bộ với con số user thấy trên card.
        boolean deptAchieved = deptOrderRevenue >= target;
        double deptAchievedPct = target > 0 ? (double) deptOrderRevenue / target * 100.0 : 0.0;

        // 3) Chuẩn hóa tiers (sort DESC theo thresholdPercent). Nếu null/empty →
        //    dùng legacy 90/50% dept-wide.
        List<SalesBonusTierDto> sortedTiers = tiers == null ? new ArrayList<>()
                : tiers.stream()
                .filter(t -> t != null && t.getThresholdPercent() != null && t.getPricePercent() != null)
                .sorted((a,b) -> Integer.compare(b.getThresholdPercent(), a.getThresholdPercent()))
                .toList();
        String tiersJson = null;
        try { if (!sortedTiers.isEmpty()) tiersJson = MAPPER.writeValueAsString(sortedTiers); } catch (Exception ignored) {}

        // 3b) Multiplier chung cho cả phòng = tier matching với deptAchievedPct
        double deptMultiplier = resolveMultiplierFromTiers(deptAchievedPct, sortedTiers,
                deptAchieved ? 1.0 : 0.5);

        // 4) Snapshot config lên sheet
        //    (10/2026) Lưu ORDER revenue (= tổng DT tháng, order-based) để % đạt
        //    target khớp với card "Tổng doanh thu tháng" hiển thị trên UI.
        sheet.setSalesBonusTarget(target);
        sheet.setSalesBonusStepAmount(stepAmount);
        sheet.setSalesBonusStepUnitPrice(stepUnitPrice);
        sheet.setSalesBonusMultiplier(deptMultiplier);
        sheet.setSalesBonusDeptRevenue(deptOrderRevenue);
        sheet.setSalesBonusTiersJson(tiersJson);
        sheet.markBonusCalculated(actor != null ? actor.getFullName() : "SYSTEM");
        sheetRepo.save(sheet);

        // 5) Upsert sales_bonus_override cho từng seller
        List<SalesBonusRowDto> rows = new ArrayList<>(sellers.size());
        for (User u : sellers) {
            long rev = sellerRevenues.getOrDefault(u.getId(), 0L);
            int  steps     = (int) (rev / stepAmount);
            long remainder = rev - (long) steps * stepAmount;

            boolean isSalesSeller = u.getPayrollRole() == com.nhatnam.server.enumtype.Role.SELLER
                    || u.getPayrollRole() == com.nhatnam.server.enumtype.Role.SUPER_SELLER;
            Long personalTarget = u.getSalesPersonalTarget();

            // Multiplier LUÔN theo dept-wide (ladder match với deptAchievedPct).
            // Personal target: tính % đạt dùng ORDER revenue (= tổng DT tháng hiển thị UI),
            // không phải commission revenue (payment-based).
            double multiplier = deptMultiplier;
            Double achievedPct = null;
            if (personalTarget != null && personalTarget > 0) {
                long orderRev = sellerOrderRevenues.getOrDefault(u.getId(), 0L);
                achievedPct = (double) orderRev / personalTarget * 100.0;
            }
            long defaultUnitAfter = Math.round(stepUnitPrice * multiplier);

            // (10/2026) RESET về default mỗi lần preview — không giữ override cũ.
            //   User override lại trong modal, bấm "Xác nhận" (save) để lưu lại.
            SalesBonusOverride ov = salesBonusOverrideRepo
                    .findByUser_IdAndMonthAndYear(u.getId(), month, year)
                    .orElse(null);
            if (ov == null) {
                ov = SalesBonusOverride.builder()
                        .user(u).month(month).year(year)
                        .build();
            }
            // (10/2026) FIX "phần dư" không còn bị bỏ (= 0đ), mà tính theo % đạt
            // bước. Công thức theo user:
            //   remPercent = floor(remainder / stepAmount * 100)   // chỉ phần nguyên
            //   remBonus   = ceilTo5000(remPercent × defaultUnitAfter / 100)
            // VD stepAmount=100tr, defaultUnitAfter=200.000 (full 400k × multiplier 50%),
            //    remainder=78.084.417 → remPercent=78 → raw = 78×200.000/100 = 156.000
            //    → ceilTo5000(156.000) = 160.000.
            int remainderPercent = stepAmount > 0
                    ? (int) (remainder * 100L / stepAmount)
                    : 0;
            long remainderBonusRaw = (long) remainderPercent * defaultUnitAfter / 100L;
            long remainderBonusDefault = roundUpTo(remainderBonusRaw, 5_000L);

            ov.setRevenue(rev);
            ov.setSteps(steps);
            ov.setRemainder(remainder);
            ov.setPersonalTarget(personalTarget);
            ov.setAchievedPercent(achievedPct);
            ov.setAppliedMultiplier(multiplier);
            ov.setStepUnitPrice(defaultUnitAfter);     // reset về default
            ov.setRemainderBonus(remainderBonusDefault); // default theo % phần dư, đã round 5.000
            ov.setOwnerEdited(false);                   // reset flag
            ov.setTotalBonus((long) ov.getSteps() * ov.getStepUnitPrice() + ov.getRemainderBonus());
            SalesBonusOverride saved = salesBonusOverrideRepo.save(ov);

            rows.add(SalesBonusRowDto.builder()
                    .userId(u.getId())
                    .fullName(u.getFullName())
                    .roleLabel(deptResolver.roleLabelOf(u))
                    .revenue(rev)
                    .steps(steps)
                    .remainder(remainder)
                    .stepUnitPrice(saved.getStepUnitPrice())
                    .remainderBonus(saved.getRemainderBonus())
                    .totalBonus(saved.getTotalBonus())
                    .ownerEdited(Boolean.TRUE.equals(saved.getOwnerEdited()))
                    .isSalesSeller(isSalesSeller)
                    .personalTarget(personalTarget)
                    .achievedPercent(achievedPct)
                    .appliedMultiplier(multiplier)
                    .build());
        }

        return SalesBonusPreviewDto.builder()
                .month(month).year(year)
                .target(target).stepAmount(stepAmount).stepUnitPrice(stepUnitPrice)
                .multiplier(deptMultiplier)
                .deptRevenue(deptRevenue)
                .targetAchieved(deptAchieved)
                .tiers(sortedTiers)
                .rows(rows)
                .build();
    }

    /**
     * Tìm multiplier từ ladder tiers (đã sort DESC theo thresholdPercent).
     * Trả về tier.pricePercent/100 của tier đầu tiên match; nếu không khớp tier nào → fallback.
     */
    private static double resolveMultiplierFromTiers(double achievedPct,
                                                     List<SalesBonusTierDto> sortedTiers,
                                                     double fallback) {
        if (sortedTiers == null || sortedTiers.isEmpty()) return fallback;
        for (SalesBonusTierDto t : sortedTiers) {
            if (achievedPct >= t.getThresholdPercent()) {
                return t.getPricePercent() / 100.0;
            }
        }
        return 0.0;
    }

    /**
     * LOAD lại preview đang có — dùng khi OWNER mở lại tab đã tính.
     * Nếu sheet chưa có config (chưa bấm Tính) → trả null.
     */
    @Transactional(readOnly = true)
    public SalesBonusPreviewDto getCurrentSalesBonusV2(int month, int year) {
        AttendanceSheet sheet = sheetRepo
                .findByMonthAndYearAndDepartment(month, year, PayrollDepartment.SALES)
                .orElse(null);
        if (sheet == null || sheet.getSalesBonusStepUnitPrice() == null) return null;

        List<SalesBonusOverride> all = salesBonusOverrideRepo.findByMonthAndYear(month, year);
        List<SalesBonusRowDto> rows = all.stream()
                .sorted(Comparator.comparing(o -> o.getUser() != null && o.getUser().getFullName() != null
                        ? o.getUser().getFullName() : ""))
                .map(o -> {
                    User u = o.getUser();
                    boolean isSeller = u != null && (
                            u.getPayrollRole() == com.nhatnam.server.enumtype.Role.SELLER
                                    || u.getPayrollRole() == com.nhatnam.server.enumtype.Role.SUPER_SELLER);
                    return SalesBonusRowDto.builder()
                            .userId(u != null ? u.getId() : null)
                            .fullName(u != null ? u.getFullName() : null)
                            .roleLabel(u != null ? deptResolver.roleLabelOf(u) : null)
                            .revenue(o.getRevenue())
                            .steps(o.getSteps())
                            .remainder(o.getRemainder())
                            .stepUnitPrice(o.getStepUnitPrice())
                            .remainderBonus(o.getRemainderBonus())
                            .totalBonus(o.getTotalBonus())
                            .ownerEdited(Boolean.TRUE.equals(o.getOwnerEdited()))
                            .isSalesSeller(isSeller)
                            .personalTarget(o.getPersonalTarget())
                            .achievedPercent(o.getAchievedPercent())
                            .appliedMultiplier(o.getAppliedMultiplier())
                            .build();
                })
                .toList();

        boolean achieved = sheet.getSalesBonusDeptRevenue() != null && sheet.getSalesBonusTarget() != null
                && sheet.getSalesBonusDeptRevenue() >= sheet.getSalesBonusTarget();

        List<SalesBonusTierDto> tiers = new ArrayList<>();
        if (sheet.getSalesBonusTiersJson() != null && !sheet.getSalesBonusTiersJson().isBlank()) {
            try {
                tiers = MAPPER.readValue(sheet.getSalesBonusTiersJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<SalesBonusTierDto>>() {});
            } catch (Exception ignored) {}
        }

        return SalesBonusPreviewDto.builder()
                .month(month).year(year)
                .target(sheet.getSalesBonusTarget())
                .stepAmount(sheet.getSalesBonusStepAmount())
                .stepUnitPrice(sheet.getSalesBonusStepUnitPrice())
                .multiplier(sheet.getSalesBonusMultiplier())
                .deptRevenue(sheet.getSalesBonusDeptRevenue())
                .targetAchieved(achieved)
                .tiers(tiers)
                .rows(rows)
                .build();
    }

    /**
     * PUBLISH SALES BONUS V2 với override per-seller.
     *
     * <p>Flow:
     * <ol>
     *   <li>Nhận list overrides từ FE (chỉ các seller OWNER đã sửa, hoặc tất cả).</li>
     *   <li>Upsert sales_bonus_override — đánh dấu ownerEdited=true nếu khác default.</li>
     *   <li>Xoá OfficeBonusResult cũ (nếu có) → tạo mới với items theo total_bonus.</li>
     *   <li>Mark sheet PUBLISHED → nhân viên thấy phiếu.</li>
     * </ol>
     */
    /**
     * (10/2026) SAVE OVERRIDES ONLY — không publish.
     * OWNER bấm "Xác nhận" trong ReviewModal → gọi method này.
     * Sheet vẫn CALCULATED, nhân viên chưa thấy. Phải bấm "Hoàn tất" riêng để publish.
     */
    @Transactional
    public SalesBonusPreviewDto saveSalesBonusOverrides(int month, int year,
                                                        List<SalesBonusOverrideDto> overrides,
                                                        User actor) {
        applySalesOverrides(month, year, overrides, actor);
        // FIX (10/2026): Trước đây OfficeBonusResult chỉ được tạo ở bước Publish —
        // nên sau khi OWNER bấm "Xác nhận" trong ReviewModal, cột "Thưởng doanh
        // thu" ở bảng preview vẫn trống (getOfficeBonusPreview đọc bonusAmount
        // từ OfficeBonusResult). Phải chờ "Hoàn tất" mới có data — sai logic.
        // Nay sync luôn OfficeBonusResult theo state hiện tại của SalesBonusOverride
        // nhưng KHÔNG đổi state sheet (vẫn CALCULATED, nhân viên chưa thấy).
        syncOfficeBonusResultFromOverrides(month, year, actor);
        return getCurrentSalesBonusV2(month, year);
    }

    /**
     * Dựng lại OfficeBonusResult cho SALES từ SalesBonusOverride hiện tại.
     * Dùng chung cho cả bước SAVE (giữ state CALCULATED) và PUBLISH (sẽ bật
     * state PUBLISHED ở ngoài).
     */
    private void syncOfficeBonusResultFromOverrides(int month, int year, User actor) {
        PayrollDepartment dept = PayrollDepartment.SALES;
        AttendanceSheet sheet = sheetOf(month, year, dept);
        if (sheet.getSalesBonusStepUnitPrice() == null) return;
        long stepUnitPrice = sheet.getSalesBonusStepUnitPrice();

        List<SalesBonusOverride> allRows = salesBonusOverrideRepo.findByMonthAndYear(month, year);
        long deptPool = 0L;
        java.math.BigDecimal deptRev = java.math.BigDecimal.ZERO;
        List<OfficeBonusItem> items = new ArrayList<>();
        for (SalesBonusOverride row : allRows) {
            User u = row.getUser();
            if (u == null) continue;
            long total = row.getTotalBonus() != null ? row.getTotalBonus() : 0L;
            deptPool += total;
            java.math.BigDecimal rev = java.math.BigDecimal.valueOf(
                    row.getRevenue() != null ? row.getRevenue() : 0L);
            deptRev = deptRev.add(rev);
            items.add(OfficeBonusItem.builder()
                    .user(u)
                    .userFullName(u.getFullName())
                    .roleLabel(deptResolver.roleLabelOf(u))
                    .revenue(rev)
                    .bonusAmount(total)
                    .rawBonusAmount(total)
                    .kpiPercent(100.0)
                    .transactionCount(0)
                    .build());
        }

        officeBonusResultRepo.deleteByMonthAndYearAndDepartment(month, year, dept);
        officeBonusResultRepo.flush();

        OfficeBonusResult result = OfficeBonusResult.builder()
                .month(month).year(year).department(dept)
                .commissionUnitPrice(stepUnitPrice)
                .totalRevenue(deptRev)
                .totalBonusPool(deptPool)
                .transactionCount(0)
                .computedAt(System.currentTimeMillis())
                .computedByName(actor != null ? actor.getFullName() : "SYSTEM")
                .build();
        result = officeBonusResultRepo.save(result);
        final OfficeBonusResult saved = result;
        items.forEach(it -> it.setBonusResult(saved));
        saved.getItems().addAll(items);
        officeBonusResultRepo.save(saved);
    }

    /**
     * PUBLISH — promote current state (từ sales_bonus_override) → office_bonus_result,
     * mark sheet PUBLISHED → nhân viên thấy. KHÔNG nhận overrides nữa (OWNER đã lưu
     * bằng saveSalesBonusOverrides trước đó).
     */
    @Transactional
    public SalesBonusPreviewDto publishSalesBonusV2(int month, int year, User actor) {
        return publishSalesBonusV2Internal(month, year, actor);
    }

    private void applySalesOverrides(int month, int year, List<SalesBonusOverrideDto> overrides, User actor) {
        PayrollDepartment dept = PayrollDepartment.SALES;
        AttendanceSheet sheet = sheetOf(month, year, dept);
        if (sheet.getSalesBonusStepUnitPrice() == null)
            throw new IllegalStateException("Chưa bấm 'Tính thưởng' — không có config để lưu.");

        long stepUnitPrice = sheet.getSalesBonusStepUnitPrice();
        double multiplier  = sheet.getSalesBonusMultiplier() != null ? sheet.getSalesBonusMultiplier() : 1.0;
        long defaultUnitAfter = Math.round(stepUnitPrice * multiplier);
        // (10/2026) Cần stepAmount để tính default remainderBonus theo công thức
        // "% phần dư" — dùng để so sánh ownerEdited đúng, không dính default.
        long stepAmount = sheet.getSalesBonusStepAmount() != null
                ? sheet.getSalesBonusStepAmount() : 0L;

        Map<Long, SalesBonusOverrideDto> ovByUser = new HashMap<>();
        if (overrides != null) {
            for (SalesBonusOverrideDto o : overrides) {
                if (o != null && o.getUserId() != null) ovByUser.put(o.getUserId(), o);
            }
        }
        List<SalesBonusOverride> allRows = salesBonusOverrideRepo.findByMonthAndYear(month, year);
        for (SalesBonusOverride row : allRows) {
            User u = row.getUser();
            if (u == null) continue;
            // Default remainderBonus theo công thức mới (10/2026): để check
            // ownerEdited đúng — khi OWNER không đổi, giá trị gửi lên == default.
            long rowRemainder = row.getRemainder() != null ? row.getRemainder() : 0L;
            int defaultRemPercent = stepAmount > 0
                    ? (int) (rowRemainder * 100L / stepAmount) : 0;
            long defaultRemBonus = roundUpTo(
                    (long) defaultRemPercent * defaultUnitAfter / 100L, 5_000L);

            SalesBonusOverrideDto ov = ovByUser.get(u.getId());
            if (ov != null) {
                boolean edited = false;
                if (ov.getStepUnitPrice() != null && ov.getStepUnitPrice() >= 0) {
                    row.setStepUnitPrice(ov.getStepUnitPrice());
                    if (ov.getStepUnitPrice() != defaultUnitAfter) edited = true;
                }
                if (ov.getRemainderBonus() != null && ov.getRemainderBonus() >= 0) {
                    row.setRemainderBonus(ov.getRemainderBonus());
                    if (ov.getRemainderBonus() != defaultRemBonus) edited = true;
                }
                // (10/2026) MỌI NV đều có thể set personalTarget nếu có doanh thu
                if (ov.getPersonalTarget() != null && ov.getPersonalTarget() >= 0) {
                    u.setSalesPersonalTarget(ov.getPersonalTarget());
                    userRepo.save(u);
                    row.setPersonalTarget(ov.getPersonalTarget());
                    if (ov.getPersonalTarget() > 0) {
                        // Dùng ORDER revenue (tổng DT tháng) để tính % đạt KPI
                        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
                        long startMs = ym.atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
                        long endMs   = ym.plusMonths(1).atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
                        java.math.BigDecimal oRev = nzBd(
                                orderRepository.sumFinalAmountCreatedInRangeByUser(startMs, endMs, u.getId()));
                        row.setAchievedPercent(oRev.doubleValue() / ov.getPersonalTarget() * 100.0);
                    }
                }
                row.setOwnerEdited(edited || Boolean.TRUE.equals(row.getOwnerEdited()));
            }
            long total = (long) row.getSteps() * row.getStepUnitPrice() + row.getRemainderBonus();
            row.setTotalBonus(total);
            salesBonusOverrideRepo.save(row);
        }
    }

    private SalesBonusPreviewDto publishSalesBonusV2Internal(int month, int year, User actor) {
        PayrollDepartment dept = PayrollDepartment.SALES;
        AttendanceSheet sheet = sheetOf(month, year, dept);
        if (sheet.getSalesBonusStepUnitPrice() == null)
            throw new IllegalStateException("Chưa bấm 'Tính thưởng' — không có config để Public.");

        // (10/2026) Dùng chung helper — logic dựng OfficeBonusResult đã tách ra
        // để SAVE cũng gọi được. Ở đây chỉ cần sync lại cho chắc rồi mark Published.
        syncOfficeBonusResultFromOverrides(month, year, actor);

        // Mark sheet PUBLISHED (markBonusPublished tự set bonusFinalized=true)
        sheet.markBonusPublished(actor != null ? actor.getFullName() : "SYSTEM");
        sheetRepo.save(sheet);

        log.info("[Payroll] Public SALES bonus v2 {}/{} target={}",
                month, year, sheet.getSalesBonusTarget());

        return getCurrentSalesBonusV2(month, year);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PREVIEW HOA HỒNG — hoạt động cả khi CHƯA tính (11/2026)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Preview Thưởng/Hoa hồng cho SALES hoặc ACCOUNTING — trả CẢ khi chưa tính
     * (bonusAmount = null) và sau khi đã tính.
     *
     * <h3>Trả về</h3>
     * <ul>
     *   <li>3 stat phòng: totalMonthOrderRevenue, totalCollectedRevenue,
     *       totalHoldRevenue, transactionCount.</li>
     *   <li>items[]: mỗi nhân viên 1 dòng, sort theo chức vụ.</li>
     *   <li>lastCommissionUnitPrice: đơn giá tháng gần nhất đã có — FE dùng
     *       làm placeholder ô input.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public OfficeBonusPreviewDto getOfficeBonusPreview(int month, int year,
                                                       PayrollDepartment department) {
        if (department != PayrollDepartment.SALES && department != PayrollDepartment.ACCOUNTING)
            throw new IllegalArgumentException("Preview chỉ cho SALES / ACCOUNTING");

        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        long startMs = ym.atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
        long endMs   = ym.plusMonths(1).atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();

        // Kết quả đã tính (nếu có) — để lấy bonusAmount + ratio per user
        OfficeBonusResult existing = officeBonusResultRepo
                .findByMonthAndYearAndDepartment(month, year, department).orElse(null);
        Map<Long, Long> bonusByUser = new HashMap<>();
        Map<Long, Long> rawBonusByUser = new HashMap<>();
        Map<Long, java.math.BigDecimal> ratioByUser = new HashMap<>();
        if (existing != null) {
            for (OfficeBonusItem it : existing.getItems()) {
                bonusByUser.put(it.getUser().getId(), it.getBonusAmount());
                if (it.getRawBonusAmount() != null)
                    rawBonusByUser.put(it.getUser().getId(), it.getRawBonusAmount());
                if (it.getRatioPercent() != null)
                    ratioByUser.put(it.getUser().getId(), it.getRatioPercent());
            }
        }

        // Danh sách nhân viên hiện trong bảng.
        //   SALES: NV phòng KD + extras ngoài phòng có tạo đơn (vd NV Kho).
        //   ACCOUNTING: NV phòng Kế toán có receiveBonus (giữ nguyên).
        List<User> employees = department == PayrollDepartment.SALES
                ? buildSalesBonusEligible(startMs, endMs)
                : deptResolver.employeesOf(department).stream()
                .filter(u -> !Boolean.FALSE.equals(u.getReceiveBonus()))
                .sorted(Comparator.comparingInt(u -> roleSortOrderIn(department, u)))
                .toList();

        // ── STAT TRÊN LUÔN LẤY CÔNG TY — ACCOUNTING & SALES giống nhau ──
        //
        // FIX (11/2026): trước đây SALES tính dept-level = Σ per-seller nên
        // thiếu đơn do user KHÔNG phải seller (vd kế toán/admin tạo đơn hộ)
        // → chênh với ACCOUNTING. User muốn cả 2 tab hiển thị cùng 1 số ở
        // card trên (toàn công ty), chỉ các dòng trong bảng mới per-seller.
        java.math.BigDecimal deptMonthOrderRev =
                nzBd(orderRepository.sumFinalAmountCreatedInRange(startMs, endMs));
        java.math.BigDecimal deptCollectedRev  =
                nzBd(paymentTransactionRepo.sumRevenueAllOrders(startMs, endMs));
        java.math.BigDecimal deptHoldRev       =
                nzBd(orderRepository.sumHoldAmountCreatedInRange(startMs, endMs));
        java.math.BigDecimal deptWaivedRev     =
                nzBd(orderRepository.sumWaivedAmountCreatedInRange(startMs, endMs));
        int deptTxCount =
                (int) paymentTransactionRepo.countTransactionsAllOrders(startMs, endMs);

        // (10/2026 fix) "Doanh thu được chia hoa hồng" dùng để tính pool PHẢI
        // nhất quán với số được dùng ở finalizeBonus (collected + waived).
        //   collected = Σ pt.amount × netRatio  (từng lần thu, EX-VAT)
        //   waived    = Σ (final − paid) × netRatio của đơn COMPLETED còn chênh
        //               — khoản kế toán xác nhận "bỏ số lẻ" vẫn tính HH như đã thu.
        // Trước fix này, UI chỉ hiển thị collected → chênh với pool đúng bằng
        // số waived (vd 957đ trong log test).
        java.math.BigDecimal deptCollectedForBonus = deptCollectedRev.add(deptWaivedRev);

        List<OfficeBonusPreviewRowDto> rows = new ArrayList<>();
        for (User u : employees) {
            java.math.BigDecimal rowMonth, rowCollected, rowHold, rowWaived;
            int rowTx;
            if (department == PayrollDepartment.SALES) {
                // Mỗi seller chỉ tính đơn do chính họ tạo (o.user.id = seller.id)
                rowMonth     = nzBd(orderRepository.sumFinalAmountCreatedInRangeByUser(startMs, endMs, u.getId()));
                java.math.BigDecimal rowCollectedOnly =
                        nzBd(paymentTransactionRepo.sumRevenueByUser(startMs, endMs, u.getId()));
                rowWaived    = nzBd(orderRepository.sumWaivedAmountCreatedInRangeByUser(startMs, endMs, u.getId()));
                rowCollected = rowCollectedOnly.add(rowWaived);     // dùng chia HH
                rowHold      = nzBd(orderRepository.sumHoldAmountCreatedInRangeByUser(startMs, endMs, u.getId()));
                rowTx        = (int) paymentTransactionRepo.countTransactionsByUser(startMs, endMs, u.getId());
            } else {
                // ACCOUNTING — mỗi row thấy số của phòng
                rowMonth     = deptMonthOrderRev;
                rowCollected = deptCollectedForBonus;                // = collected + waived
                rowHold      = deptHoldRev;
                rowWaived    = deptWaivedRev;
                rowTx        = deptTxCount;
            }

            rows.add(OfficeBonusPreviewRowDto.builder()
                    .userId(u.getId())
                    .userFullName(u.getFullName())
                    .roleLabel(deptResolver.roleLabelOf(u))
                    .roleSortOrder(roleSortOrderIn(department, u))
                    .weight(department == PayrollDepartment.ACCOUNTING
                            ? OfficeBonusCommissionUtil.accountingWeightOf(deptResolver.payrollRoleOf(u))
                            : null)
                    .totalMonthOrderRevenue(rowMonth)
                    .totalCollectedRevenue(rowCollected)
                    .totalHoldRevenue(rowHold)
                    .totalWaivedRevenue(rowWaived)
                    .transactionCount(rowTx)
                    .bonusAmount(bonusByUser.get(u.getId()))
                    .rawBonusAmount(rawBonusByUser.get(u.getId()))
                    .ratioPercent(ratioByUser.get(u.getId()))
                    .build());
        }

        return OfficeBonusPreviewDto.builder()
                .month(month).year(year)
                .department(department.name())
                .departmentLabel(department.getLabel())
                .totalMonthOrderRevenue(deptMonthOrderRev)
                .totalCollectedRevenue(deptCollectedForBonus)         // = collected + waived
                .totalHoldRevenue(deptHoldRev)
                .totalWaivedRevenue(deptWaivedRev)
                .transactionCount(deptTxCount)
                .commissionCalculated(existing != null)
                .commissionUnitPrice(existing != null ? existing.getCommissionUnitPrice() : null)
                .lastCommissionUnitPrice(findLastCommissionUnitPrice(month, year, department))
                .totalBonusPool(existing != null ? existing.getTotalBonusPool() : null)
                .undistributedAmount(existing != null ? existing.getUndistributedAmount() : null)
                .salesTiers(existing != null ? parseSalesTiers(existing.getSalesTiersJson()) : null)
                .accountingPercent(existing != null ? existing.getAccountingPercent() : null)
                .accountingRatios(existing != null ? parseAcctRatios(existing.getAccountingRatiosJson()) : null)
                .computedAt(existing != null ? existing.getComputedAt() : null)
                .computedByName(existing != null ? existing.getComputedByName() : null)
                .items(rows)
                .build();
    }

    /**
     * Đơn giá hoa hồng của THÁNG GẦN NHẤT có record cho bộ phận này (quét ngược
     * tối đa 24 tháng), trước tháng {@code (year, month)}. {@code null} nếu
     * chưa từng tính.
     */
    @Transactional(readOnly = true)
    public Long findLastCommissionUnitPrice(int month, int year, PayrollDepartment department) {
        java.time.YearMonth cursor = java.time.YearMonth.of(year, month);
        for (int i = 0; i < 24; i++) {
            cursor = cursor.minusMonths(1);
            Optional<OfficeBonusResult> opt = officeBonusResultRepo
                    .findByMonthAndYearAndDepartment(cursor.getMonthValue(), cursor.getYear(), department);
            if (opt.isPresent() && opt.get().getCommissionUnitPrice() != null)
                return opt.get().getCommissionUnitPrice();
        }
        return null;
    }

    private static java.math.BigDecimal nzBd(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }

    /**
     * (10/2026) PUBLIC thưởng — nhân viên bắt đầu thấy phiếu thưởng.
     * Chỉ cho phép khi đã ở state CALCULATED (đã Tính thưởng ở bước trước).
     * Chuyển state CALCULATED → PUBLISHED và bật bonusFinalized = true.
     */
    @Transactional
    public AttendanceSheetDto publishBonus(int month, int year, PayrollDepartment department, User actor) {
        if (department != PayrollDepartment.SALES
                && department != PayrollDepartment.ACCOUNTING
                && department != PayrollDepartment.DRIVER)
            throw new IllegalArgumentException("Chỉ SALES / ACCOUNTING / DRIVER mới có bước Public Thưởng.");
        AttendanceSheet sheet = sheetOf(month, year, department);
        if (sheet.getBonusCalcStatus() != com.nhatnam.server.enumtype.PayrollCalcStatus.CALCULATED)
            throw new IllegalArgumentException(
                    "Phải Tính thưởng trước. Trạng thái hiện tại: "
                            + (sheet.getBonusCalcStatus() == null ? "NONE" : sheet.getBonusCalcStatus().name()));

        // (10/2026) Ràng buộc nghiệp vụ: trước khi Public Thưởng, LƯƠNG cả công
        // ty phải đã Public. Nếu lương vẫn chưa chốt mà Public thưởng, NV sẽ
        // thấy phiếu "Đang xử lý lương" + có thưởng → không nhất quán.
        AttendanceSheet company = sheetRepo.findCompanySheet(month, year).orElse(null);
        boolean companyPub = company != null
                && company.getCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED;
        if (!companyPub) {
            throw new IllegalArgumentException(
                    "Phải Public LƯƠNG toàn công ty tháng " + month + "/" + year
                            + " trước khi Public Thưởng (tránh phiếu thưởng xuất hiện khi lương chưa chốt).");
        }

        sheet.markBonusPublished(actor != null ? actor.getFullName() : "system");
        sheetRepo.save(sheet);
        log.info("[Payroll] PUBLIC THƯỞNG {} tháng {}/{} bởi {}", department, month, year,
                actor != null ? actor.getFullName() : "system");
        return toSheetDto(sheet);
    }

    /**
     * (10/2026) UNPUBLIC thưởng — ẩn phiếu thưởng khỏi nhân viên, giữ nguyên
     * kết quả đã tính. State PUBLISHED → CALCULATED. OfficeBonusResult KHÔNG xoá.
     * Dùng khi phát hiện sai, muốn sửa cấu hình rồi tính lại trước khi Public lại.
     */
    @Transactional
    public AttendanceSheetDto unpublishBonus(int month, int year, PayrollDepartment department, User actor) {
        if (department != PayrollDepartment.SALES
                && department != PayrollDepartment.ACCOUNTING
                && department != PayrollDepartment.DRIVER)
            throw new IllegalArgumentException("Chỉ SALES / ACCOUNTING / DRIVER mới có bước Unpublic Thưởng.");
        AttendanceSheet sheet = sheetOf(month, year, department);
        if (sheet.getBonusCalcStatus() != com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED)
            throw new IllegalArgumentException("Thưởng chưa Public thì không Unpublic được.");
        sheet.markBonusUnpublished();
        sheetRepo.save(sheet);
        log.info("[Payroll] UNPUBLIC THƯỞNG {} tháng {}/{} bởi {}", department, month, year,
                actor != null ? actor.getFullName() : "system");
        return toSheetDto(sheet);
    }

    /**
     * Mở lại Thưởng doanh thu — reset về state NONE (chưa tính), đồng thời XOÁ
     * kết quả OfficeBonusResult đã lưu. OWNER sẽ phải nhập lại config và bấm
     * "Tính thưởng" từ đầu.
     *
     * <p>Yêu cầu state hiện tại là CALCULATED (chưa Public). Nếu đang PUBLISHED
     * thì phải Unpublic trước — tránh trường hợp nhân viên đang xem phiếu rồi
     * bị xoá đột ngột không rõ lý do.
     */
    @Transactional
    public AttendanceSheetDto reopenBonus(int month, int year, PayrollDepartment department) {
        if (department != PayrollDepartment.SALES && department != PayrollDepartment.ACCOUNTING)
            throw new IllegalArgumentException("Chỉ SALES / ACCOUNTING mới có bước Hoàn tất Thưởng.");
        AttendanceSheet sheet = sheetOf(month, year, department);
        if (sheet.getBonusCalcStatus() == com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED)
            throw new IllegalArgumentException(
                    "Thưởng đang Public — hãy Unpublic trước khi Mở lại.");
        // (10/2026) Mở lại = cho phép TÍNH LẠI từ đầu → cần xoá kết quả đã lưu
        // để OWNER thấy UI "Chưa tính" và nhập lại config. Nếu không xoá, UI
        // vẫn hiển thị bảng cũ gây hiểu nhầm là đã có thưởng.
        officeBonusResultRepo.deleteByMonthAndYearAndDepartment(month, year, department);
        officeBonusResultRepo.flush();
        sheet.markBonusReopened();
        sheetRepo.save(sheet);
        log.info("[Payroll] MỞ LẠI THƯỞNG {} tháng {}/{} (đã xoá OfficeBonusResult)",
                department, month, year);
        return toSheetDto(sheet);
    }

    /** Đọc kết quả thưởng doanh thu đã tính (hoặc null nếu chưa tính). */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public OfficeBonusSummaryDto getOfficeBonusSummary(int month, int year, PayrollDepartment department) {
        return officeBonusResultRepo.findByMonthAndYearAndDepartment(month, year, department)
                .map(this::toOfficeBonusSummaryDto)
                .orElse(null);
    }

    /**
     * Tính thưởng từ doanh thu.
     *
     * <pre>
     * R ≥ 100tr  → 400.000đ mỗi 100tr (tuyến tính), phần lẻ nội suy 70–100tr
     * 70tr ≤ R &lt; 100tr → nội suy tuyến tính trong dải [70tr, 100tr] → [0, 400.000]
     * R &lt; 70tr   → 0đ
     * </pre>
     */
    static long calcBonusFromRevenue(java.math.BigDecimal revenue) {
        if (revenue == null || revenue.compareTo(java.math.BigDecimal.ZERO) <= 0) return 0L;

        final long UNIT  = 400_000L;          // thưởng mỗi 100tr
        final long TIER1 = 100_000_000L;       // 100tr
        final long LOW   =  70_000_000L;       //  70tr

        long rev = revenue.setScale(0, java.math.RoundingMode.HALF_UP).longValue();

        if (rev < LOW) return 0L;

        if (rev < TIER1) {
            // Nội suy tuyến tính [70tr → 0đ, 100tr → 400.000đ]
            double ratio = (double)(rev - LOW) / (TIER1 - LOW);
            return Math.round(ratio * UNIT);
        }

        // Tính số nguyên lần 100tr + phần lẻ nội suy
        long full = rev / TIER1;          // số lần đủ 100tr
        long rem  = rev % TIER1;          // phần lẻ

        long bonusFull = full * UNIT;
        long bonusRem  = 0L;
        if (rem >= LOW) {
            double ratio = (double)(rem - LOW) / (TIER1 - LOW);
            bonusRem = Math.round(ratio * UNIT);
        }
        return bonusFull + bonusRem;
    }

    private OfficeBonusSummaryDto toOfficeBonusSummaryDto(OfficeBonusResult r) {
        List<OfficeBonusItemDto> items = r.getItems().stream()
                .map(i -> OfficeBonusItemDto.builder()
                        .userId(i.getUser() != null ? i.getUser().getId() : null)
                        .userFullName(i.getUserFullName())
                        .roleLabel(i.getRoleLabel())
                        .revenue(i.getRevenue())
                        .bonusAmount(i.getBonusAmount())
                        .rawBonusAmount(i.getRawBonusAmount())
                        .ratioPercent(i.getRatioPercent())
                        .kpiPercent(i.getKpiPercent())
                        .transactionCount(i.getTransactionCount())
                        .build())
                .toList();

        return OfficeBonusSummaryDto.builder()
                .month(r.getMonth()).year(r.getYear())
                .department(r.getDepartment().name())
                .departmentLabel(r.getDepartment().getLabel())
                .totalRevenue(r.getTotalRevenue())
                .totalBonusPool(r.getTotalBonusPool())
                .transactionCount(r.getTransactionCount())
                .undistributedAmount(r.getUndistributedAmount())
                .salesTiers(parseSalesTiers(r.getSalesTiersJson()))
                .accountingPercent(r.getAccountingPercent())
                .accountingRatios(parseAcctRatios(r.getAccountingRatiosJson()))
                .computedAt(r.getComputedAt())
                .computedByName(r.getComputedByName())
                .items(items)
                .build();
    }

    /** Parse sales tiers JSON lưu trong DB → list DTO. Null hoặc parse lỗi → null. */
    private List<SalesTierDto> parseSalesTiers(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<SalesTierDto>>() {});
        } catch (Exception e) {
            log.warn("[Bonus] Không parse được sales_tiers_json: {}", e.getMessage());
            return null;
        }
    }

    /** Parse accounting ratios JSON → list DTO. */
    private List<UserRatioDto> parseAcctRatios(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<UserRatioDto>>() {});
        } catch (Exception e) {
            log.warn("[Bonus] Không parse được accounting_ratios_json: {}", e.getMessage());
            return null;
        }
    }


    /**
     * Tính KPI cho phòng KẾ TOÁN trong kỳ tháng/năm.
     *
     * <p><b>TODO:</b> Triển khai logic KPI thực tế theo quy chế kế toán.
     * Hiện trả về 100% cho mọi nhân viên kế toán.
     *
     * <p>Lưu ý: nhân viên kế toán có thể được đánh dấu không nhận KPI/bonus
     * (field {@code receiveKpi}/{@code receiveBonus} trên User) — những người
     * đó sẽ không được chia KPI/bonus khi tính toán.
     *
     * @param month  tháng tính lương
     * @param year   năm tính lương
     * @return map userId → kpiPercent (0–100)
     */
    public Map<Long, Double> calcAccountingKpi(int month, int year) {
        List<User> employees = deptResolver.employeesOf(PayrollDepartment.ACCOUNTING);
        Map<Long, Double> result = new LinkedHashMap<>();
        for (User u : employees) {
            // TODO: Tính KPI thực tế theo quy chế kế toán
            // Bỏ qua nhân viên được đánh dấu không nhận KPI
            Boolean rcvKpi = u.getReceiveKpi();
            if (Boolean.FALSE.equals(rcvKpi)) continue;
            result.put(u.getId(), 100.0);
        }
        return result;
    }

    /**
     * Tính bonus cho phòng KẾ TOÁN trong kỳ tháng/năm.
     *
     * <p><b>TODO:</b> Triển khai logic bonus thực tế theo quy chế kế toán.
     * Hiện trả về 0 cho mọi nhân viên kế toán.
     *
     * <p>Lưu ý: nhân viên tổng hợp hoặc có {@code receiveBonus = false}
     * sẽ không được chia bonus.
     *
     * @param month      tháng tính lương
     * @param year       năm tính lương
     * @param bonusPool  tổng quỹ bonus cần phân chia
     * @return map userId → bonusAmount
     */
    public Map<Long, Long> calcAccountingBonus(int month, int year, long bonusPool) {
        // ── TRIỂN KHAI SAU ──────────────────────────────────────────────────
        // Hiện tại thưởng kế toán được tính qua finalizeBonus() dựa trên doanh thu
        // thực thu trong tháng. Hàm này giữ lại để tương thích các caller cũ.
        // Nếu muốn dùng bonusPool thủ công thay vì doanh thu, triển khai tại đây.
        // ────────────────────────────────────────────────────────────────────
        List<User> employees = deptResolver.employeesOf(PayrollDepartment.ACCOUNTING);
        Map<Long, Long> result = new LinkedHashMap<>();
        List<User> eligible = employees.stream()
                .filter(u -> !Boolean.FALSE.equals(u.getReceiveBonus()))
                .toList();
        if (eligible.isEmpty() || bonusPool <= 0) return result;
        long perPerson = bonusPool / eligible.size();
        perPerson = (perPerson / 100_000) * 100_000;    // làm tròn trăm nghìn
        for (User u : eligible) result.put(u.getId(), perPerson);
        return result;
    }

    /**
     * Tính KPI cho phòng KINH DOANH trong kỳ tháng/năm.
     *
     * <p><b>TODO:</b> Triển khai logic KPI thực tế cho sale (doanh số,
     * thu tiền, chăm sóc khách hàng…).
     * Hiện trả về 100% cho mọi nhân viên kinh doanh.
     *
     * @param month  tháng tính lương
     * @param year   năm tính lương
     * @return map userId → kpiPercent (0–100)
     */
    public Map<Long, Double> calcSalesKpi(int month, int year) {
        List<User> employees = deptResolver.employeesOf(PayrollDepartment.SALES);
        Map<Long, Double> result = new LinkedHashMap<>();
        for (User u : employees) {
            // TODO: Tính KPI thực tế theo quy chế kinh doanh
            result.put(u.getId(), 100.0);
        }
        return result;
    }

    /**
     * Tính bonus cho phòng KINH DOANH trong kỳ tháng/năm.
     *
     * <p><b>TODO:</b> Triển khai logic bonus thực tế cho sale.
     * Hiện trả về 0 cho mọi nhân viên kinh doanh.
     *
     * @param month      tháng tính lương
     * @param year       năm tính lương
     * @param bonusPool  tổng quỹ bonus cần phân chia
     * @return map userId → bonusAmount
     */
    public Map<Long, Long> calcSalesBonus(int month, int year, long bonusPool) {
        // ── TRIỂN KHAI SAU ──────────────────────────────────────────────────
        // Hiện tại thưởng sale được tính qua finalizeBonus() dựa trên doanh thu
        // cá nhân (PaymentTransaction của đơn do seller đó tạo).
        // Hàm này giữ lại để tương thích các caller cũ.
        // Nếu muốn dùng bonusPool tổng chia đều thay vì cá nhân, triển khai tại đây.
        // ────────────────────────────────────────────────────────────────────
        List<User> employees = deptResolver.employeesOf(PayrollDepartment.SALES);
        Map<Long, Long> result = new LinkedHashMap<>();
        if (employees.isEmpty() || bonusPool <= 0) return result;
        long perPerson = bonusPool / employees.size();
        perPerson = (perPerson / 100_000) * 100_000;    // làm tròn trăm nghìn
        for (User u : employees) result.put(u.getId(), perPerson);
        return result;
    }

    public void validatePastPeriod(int month, int year) {
        YearMonth target = YearMonth.of(year, month);
        YearMonth current = YearMonth.now(VN);

        // Tháng đã qua → cho phép
        if (target.isBefore(current)) {
            return;
        }

        // Tháng hiện tại → kiểm tra đã chốt chưa
        if (target.equals(current)) {
            // Kiểm tra xem có bộ phận nào đã chốt cho tháng này chưa
            boolean anyFinalized = sheetRepo.existsByMonthAndYearAndFinalizedTrue(month, year);
            if (anyFinalized) {
                return; // Có ít nhất 1 bộ phận đã chốt → cho phép xem
            }
        }

        throw new IllegalArgumentException(
                "Chỉ xem/tải được các tháng đã kết thúc. Tháng %d/%d chưa hết hoặc chưa được chốt."
                        .formatted(month, year));
    }

}