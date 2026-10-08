package com.nhatnam.server.service.hr;

import com.nhatnam.server.dto.hr.HrDtos.AllowanceItemDto;
import com.nhatnam.server.dto.hr.HrDtos.SalaryBreakdownDto;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.AttendanceEntryRepository;
import com.nhatnam.server.repository.DriverAttendanceRepository;
import com.nhatnam.server.repository.ExpenseVoucherRepository;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.FactoryKpiBonusItemRepository;
import com.nhatnam.server.repository.OfficeBonusResultRepository;
import com.nhatnam.server.service.FactoryKpiService;
import com.nhatnam.server.utils.PayrollTaxCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Export file lương tổng hợp theo yêu cầu OWNER/ADMIN.
 *
 * <p>Thứ tự phòng ban: Quản lý cấp cao → Kế toán → Xưởng sản xuất → Kinh doanh → Kho và giao nhận.
 * <p>"Kho và giao nhận" gom cả WAREHOUSE + DRIVER.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SalaryExportService {

    private final PayrollDepartmentResolver deptResolver;
    private final HrService hrService;
    private final AttendanceEntryRepository entryRepo;
    private final DriverAttendanceRepository driverAttendanceRepo;
    private final DriverRepository driverRepo;
    private final com.nhatnam.server.service.FactoryPayrollService factoryPayrollService;
    private final ExpenseVoucherRepository expenseVoucherRepo;
    private final FactoryKpiService kpiService;
    private final FactoryKpiBonusItemRepository kpiBonusItemRepo;
    private final OfficeBonusResultRepository officeBonusResultRepo;

    private static final long LUNCH_ALLOWANCE_PER_DAY = 30_000L;

    /** Username mặc định không tính lương. */
    private static final String EXCLUDED_USERNAME = "nguyenhai";

    /**
     * Thứ tự phòng ban khi xuất file.
     *
     * <p>[2026] Tách "Kho" và "Giao nhận" thành 2 khối RIÊNG — trước đây gom
     * thành "Kho và giao nhận" (mã ảo WAREHOUSE_AND_DRIVER). Giờ mỗi bộ phận
     * có block header riêng để dễ đối chiếu bảng lương.
     */
    private static final List<String> DEPARTMENT_ORDER = List.of(
            "MANAGEMENT", "ACCOUNTING", "FACTORY", "SALES", "WAREHOUSE", "DRIVER"
    );

    /** Nhãn hiển thị phòng ban trên file. */
    private static final Map<String, String> DEPARTMENT_LABELS = Map.of(
            "MANAGEMENT", "Quản lý cấp cao",
            "ACCOUNTING", "Kế toán",
            "FACTORY",    "Xưởng sản xuất",
            "SALES",      "Kinh doanh",
            "WAREHOUSE",  "Kho",
            "DRIVER",     "Giao nhận"
    );

    /**
     * Các nhãn phụ cấp LUÔN hiển thị (kể cả khi = 0đ cho mọi nhân viên).
     *
     * <p>[Task #3, 2026] Bỏ "Phụ cấp xăng xe" khỏi danh sách này — cột chỉ
     * hiện khi có ít nhất 1 nhân viên có "Phụ cấp xăng xe" trong hồ sơ lương
     * được duyệt. Trước đây "xăng xe" luôn có cột dù nhân viên không được
     * cấp, gây hiểu nhầm là hệ thống tự tính.
     *
     * <p>Lưu ý phân biệt: tiền xăng đi giao hàng của tài xế / kho hỗ trợ giao
     * hàng KHÔNG ghi vào cột "Phụ cấp xăng xe" — đã tách sang file thưởng KPI
     * xưởng / bảng thưởng riêng.
     */
    private static final String[] BASE_ALLOWANCE_LABELS = {
            "Phụ cấp cơm trưa", "Phụ cấp điện thoại", "Phụ cấp OT"
    };

    /**
     * Nhãn phụ cấp BỎ QUA khi xuất file lương (SALARY_ONLY).
     * "Phụ cấp giao hàng" tính riêng trong file thưởng tài xế.
     * "Phụ cấp xăng xe giao hàng" = km × giá xăng, cũng tính riêng.
     */
    private static final Set<String> SALARY_EXPORT_SKIP_ALLOWANCES = Set.of(
            "Phụ cấp giao hàng", "Phụ cấp xăng xe giao hàng",
            "Phụ cấp thâm niên"
    );

    /**
     * Nhãn phụ cấp mà TÀI XẾ / KHO phải ghi 0 trên file lương
     * (vì phần xăng xe của họ tính riêng theo km × giá xăng trong file thưởng).
     */
    private static final Set<String> DRIVER_ZERO_ALLOWANCES = Set.of(
            "Phụ cấp xăng xe"
    );

    /**
     * Kiểm tra 1 nhãn phụ cấp có phải AUTO-GENERATED từ hoạt động giao hàng /
     * hỗ trợ giao hàng hay không.
     *
     * <p>[Task #3, 2026] Các nhãn này do {@code FactoryPayrollService
     * .computeNonDriverFuelAllowance()} tạo ra dưới dạng {@code MonthlyAdjustment}
     * cho nhân viên xưởng có Driver entity (hỗ trợ giao hàng). Chúng KHÔNG được
     * đưa vào file lương — thay vào đó tính chung với thưởng đơn hàng trong file
     * thưởng KPI xưởng. Nhân viên nào có "Phụ cấp xăng xe" khi TẠO HỒ SƠ LƯƠNG
     * (nhập tay, label chỉ là "Phụ cấp xăng xe" thuần, không có km × đ trong
     * ngoặc) thì vẫn hiển thị bình thường.
     *
     * <p>Pattern nhận diện:
     * <ul>
     *   <li>{@code "Phụ cấp xăng xe (X km × Yđ)"} — auto (có " km × ")</li>
     *   <li>{@code "Phụ cấp xăng xe (giao hàng)"} — auto legacy</li>
     *   <li>{@code "Phụ cấp giao hàng (X lượt ...)"} — auto</li>
     *   <li>{@code "Phụ cấp giao hàng"} — vẫn skip vì rất khó phân biệt với auto</li>
     *   <li>{@code "Phụ cấp xăng xe"} (không ngoặc) — hồ sơ lương, GIỮ LẠI</li>
     * </ul>
     */
    private static boolean isAutoGeneratedDeliveryAllowance(String rawLabel) {
        if (rawLabel == null) return false;
        String s = rawLabel.trim();

        // "Phụ cấp giao hàng" — dù có ngoặc hay không, luôn skip vì đây là khoản
        // hỗ trợ giao hàng tự tính, không có trong hồ sơ lương.
        if (s.equals("Phụ cấp giao hàng") || s.startsWith("Phụ cấp giao hàng (")) return true;

        // "Phụ cấp xăng xe (... km × ...đ)" hoặc "Phụ cấp xăng xe (giao hàng)"
        //   → auto-generated. Còn "Phụ cấp xăng xe" trần thì KHÔNG skip (hồ sơ lương).
        if (s.startsWith("Phụ cấp xăng xe (")) {
            // Bên trong ngoặc có " km × " → chắc chắn auto-generated.
            if (s.contains(" km × ") || s.contains(" km x ")) return true;
            // Legacy "(giao hàng)" — cũng auto.
            if (s.contains("(giao hàng)")) return true;
            // "Phụ cấp xăng xe (X đ)" — vẫn giữ nếu là hồ sơ lương (rất hiếm). Không skip.
        }
        return false;
    }

    /**
     * Có cho bảo vệ Q9 (attendanceExempt / FACTORY_SECURITY) vào file lương không.
     * Đổi thành false nếu muốn bỏ bảo vệ quận 9 ra khỏi file lương.
     */
    private static final boolean INCLUDE_SECURITY_IN_SALARY = false;

    /**
     * Thứ tự sắp xếp nhân viên trong mỗi phòng ban theo cấp bậc.
     *
     * <p>[2026] Nhánh FACTORY sắp xếp theo yêu cầu OWNER:
     * Trưởng xưởng → Trợ lý → Quản lý → Kế toán → Đóng gói
     * → Công nhân SX → Nhân viên xưởng → Bảo vệ xưởng.
     */
    private static int roleRank(String department, Role role) {
        if (role == null) return 99;
        return switch (department) {
            case "MANAGEMENT" -> switch (role) {
                case OWNER -> 0;
                case ADMIN -> 1;
                default -> 99;
            };
            case "ACCOUNTING" -> switch (role) {
                case SUPER_ACCOUNTANT -> 0;
                case ACCOUNTANT       -> 1;
                default -> 99;
            };
            case "FACTORY" -> switch (role) {
                case SUPER_FACTORY_WORKER      -> 0;   // Trưởng xưởng
                case FACTORY_STAFF             -> 1;   // Trợ lý xưởng
                case FACTORY_MANAGER           -> 2;   // Quản lý xưởng
                case FACTORY_ACCOUNTANT        -> 3;   // Kế toán xưởng
                case FACTORY_PACKAGING_WORKER  -> 4;   // Công nhân đóng gói
                case FACTORY_PRODUCTION_WORKER -> 5;   // Công nhân sản xuất
                case FACTORY_WORKER            -> 6;   // Nhân viên xưởng (xếp tạm)
                case FACTORY_SECURITY          -> 7;   // Bảo vệ xưởng
                default -> 99;
            };
            case "SALES" -> switch (role) {
                case SUPER_SELLER -> 0;
                case SELLER       -> 1;
                default -> 99;
            };
            case "WAREHOUSE" -> switch (role) {
                case SUPER_WAREHOUSE -> 0;
                case WAREHOUSE       -> 1;
                default -> 99;
            };
            case "DRIVER" -> switch (role) {
                case DRIVER -> 0;
                default -> 99;
            };
            default -> 99;
        };
    }

    /**
     * Số công chuẩn trong tháng: T2–T7 = 1 công, CN = 0, nghỉ lễ = 0.
     *
     * <p>[2026] Trừ thêm ngày lễ trong {@link com.nhatnam.server.utils.VietnameseHolidays}
     * để khớp với {@link com.nhatnam.server.utils.PayrollTaxCalculator#standardWorkdaysOf}
     * — trước đó cả 2 hàm cùng bỏ sót ngày lễ.
     */
    private double standardWorkdaysOf(int month, int year) {
        return PayrollTaxCalculator.standardWorkdaysOf(month, year);
    }

    /**
     * Đếm ngày công thực tế cho tài xế: ngày nào có ít nhất 1 record điểm danh
     * (START hoặc END hoặc cả 2) thì tính 1 công.
     *
     * @deprecated Phase 5 (10/2026): tài xế không còn tính lương theo odo.
     *   Lương theo chấm công đọc từ {@code AttendanceEntry} chung (giống xưởng).
     *   Method giữ lại chỉ phòng khi đến còn phần code khác tham chiếu — thực
     *   tế hiện tại không còn caller trong lớp này.
     */
    @Deprecated
    @SuppressWarnings("unused")
    private double countDriverWorkdays(User user, int month, int year) {
        Driver driver = driverRepo.findByUser_Id(user.getId()).orElse(null);
        if (driver == null) return 0;

        YearMonth ym = YearMonth.of(year, month);
        String from = ym.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String to   = ym.atEndOfMonth().format(DateTimeFormatter.ISO_LOCAL_DATE);

        List<DriverAttendance> records = driverAttendanceRepo.findByDateRange(from, to);
        // Lọc theo driver, đếm số ngày distinct
        long days = records.stream()
                .filter(r -> r.getDriver() != null && r.getDriver().getId().equals(driver.getId()))
                .map(DriverAttendance::getAttendanceDate)
                .distinct()
                .count();
        return days;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXPORT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public byte[] exportSalaryReport(int month, int year, List<String> departments, String exportType) throws Exception {

        // ── [FIX] Tab Tài xế + "Chỉ thưởng" → xuất file riêng với layout mới ──
        // Bao gồm cả tài xế thật + nhân viên hỗ trợ giao hàng (nonDriverRows)
        if ("BONUS_ONLY".equals(exportType)) {
            String dept = departments.get(0); // FE gửi đúng 1 phòng ban
            return switch (dept) {
                case "DRIVER"     -> exportDriverBonusReport(month, year);
                case "FACTORY"    -> exportFactoryBonusReport(month, year);
                case "ACCOUNTING" -> exportOfficeBonusReport(month, year, PayrollDepartment.ACCOUNTING);
                case "SALES"      -> exportOfficeBonusReport(month, year, PayrollDepartment.SALES);
                default -> throw new IllegalArgumentException("Phòng ban " + dept + " chưa hỗ trợ xuất thưởng riêng.");
            };
        }

        boolean includeSalary = !"BONUS_ONLY".equals(exportType);
        boolean includeBonus  = !"SALARY_ONLY".equals(exportType);

        // [2026] Không gom WAREHOUSE + DRIVER nữa — mỗi bộ phận là 1 khối riêng
        // trên file xuất. FE gửi mã nào giữ nguyên mã đó.
        Set<String> normalized = new LinkedHashSet<>(departments);

        // Sắp xếp theo thứ tự chuẩn
        List<String> orderedDepts = DEPARTMENT_ORDER.stream()
                .filter(normalized::contains)
                .collect(Collectors.toList());

        if (orderedDepts.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một phòng ban.");
        }

        double stdWorkdays = standardWorkdaysOf(month, year);

        // Thu thập dữ liệu
        Map<String, List<EmployeeRow>> deptData = new LinkedHashMap<>();
        for (String deptCode : orderedDepts) {
            List<EmployeeRow> rows = collectEmployees(deptCode, month, year, stdWorkdays);
            if (!rows.isEmpty()) {
                deptData.put(deptCode, rows);
            }
        }

        // ── Thu thập TẤT CẢ nhãn phụ cấp xuất hiện ở bất kỳ nhân viên nào ──
        //   Bắt đầu bằng 4 nhãn cơ bản (luôn có cột dù = 0), sau đó thêm các
        //   nhãn mới (Phụ cấp trách nhiệm, Phụ cấp giao hàng…) theo thứ tự gặp.
        //
        //   Nhãn động có chi tiết trong ngoặc (VD "Phụ cấp xăng xe (176 km × 3.000đ)")
        //   → cắt bỏ phần ngoặc để gom vào cùng 1 cột "Phụ cấp xăng xe".
        List<String> allAllowanceLabels = new ArrayList<>(Arrays.asList(BASE_ALLOWANCE_LABELS));
        Set<String> seen = new LinkedHashSet<>(allAllowanceLabels);
        for (List<EmployeeRow> rows : deptData.values()) {
            for (EmployeeRow er : rows) {
                if (er.breakdown != null && er.breakdown.getAllowances() != null) {
                    for (AllowanceItemDto a : er.breakdown.getAllowances()) {
                        // Bỏ qua phụ cấp auto-generated từ hỗ trợ giao hàng —
                        // không được xuất hiện thành cột trong file lương.
                        if (isAutoGeneratedDeliveryAllowance(a.getLabel())) continue;
                        String baseLabel = stripDetail(a.getLabel());
                        if (baseLabel != null && !seen.contains(baseLabel)
                                && !SALARY_EXPORT_SKIP_ALLOWANCES.contains(baseLabel)) {
                            seen.add(baseLabel);
                            allAllowanceLabels.add(baseLabel);
                        }
                    }
                }
            }
        }
        // Danh sách nhãn phụ cấp cuối cùng dùng cho cột Excel
        String[] dynamicAllowanceLabels = allAllowanceLabels.toArray(new String[0]);

        // Build Excel
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            ExportStyles styles = new ExportStyles(wb);
            XSSFSheet sheet = wb.createSheet("Bảng lương tháng " + month + "-" + year);

            int r = 0;
            r = writeHeader(sheet, styles, r, month, year, includeSalary, includeBonus, dynamicAllowanceLabels);
            r++; // blank

            r = writeTableHeader(sheet, styles, r, includeSalary, includeBonus, dynamicAllowanceLabels);

            boolean firstDept = true;
            for (Map.Entry<String, List<EmployeeRow>> entry : deptData.entrySet()) {
                String deptCode = entry.getKey();
                List<EmployeeRow> rows = entry.getValue();

                // 2 dòng trống ngăn cách giữa các phòng ban (trừ phòng đầu tiên)
                if (!firstDept) {
                    int totalCols2 = calcTotalColumns(includeSalary, includeBonus, dynamicAllowanceLabels);
                    sheet.addMergedRegion(new CellRangeAddress(r, r + 1, 0, totalCols2 - 1));
                    sheet.createRow(r).setHeightInPoints(10);
                    sheet.createRow(r + 1).setHeightInPoints(10);
                    r += 2;
                }
                firstDept = false;

                r = writeDepartmentHeader(sheet, styles, r, deptCode, includeSalary, includeBonus, dynamicAllowanceLabels);

                int stt = 1; // STT riêng cho từng phòng ban
                for (EmployeeRow empRow : rows) {
                    r = writeEmployeeRow(sheet, styles, r, stt++, empRow, includeSalary, includeBonus, dynamicAllowanceLabels);
                }

                // ── PHASE 6: SUBTOTAL dòng cuối mỗi phòng ban ────────────────
                if (includeSalary) {
                    r = writeDepartmentSubtotalRow(sheet, styles, r,
                            deptCode, rows, dynamicAllowanceLabels, includeBonus);
                }
            }

            // ── PHASE 4: DÒNG TỔNG CẢ CÔNG TY ──────────────────────────────
            if (includeSalary) {
                r = writeGrandTotalRow(sheet, styles, r, deptData, dynamicAllowanceLabels, includeBonus);
            }

            // Column widths
            int totalCols = calcTotalColumns(includeSalary, includeBonus, dynamicAllowanceLabels);
            sheet.setColumnWidth(0, 8 * 256);
            sheet.setColumnWidth(1, 30 * 256);  // Họ tên + dòng phụ bên dưới
            sheet.setColumnWidth(2, 22 * 256);
            for (int i = 3; i < totalCols; i++) sheet.setColumnWidth(i, 17 * 256);

            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setLandscape(true);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.getFooter().setCenter("Trang &P/&N");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }



    // ══════════════════════════════════════════════════════════════════════════
    // XUẤT FILE THƯỞNG XƯỞNG SẢN XUẤT
    //
    // Cột: STT | Họ tên | Chức vụ | Bonus (thưởng KPI) | Tạm ứng | Tổng
    // Header:
    //   Dòng 1: Đơn giá bonus/tấn (vàng) | Quỹ thưởng tháng (xanh ngọc)
    //   Dòng 2: Quỹ dư tháng trước (xanh lá) | Quỹ dư sau chia tháng này (xanh dương)
    // ══════════════════════════════════════════════════════════════════════════

    private byte[] exportFactoryBonusReport(int month, int year) throws Exception {
        FactoryKpiBonus kpi = kpiService.getOrCompute(month, year);
        List<User> employees = new ArrayList<>(deptResolver.employeesOf(PayrollDepartment.FACTORY));
        String monthStr = String.format("%d-%02d", year, month);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            ExportStyles styles = new ExportStyles(wb);
            XSSFSheet sheet = wb.createSheet("Thưởng xưởng T" + month + "-" + year);
            DataFormat fmt = wb.createDataFormat();
            short vnFmt = fmt.getFormat("#,##0");

            XSSFCellStyle mvn = wb.createCellStyle(); mvn.cloneStyleFrom(styles.moneyOdd);  mvn.setDataFormat(vnFmt);
            XSSFCellStyle mve = wb.createCellStyle(); mve.cloneStyleFrom(styles.moneyEven); mve.setDataFormat(vnFmt);
            XSSFCellStyle nvn = wb.createCellStyle(); nvn.cloneStyleFrom(styles.netOdd);    nvn.setDataFormat(vnFmt);
            XSSFCellStyle nve = wb.createCellStyle(); nve.cloneStyleFrom(styles.netEven);   nve.setDataFormat(vnFmt);

            int r = 0, COLS = 6;

            // ── Title ──
            Row r0 = sheet.createRow(r); r0.setHeightInPoints(30);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, COLS - 1));
            putCell(r0, 0, "CÔNG TY TNHH NHẤT NAM", styles.companyName); r++;

            Row r1 = sheet.createRow(r); r1.setHeightInPoints(36);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, COLS - 1));
            putCell(r1, 0, "BẢNG THƯỞNG BONUS XƯỞNG SẢN XUẤT THÁNG " + month + "/" + year, styles.title); r++;

            // ── Info dòng 1: Đơn giá bonus (vàng) | Quỹ thưởng tháng (xanh ngọc) ──
            Row info1 = sheet.createRow(r); info1.setHeightInPoints(24);
            int midCol1 = COLS / 2;

            // Khối trái: Đơn giá bonus — style vàng
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, midCol1 - 1));
            putCell(info1, 0,
                    "Đơn giá bonus: " + fmtVNd(kpi.getRatePerTon()) + "/tấn",
                    styles.bonusRateStyle);
            for (int cc = 1; cc < midCol1; cc++) putCell(info1, cc, "", styles.bonusRateStyle);

            // Khối phải: Quỹ thưởng tháng — style xanh ngọc
            //   Thêm phần "(sản lượng đã sản xuất tháng đó)" trong ngoặc để nhân
            //   viên xem file biết ngay quỹ thưởng đó dựa trên bao nhiêu kg.
            String poolText = "Quỹ thưởng tháng: " + fmtVNd(kpi.getBonusPool());
            if (kpi.getTotalOutputKg() != null
                    && kpi.getTotalOutputKg().compareTo(java.math.BigDecimal.ZERO) > 0) {
                poolText += " (" + fmtKg(kpi.getTotalOutputKg()) + "Kg)";
            }
            sheet.addMergedRegion(new CellRangeAddress(r, r, midCol1, COLS - 1));
            putCell(info1, midCol1, poolText, styles.bonusPoolStyle);
            for (int cc = midCol1 + 1; cc < COLS; cc++) putCell(info1, cc, "", styles.bonusPoolStyle);

            r++;

            // ── Info dòng 2: Quỹ dư tháng trước (xanh lá) | Quỹ dư sau chia (xanh dương) ──
            Row info2 = sheet.createRow(r); info2.setHeightInPoints(24);
            int midCol = COLS / 2;

            // Khối trái: Quỹ dư tháng trước — xanh lá
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, midCol - 1));
            putCell(info2, 0,
                    "Quỹ dư tháng trước: " + fmtVNd(kpi.getCarryOverIn()),
                    styles.carryInStyle);
            for (int cc = 1; cc < midCol; cc++) putCell(info2, cc, "", styles.carryInStyle);

            // Khối phải: Quỹ dư sau chia tháng này — xanh dương
            sheet.addMergedRegion(new CellRangeAddress(r, r, midCol, COLS - 1));
            putCell(info2, midCol,
                    "Quỹ dư sau chia tháng này: " + fmtVNd(kpi.getCarryOverOut()),
                    styles.carryOutStyle);
            for (int cc = midCol + 1; cc < COLS; cc++) putCell(info2, cc, "", styles.carryOutStyle);

            r++;
            r++; // blank

            // ── Table header ──
            Row hdr = sheet.createRow(r); hdr.setHeightInPoints(36);
            putCell(hdr, 0, "STT", styles.headerCell);
            putCell(hdr, 1, "Họ và tên", styles.headerCell);
            putCell(hdr, 2, "Chức vụ", styles.headerCell);
            putCell(hdr, 3, "Bonus", styles.headerCell);
            putCell(hdr, 4, "Tạm ứng", styles.headerCell);
            putCell(hdr, 5, "Tổng thưởng\n(Bonus - Tạm ứng)", styles.headerNetCell);
            sheet.createFreezePane(0, r + 1); r++;

            int stt = 1;
            // ── SẮP XẾP: override role theo tên TRƯỚC khi roleRank ──────────
            //   Ngô Thị Mỹ Hạnh bị set nhầm payroll_role = CNSX → override
            //   thành FACTORY_PACKAGING_WORKER để xếp sau Kế toán xưởng.
            employees.sort(Comparator.comparingInt(u ->
                    roleRank("FACTORY",
                            FactoryKpiService.overridePackagingRole(u, deptResolver.payrollRoleOf(u)))));

            // ── Style cho dòng "Số ngày đi làm" ─────────────────────────────
            //   In nghiêng, chữ xám nhạt, canh trái + thụt lề ~20px (indent = 2).
            //   Excel không dùng đơn vị px trực tiếp — indent = 2 tương đương
            //   khoảng 18-24px tuỳ font hệ thống, đã đủ để tách biệt khỏi cột STT.
            XSSFCellStyle dayRowStyle = wb.createCellStyle();
            XSSFFont dayRowFont = wb.createFont();
            dayRowFont.setItalic(true);
            dayRowFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            dayRowFont.setFontHeightInPoints((short) 10);   // ✅ đúng: font dùng setFontHeightInPoints
            dayRowStyle.setFont(dayRowFont);
            dayRowStyle.setAlignment(HorizontalAlignment.LEFT);
            dayRowStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            dayRowStyle.setIndention((short) 2);   // ~20px thụt lề

            for (User u : employees) {
                if (EXCLUDED_USERNAME.equals(u.getUsername())) continue;

                Role role = deptResolver.payrollRoleOf(u);

                // ── Task #5: bỏ Chủ tịch / Giám đốc / Kế toán trưởng ──────
                if (role != null && EXCLUDED_ROLES.contains(role)) continue;

                // ── OVERRIDE role hiển thị theo tên ─────────────────────────
                //   Đảm bảo Ngô Thị Mỹ Hạnh hiện "Công nhân đóng gói" thay vì
                //   "Công nhân sản xuất" (do payroll_role bị set nhầm).
                Role effectiveRole = FactoryKpiService.overridePackagingRole(u, role);
                String roleLabelText = effectiveRole != null
                        ? FactoryKpiService.ROLE_LABELS.getOrDefault(effectiveRole,
                        deptResolver.roleLabelOf(u))
                        : deptResolver.roleLabelOf(u);

                boolean isSecurity = (effectiveRole == Role.FACTORY_SECURITY);

                FactoryKpiBonusItem item = kpiBonusItemRepo.findByUserAndPeriod(u.getId(), month, year).orElse(null);
                long bonus = item != null && item.getAmount() != null ? item.getAmount() : 0L;

                var adv = expenseVoucherRepo.sumSalaryAdvance(u.getId(), monthStr, ExpenseVoucher.VoucherStatus.APPROVED);
                long advance = adv != null ? adv.longValue() : 0;

                // Đọc bảng chấm công của nhân viên MỘT LẦN — dùng cho cả 2 việc:
                //   (a) xác định lý do không có bonus (parttime / không đủ công)
                //   (b) hiển thị dòng "Số ngày đi làm" dưới mỗi nhân viên
                AttendanceEntry attEntry = entryRepo
                        .findByUserAndPeriod(u.getId(), month, year).orElse(null);

                boolean even = stt % 2 == 0;
                CellStyle txt = even ? styles.dataEven : styles.dataOdd;
                CellStyle num = even ? styles.numEven : styles.numOdd;
                CellStyle mny = even ? mve : mvn;
                CellStyle net = even ? nve : nvn;

                // ── Xác định lý do không có bonus ──
                //   Với thang mới 2026, parttime CŨNG được chia bonus (hệ số
                //   0.6), miễn đi làm ≥ 15 buổi. Nên message không còn phân biệt
                //   parttime nữa — chỉ phân biệt đơn vị đếm (ngày/buổi).
                String reason = null;
                if (!isSecurity && bonus == 0) {
                    // ── NGOẠI LỆ: nghỉ thai sản theo luật ────────────────────
                    //   Nhân viên này nghỉ hợp pháp, không đủ ngày công nên không
                    //   được chia KPI. Không hiển thị lý do "dưới 15 ngày công"
                    //   (gây hiểu nhầm là nghỉ không phép), thay bằng
                    //   "Nghỉ thai sản theo luật".
                    if (FactoryKpiService.isMaternityLeave(u)) {
                        reason = "Nghỉ thai sản theo luật";
                    } else if (item == null) {
                        reason = "Không đủ điều kiện nhận thưởng Bonus tháng "
                                + month + " do không có dữ liệu chấm công.";
                    } else {
                        boolean isPartTime = attEntry != null
                                && Boolean.TRUE.equals(attEntry.getPartTime());
                        String unit = isPartTime ? "buổi" : "ngày";
                        reason = "Không đủ điều kiện nhận thưởng Bonus tháng "
                                + month + " do đi làm dưới 15 " + unit + " công.";
                    }
                }
                boolean noBonus = (reason != null);

                Row row = sheet.createRow(r); row.setHeightInPoints(24);

                if (noBonus) {
                    // ── KHÔNG có bonus: cả dòng in nghiêng, merge cột 3→5 ──
                    CellStyle itTxt = even ? styles.italicDataEven   : styles.italicDataOdd;
                    CellStyle itNum = even ? styles.italicNumEven    : styles.italicNumOdd;
                    CellStyle itMg  = even ? styles.italicMergedEven : styles.italicMergedOdd;

                    putCell(row, 0, String.valueOf(stt), itNum);
                    putCell(row, 1, u.getFullName(), itTxt);
                    putCell(row, 2, roleLabelText, itTxt);

                    // Merge cột Bonus (3) → Tổng thưởng (5)
                    sheet.addMergedRegion(new CellRangeAddress(r, r, 3, 5));
                    putCell(row, 3, reason, itMg);
                    putCell(row, 4, "", itMg);
                    putCell(row, 5, "", itMg);

                } else if (isSecurity) {
                    // ── BẢO VỆ XƯỞNG: merge cột 3→5, nội dung là số tiền được chia ──
                    putCell(row, 0, String.valueOf(stt), num);
                    putCell(row, 1, u.getFullName(), txt);
                    putCell(row, 2, roleLabelText, txt);

                    sheet.addMergedRegion(new CellRangeAddress(r, r, 3, 5));
                    Cell c = row.createCell(3);
                    // Số tiền được chia cho bảo vệ xưởng — hiển thị như hiện tại.
                    long securityAmount = advance > 0 ? advance : bonus;
                    c.setCellValue(securityAmount);
                    c.setCellStyle(net);
                    putCell(row, 4, "", net);
                    putCell(row, 5, "", net);

                } else {
                    // ── Bình thường ──
                    putCell(row, 0, String.valueOf(stt), num);
                    putCell(row, 1, u.getFullName(), txt);
                    putCell(row, 2, roleLabelText, txt);
                    setMoneyCell(row, 3, bonus, mny);
                    setMoneyCell(row, 4, advance, mny);

                    int exR = r + 1;
                    Cell tc = row.createCell(5);
                    tc.setCellFormula("D" + exR + "-E" + exR);
                    tc.setCellStyle(net);
                }

                r++; stt++;

                // ── DÒNG PHỤ: "Số ngày đi làm" của nhân viên trong tháng ────
                //   Merge A→F, canh trái, thụt lề ~20px (indent = 2). Bỏ qua
                //   BẢO VỆ XƯỞNG vì họ hưởng khoán trọn tháng, không quẹt thẻ
                //   (không có AttendanceEntry và cũng không có ý nghĩa hiển
                //   thị "số ngày đi làm").
                if (!isSecurity) {
                    Row dayRow = sheet.createRow(r);
                    dayRow.setHeightInPoints(18);
                    sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 5));

                    String dayText;

                    // ── NGOẠI LỆ: nhân viên luôn được tính FULL CÔNG ─────────
                    //   Dùng CHUNG nguồn sự thật với FactoryKpiService để tránh
                    //   lệch danh sách khi thêm/bớt người. Nhân viên này mặc
                    //   định đủ công tháng, bất kể có dữ liệu chấm công hay không.
                    //   Hiển thị rõ để kế toán biết đây là ngoại lệ có chủ đích,
                    //   không phải bị sót dữ liệu.
                    boolean alwaysFull = FactoryKpiService.isAlwaysFullAttendance(u);

                    if (alwaysFull) {
                        dayText = "Số ngày đi làm: Mặc định đủ công tháng";
                    } else if (FactoryKpiService.isMaternityLeave(u)) {
                        dayText = "Số ngày đi làm: Nghỉ thai sản theo luật";
                    } else if (attEntry == null) {
                        dayText = "Số ngày đi làm: chưa có dữ liệu chấm công";
                    } else if (Boolean.TRUE.equals(attEntry.getPartTime())) {
                        // ── PARTTIME: đếm theo BUỔI ─────────────────────────
                        //   Dùng presentDays (số ngày có quẹt thẻ) — mỗi ngày
                        //   parttime cover 1 buổi theo cách resolveDay tính. Bậc
                        //   bonus trong FactoryKpiService.attendanceRatio() cũng
                        //   dùng presentDays nên 2 chỗ luôn khớp.
                        Integer present = attEntry.getPresentDays();
                        int n = present != null ? present : 0;
                        dayText = "Số buổi đi làm: " + n + " buổi (Part time)";
                    } else {
                        // ── FULLTIME: đếm theo NGÀY ────────────────────────
                        //   presentDays = số ngày có ít nhất 1 lần quẹt thẻ.
                        //   Luôn là số nguyên. KHÁC actualDays — actualDays đã
                        //   trừ trễ/sớm, nghỉ nửa buổi… nên hay lẻ.
                        Integer present = attEntry.getPresentDays();
                        int n = present != null ? present : 0;
                        dayText = "Số ngày đi làm: " + n + " ngày";
                    }

                    Cell dc = dayRow.createCell(0);
                    dc.setCellValue(dayText);
                    dc.setCellStyle(dayRowStyle);
                    // Các ô còn lại trong vùng merge vẫn cần cellStyle để in
                    // đường viền / nền đồng bộ (không có viền/nền ở đây nên chỉ
                    // để giữ style trắng nhất quán).
                    for (int cc = 1; cc <= 5; cc++) {
                        Cell empty = dayRow.createCell(cc);
                        empty.setCellStyle(dayRowStyle);
                    }
                    r++;
                }
            }

            sheet.setColumnWidth(0, 6 * 256);
            sheet.autoSizeColumn(1);
            sheet.autoSizeColumn(2);
            for (int i = 3; i <= 5; i++) sheet.setColumnWidth(i, 20 * 256);

            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setLandscape(true);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // XUẤT FILE THƯỞNG KẾ TOÁN / KINH DOANH
    //
    // Cột: STT | Họ tên | Chức vụ | Doanh thu (chỉ SALES) | Tỷ lệ KPI | Bonus | Tạm ứng | Tổng
    // Header: Tổng doanh thu thu được, Đơn giá thưởng, Tổng quỹ thưởng
    // ══════════════════════════════════════════════════════════════════════════

    private byte[] exportOfficeBonusReport(int month, int year, PayrollDepartment dept) throws Exception {
        OfficeBonusResult bonusResult = officeBonusResultRepo
                .findByMonthAndYearAndDepartment(month, year, dept).orElse(null);

        String deptLabel = dept.getLabel();
        String monthStr = String.format("%d-%02d", year, month);
        boolean isSales = dept == PayrollDepartment.SALES;

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            ExportStyles styles = new ExportStyles(wb);
            XSSFSheet sheet = wb.createSheet("Thưởng " + deptLabel + " T" + month + "-" + year);
            DataFormat fmt = wb.createDataFormat();
            short vnFmt = fmt.getFormat("#,##0");

            XSSFCellStyle mvn = wb.createCellStyle(); mvn.cloneStyleFrom(styles.moneyOdd); mvn.setDataFormat(vnFmt);
            XSSFCellStyle mve = wb.createCellStyle(); mve.cloneStyleFrom(styles.moneyEven); mve.setDataFormat(vnFmt);
            XSSFCellStyle nvn = wb.createCellStyle(); nvn.cloneStyleFrom(styles.netOdd); nvn.setDataFormat(vnFmt);
            XSSFCellStyle nve = wb.createCellStyle(); nve.cloneStyleFrom(styles.netEven); nve.setDataFormat(vnFmt);

            // SALES: +1 cột Doanh thu
            int COLS = isSales ? 8 : 7;
            int r = 0;

            // Title
            Row r0 = sheet.createRow(r); r0.setHeightInPoints(30);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, COLS-1));
            putCell(r0, 0, "CÔNG TY TNHH NHẤT NAM", styles.companyName); r++;

            Row r1 = sheet.createRow(r); r1.setHeightInPoints(36);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, COLS-1));
            putCell(r1, 0, "BẢNG THƯỞNG DOANH THU " + deptLabel.toUpperCase() + " THÁNG " + month + "/" + year, styles.title); r++;

            // Info header
            String totalRevStr = bonusResult != null ? fmtVNd(bonusResult.getTotalRevenue().longValue()) : "—";
            String poolStr = bonusResult != null ? fmtVNd(bonusResult.getTotalBonusPool()) : "—";
            int txCount = bonusResult != null && bonusResult.getTransactionCount() != null ? bonusResult.getTransactionCount() : 0;

            Row r2 = sheet.createRow(r); r2.setHeightInPoints(22);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, COLS-1));
            putCell(r2, 0, "Tổng doanh thu thu được: " + totalRevStr
                    + "  ·  Số phiếu thanh toán: " + txCount
                    + "  ·  Tổng quỹ thưởng: " + poolStr
                    + "  ·  Thang: 400.000đ mỗi 100tr doanh thu", styles.subTitle); r++;
            r++;

            // Table header
            Row hdr = sheet.createRow(r); hdr.setHeightInPoints(36);
            int c = 0;
            putCell(hdr, c++, "STT", styles.headerCell);
            putCell(hdr, c++, "Họ và tên", styles.headerCell);
            putCell(hdr, c++, "Chức vụ", styles.headerCell);
            if (isSales) putCell(hdr, c++, "Doanh thu\ncá nhân", styles.headerCell);
            putCell(hdr, c++, "Tỷ lệ\nKPI (%)", styles.headerCell);
            putCell(hdr, c++, "Bonus\n(thưởng DT)", styles.headerCell);
            putCell(hdr, c++, "Tạm ứng", styles.headerCell);
            putCell(hdr, c++, "Tổng thưởng\n(Bonus - Tạm ứng)", styles.headerNetCell);
            sheet.createFreezePane(0, r + 1); r++;

            // Data
            List<OfficeBonusItem> items = bonusResult != null
                    ? new ArrayList<>(bonusResult.getItems())
                    : new ArrayList<>();
            int stt = 1;

            for (OfficeBonusItem item : items) {
                boolean even = stt % 2 == 0;
                CellStyle mny = even ? mve : mvn;
                CellStyle net = even ? nve : nvn;
                CellStyle txt = even ? styles.dataEven : styles.dataOdd;
                CellStyle num = even ? styles.numEven : styles.numOdd;

                long bonus = item.getBonusAmount() != null ? item.getBonusAmount() : 0L;
                double kpiPct = item.getKpiPercent() != null ? item.getKpiPercent() : 100.0;

                var adv = item.getUser() != null
                        ? expenseVoucherRepo.sumSalaryAdvance(item.getUser().getId(), monthStr, ExpenseVoucher.VoucherStatus.APPROVED)
                        : null;
                long advance = adv != null ? adv.longValue() : 0;

                Row row = sheet.createRow(r); row.setHeightInPoints(24);
                c = 0;
                putCell(row, c++, String.valueOf(stt), num);
                putCell(row, c++, item.getUserFullName(), txt);
                putCell(row, c++, item.getRoleLabel(), txt);
                if (isSales) {
                    setMoneyCell(row, c++, item.getRevenue() != null ? item.getRevenue().longValue() : 0L, mny);
                }
                putCell(row, c++, String.format("%.0f%%", kpiPct), num);
                setMoneyCell(row, c++, bonus, mny);
                setMoneyCell(row, c++, advance, mny);

                // Tổng = Bonus - Tạm ứng (công thức Excel)
                int exR = r + 1;
                int bonusCol = isSales ? 5 : 4;  // E or F
                int advCol = bonusCol + 1;
                String bonusRef = colLetter(bonusCol) + exR;
                String advRef = colLetter(advCol) + exR;
                Cell tc = row.createCell(c);
                tc.setCellFormula(bonusRef + "-" + advRef);
                tc.setCellStyle(net);

                r++; stt++;
            }

            sheet.setColumnWidth(0, 6*256);
            sheet.autoSizeColumn(1);
            sheet.autoSizeColumn(2);
            for (int i = 3; i < COLS; i++) sheet.setColumnWidth(i, 18*256);

            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setLandscape(true);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short)1);
            sheet.getPrintSetup().setFitHeight((short)0);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Chuyển số cột (0-based) thành chữ cái Excel: 0→A, 1→B, ... 25→Z. */
    private static String colLetter(int col) {
        return String.valueOf((char) ('A' + col));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // XUẤT FILE THƯỞNG TÀI XẾ + HỖ TRỢ GIAO HÀNG
    //
    // Layout: mỗi nhân viên = 2 dòng Excel
    //   Dòng 1: STT | Họ tên | Chức vụ | Phụ cấp xăng | Thưởng xe máy | Thưởng xe tải | Tạm ứng | Tổng
    //   Dòng 2: __ | __ | __ | "X km × Yđ/km" | "X lượt × Yđ/lượt" | "X lượt × Yđ/lượt" | __ | =D-F+H-J
    // ══════════════════════════════════════════════════════════════════════════

    private static class DriverBonusRow {
        String name;
        String role;
        boolean nonDriver;
        double km;
        long fuelPay;
        int tripsMoto;
        long bonusPayMoto;
        int tripsTruck;
        long bonusPayTruck;
        long totalBonus;
        long advance;
        Long userId;
    }

    private byte[] exportDriverBonusReport(int month, int year) throws Exception {
        var cfg = factoryPayrollService.driverPayrollConfig(month, year);
        Long gasPrice   = cfg.getGasPrice();
        Long bonusMoto  = cfg.getBonusUnitPrice();
        Long bonusTruck = cfg.getTruckBonusUnitPrice();
        var driverRows  = cfg.getRows() != null ? cfg.getRows() : List.<com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto>of();
        var nonDriverRw = cfg.getNonDriverRows() != null ? cfg.getNonDriverRows() : List.<com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto>of();

        String monthStr = String.format("%d-%02d", year, month);
        List<DriverBonusRow> allRows = new ArrayList<>();

        // ── Tài xế thật ──
        for (var dr : driverRows) {
            DriverBonusRow br = new DriverBonusRow();
            br.name = dr.getDriverName();
            br.nonDriver = false;
            br.userId = dr.getUserId();

            if (dr.getMotorbike() != null) {
                br.km = dr.getMotorbike().getTotalKm() != null ? dr.getMotorbike().getTotalKm() : 0;
                br.fuelPay = nz(dr.getMotorbike().getFuelPay());
                br.tripsMoto = dr.getMotorbike().getTotalTrips() != null ? dr.getMotorbike().getTotalTrips() : 0;
                br.bonusPayMoto = nz(dr.getMotorbike().getBonusPay());
            }
            if (dr.getTruck() != null) {
                br.tripsTruck = dr.getTruck().getTotalTrips() != null ? dr.getTruck().getTotalTrips() : 0;
                br.bonusPayTruck = nz(dr.getTruck().getBonusPay());
            }

            br.totalBonus = br.fuelPay + br.bonusPayMoto + br.bonusPayTruck;
            if (br.totalBonus == 0 && br.km == 0) continue;

            br.role = dr.getVehicleType() != null
                    ? switch (dr.getVehicleType()) {
                case "BOTH" -> "Tài xế (Xe máy + Xe tải)";
                case "TRUCK" -> "Tài xế (Xe tải)";
                default -> "Tài xế (Xe máy)";
            }
                    : "Tài xế";

            if (br.userId != null) {
                var adv = expenseVoucherRepo.sumSalaryAdvance(
                        br.userId, monthStr, ExpenseVoucher.VoucherStatus.APPROVED);
                br.advance = adv != null ? adv.longValue() : 0;
            }

            allRows.add(br);
        }

        // ── Nhân viên hỗ trợ giao hàng ──
        for (var nr : nonDriverRw) {
            DriverBonusRow br = new DriverBonusRow();
            br.name = nr.getDriverName();
            br.nonDriver = true;
            br.userId = nr.getUserId();

            if (nr.getMotorbike() != null) {
                br.km = nr.getMotorbike().getTotalKm() != null ? nr.getMotorbike().getTotalKm() : 0;
                br.fuelPay = nz(nr.getMotorbike().getFuelPay());
                br.tripsMoto = nr.getMotorbike().getTotalTrips() != null ? nr.getMotorbike().getTotalTrips() : 0;
                br.bonusPayMoto = nz(nr.getMotorbike().getBonusPay());
            }
            if (nr.getTruck() != null) {
                br.tripsTruck = nr.getTruck().getTotalTrips() != null ? nr.getTruck().getTotalTrips() : 0;
                br.bonusPayTruck = nz(nr.getTruck().getBonusPay());
            }

            br.totalBonus = br.fuelPay + br.bonusPayMoto + br.bonusPayTruck;
            if (br.totalBonus == 0 && br.km == 0) continue;

            br.role = "Hỗ trợ giao hàng";
            if (nr.getDepartment() != null && !nr.getDepartment().isBlank())
                br.role += " / " + nr.getDepartment();
            if (nr.getPosition() != null && !nr.getPosition().isBlank())
                br.role += " / " + nr.getPosition();

            if (br.userId != null) {
                var adv = expenseVoucherRepo.sumSalaryAdvance(
                        br.userId, monthStr, ExpenseVoucher.VoucherStatus.APPROVED);
                br.advance = adv != null ? adv.longValue() : 0;
            }

            allRows.add(br);
        }

        // ── Build Excel ──
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            ExportStyles styles = new ExportStyles(wb);
            XSSFSheet sheet = wb.createSheet("Thưởng tài xế T" + month + "-" + year);

            DataFormat fmt = wb.createDataFormat();
            short vnFmt = fmt.getFormat("#,##0");

            // Sub-detail style (dòng 2: km × giá, lượt × giá)
            XSSFFont subFont = wb.createFont();
            subFont.setFontName("Arial");
            subFont.setFontHeightInPoints((short) 9);
            subFont.setItalic(true);
            subFont.setColor(new XSSFColor(new byte[]{(byte)0x66,(byte)0x66,(byte)0x66}, null));

            XSSFCellStyle subOdd = wb.createCellStyle();
            subOdd.setFont(subFont);
            subOdd.setAlignment(HorizontalAlignment.RIGHT);
            subOdd.setVerticalAlignment(VerticalAlignment.CENTER);

            XSSFCellStyle subEven = wb.createCellStyle();
            subEven.cloneStyleFrom(subOdd);
            subEven.setFillForegroundColor(new XSSFColor(new byte[]{(byte)0xD6,(byte)0xE4,(byte)0xF0}, null));
            subEven.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Money styles with VN format
            XSSFCellStyle mvnOdd = wb.createCellStyle();
            mvnOdd.cloneStyleFrom(styles.moneyOdd);
            mvnOdd.setDataFormat(vnFmt);

            XSSFCellStyle mvnEven = wb.createCellStyle();
            mvnEven.cloneStyleFrom(styles.moneyEven);
            mvnEven.setDataFormat(vnFmt);

            XSSFCellStyle nvnOdd = wb.createCellStyle();
            nvnOdd.cloneStyleFrom(styles.netOdd);
            nvnOdd.setDataFormat(vnFmt);

            XSSFCellStyle nvnEven = wb.createCellStyle();
            nvnEven.cloneStyleFrom(styles.netEven);
            nvnEven.setDataFormat(vnFmt);

            int r = 0;
            int TOTAL_COLS = 8;

            // Header
            Row r0 = sheet.createRow(r);
            r0.setHeightInPoints(30);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, TOTAL_COLS - 1));
            putCell(r0, 0, "CÔNG TY TNHH NHẤT NAM", styles.companyName);
            r++;

            Row r1 = sheet.createRow(r);
            r1.setHeightInPoints(36);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, TOTAL_COLS - 1));
            putCell(r1, 0, "BẢNG THƯỞNG TÀI XẾ & HỖ TRỢ GIAO HÀNG THÁNG " + month + "/" + year, styles.title);
            r++;

            Row r2 = sheet.createRow(r);
            r2.setHeightInPoints(22);
            sheet.addMergedRegion(new CellRangeAddress(r, r, 0, TOTAL_COLS - 1));
            putCell(r2, 0, "Giá xăng: " + fmtVNd(gasPrice) + "/km  ·  Thưởng xe máy: " + fmtVNd(bonusMoto)
                    + "/lượt  ·  Thưởng xe tải: " + fmtVNd(bonusTruck) + "/lượt  ·  Ngày tạo: "
                    + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), styles.subTitle);
            r++;
            r++; // blank

            // Table header
            Row hdr = sheet.createRow(r);
            hdr.setHeightInPoints(40);
            putCell(hdr, 0, "STT",               styles.headerCell);
            putCell(hdr, 1, "Họ và tên",         styles.headerCell);
            putCell(hdr, 2, "Chức vụ",           styles.headerCell);
            putCell(hdr, 3, "Phụ cấp\nxăng xe",  styles.headerCell);
            putCell(hdr, 4, "Thưởng đơn\nxe máy", styles.headerCell);
            putCell(hdr, 5, "Thưởng đơn\nxe tải", styles.headerCell);
            putCell(hdr, 6, "Tạm ứng",            styles.headerCell);
            putCell(hdr, 7, "Tổng phụ cấp\n/ thưởng", styles.headerNetCell);
            sheet.createFreezePane(0, r + 1);
            r++;

            // ── Nhóm tài xế ──
            var drivers  = allRows.stream().filter(b -> !b.nonDriver).toList();
            var supports = allRows.stream().filter(b -> b.nonDriver).toList();

            int stt = 1;

            if (!drivers.isEmpty()) {
                Row dh = sheet.createRow(r);
                dh.setHeightInPoints(28);
                sheet.addMergedRegion(new CellRangeAddress(r, r, 0, TOTAL_COLS - 1));
                putCell(dh, 0, "TÀI XẾ", styles.deptHeader);
                r++;

                for (DriverBonusRow br : drivers) {
                    boolean even = (stt % 2 == 0);
                    CellStyle mny = even ? mvnEven : mvnOdd;
                    CellStyle net = even ? nvnEven : nvnOdd;
                    CellStyle txt = even ? styles.dataEven : styles.dataOdd;
                    CellStyle num = even ? styles.numEven : styles.numOdd;
                    CellStyle sub = even ? subEven : subOdd;
                    CellStyle bg  = even ? styles.dataEven : styles.dataOdd;

                    // Dòng 1: số tiền
                    Row row1a = sheet.createRow(r);
                    row1a.setHeightInPoints(24);
                    putCell(row1a, 0, String.valueOf(stt), num);
                    putCell(row1a, 1, br.name != null ? br.name : "", txt);
                    putCell(row1a, 2, br.role != null ? br.role : "", txt);
                    setMoneyCell(row1a, 3, br.fuelPay, mny);
                    setMoneyCell(row1a, 4, br.bonusPayMoto, mny);
                    setMoneyCell(row1a, 5, br.bonusPayTruck, mny);
                    setMoneyCell(row1a, 6, br.advance, mny);
                    // Tổng = xăng + xe máy + xe tải - tạm ứng  → CÔNG THỨC EXCEL
                    int excelRow1 = r + 1; // Excel 1-based
                    Cell totalCell = row1a.createCell(7);
                    totalCell.setCellFormula("D" + excelRow1 + "+E" + excelRow1 + "+F" + excelRow1 + "-G" + excelRow1);
                    totalCell.setCellStyle(net);
                    r++;

                    // Dòng 2: chi tiết (km × giá, lượt × giá)
                    Row row2a = sheet.createRow(r);
                    row2a.setHeightInPoints(16);
                    putCell(row2a, 0, "", bg);
                    putCell(row2a, 1, "", bg);
                    putCell(row2a, 2, "", bg);
                    putCell(row2a, 3, fmtDetail(br.km, "km", gasPrice, "đ/km"), sub);
                    putCell(row2a, 4, fmtTripDetail(br.tripsMoto, bonusMoto, "đ/lượt"), sub);
                    putCell(row2a, 5, fmtTripDetail(br.tripsTruck, bonusTruck, "đ/lượt"), sub);
                    putCell(row2a, 6, "", bg);
                    putCell(row2a, 7, "", bg);
                    r++;
                    stt++;
                }
            }

            if (!supports.isEmpty()) {
                if (!drivers.isEmpty()) {
                    sheet.createRow(r).setHeightInPoints(10); r++;
                    sheet.createRow(r).setHeightInPoints(10); r++;
                }

                Row dh = sheet.createRow(r);
                dh.setHeightInPoints(28);
                sheet.addMergedRegion(new CellRangeAddress(r, r, 0, TOTAL_COLS - 1));
                putCell(dh, 0, "HỖ TRỢ GIAO HÀNG", styles.deptHeader);
                r++;

                for (DriverBonusRow br : supports) {
                    boolean even = (stt % 2 == 0);
                    CellStyle mny = even ? mvnEven : mvnOdd;
                    CellStyle net = even ? nvnEven : nvnOdd;
                    CellStyle txt = even ? styles.dataEven : styles.dataOdd;
                    CellStyle num = even ? styles.numEven : styles.numOdd;
                    CellStyle sub = even ? subEven : subOdd;
                    CellStyle bg  = even ? styles.dataEven : styles.dataOdd;

                    Row row1a = sheet.createRow(r);
                    row1a.setHeightInPoints(24);
                    putCell(row1a, 0, String.valueOf(stt), num);
                    putCell(row1a, 1, br.name != null ? br.name : "", txt);
                    putCell(row1a, 2, br.role != null ? br.role : "", txt);
                    setMoneyCell(row1a, 3, br.fuelPay, mny);
                    setMoneyCell(row1a, 4, br.bonusPayMoto, mny);
                    setMoneyCell(row1a, 5, br.bonusPayTruck, mny);
                    setMoneyCell(row1a, 6, br.advance, mny);
                    int excelRow1 = r + 1;
                    Cell totalCell = row1a.createCell(7);
                    totalCell.setCellFormula("D" + excelRow1 + "+E" + excelRow1 + "+F" + excelRow1 + "-G" + excelRow1);
                    totalCell.setCellStyle(net);
                    r++;

                    Row row2a = sheet.createRow(r);
                    row2a.setHeightInPoints(16);
                    putCell(row2a, 0, "", bg);
                    putCell(row2a, 1, "", bg);
                    putCell(row2a, 2, "", bg);
                    putCell(row2a, 3, fmtDetail(br.km, "km", gasPrice, "đ/km"), sub);
                    putCell(row2a, 4, fmtTripDetail(br.tripsMoto, bonusMoto, "đ/lượt"), sub);
                    putCell(row2a, 5, fmtTripDetail(br.tripsTruck, bonusTruck, "đ/lượt"), sub);
                    putCell(row2a, 6, "", bg);
                    putCell(row2a, 7, "", bg);
                    r++;
                    stt++;
                }
            }

            // Widths
            sheet.setColumnWidth(0, 6 * 256);
            sheet.autoSizeColumn(1);  // Họ tên — auto fit
            sheet.autoSizeColumn(2);  // Chức vụ — auto fit
            for (int i = 3; i <= 7; i++) sheet.setColumnWidth(i, 20 * 256);

            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getPrintSetup().setLandscape(true);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Format chi tiết xăng xe: "1.225 km × 657đ/km" */
    private static String fmtDetail(double qty, String qtyUnit, Long unitPrice, String priceUnit) {
        if (qty <= 0 && (unitPrice == null || unitPrice == 0)) return "—";
        String qStr = qty == Math.floor(qty) ? fmtVNi((long) qty) : String.format(java.util.Locale.US, "%,.1f", qty);
        return qStr + " " + qtyUnit + " × " + fmtVNd(unitPrice);
    }

    /** Format chi tiết lượt: "437 lượt × 5.000đ/lượt" */
    private static String fmtTripDetail(int trips, Long unitPrice, String priceUnit) {
        if (trips == 0 && (unitPrice == null || unitPrice == 0)) return "—";
        return fmtVNi(trips) + " lượt × " + fmtVNd(unitPrice);
    }

    /** Format tiền VN: 10000 → "10.000đ" */
    private static String fmtVNd(Long v) {
        if (v == null || v == 0) return "0đ";
        return fmtVNi(v) + "đ";
    }

    /**
     * Format số Kg theo kiểu Việt Nam: 7502.3 → "7.502,3".
     * Dùng cho phần "(7.502,3Kg)" hiển thị bên cạnh Quỹ thưởng tháng — cho
     * người xem biết quỹ đó dựa trên bao nhiêu kg sản lượng.
     */
    private static String fmtKg(java.math.BigDecimal v) {
        if (v == null) return "0";
        return String.format(java.util.Locale.GERMANY, "%,.1f", v.doubleValue());
    }

    /**
     * Format số NGÀY CÔNG kiểu Việt Nam, LÊN tới 2 số thập phân và LUÔN cắt các
     * số 0 phía sau: 22.0 → "22", 22.5 → "22,5", 22.64 → "22,64".
     * Dùng cho dòng phụ "Số ngày đi làm: X ngày" dưới mỗi nhân viên trong
     * bảng thưởng xưởng sản xuất.
     */
    private static String fmtDays(Double v) {
        if (v == null) return "0";
        double d = v;
        // Tránh hiển thị 22.0000001 → gộp về 2 số thập phân
        d = Math.round(d * 100.0) / 100.0;
        if (d == Math.floor(d)) {
            return String.format(java.util.Locale.GERMANY, "%,d", (long) d);
        }
        // Có phần lẻ — dùng phẩy VN, tự cắt trailing 0 (22,50 → 22,5)
        String s = String.format(java.util.Locale.GERMANY, "%,.2f", d);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Format số VN: 1234567 → "1.234.567" */
    private static String fmtVNi(long v) {
        if (v == 0) return "0";
        String s = String.valueOf(Math.abs(v));
        StringBuilder sb = new StringBuilder();
        int cnt = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (cnt > 0 && cnt % 3 == 0) sb.insert(0, '.');
            sb.insert(0, s.charAt(i));
            cnt++;
        }
        return v < 0 ? "-" + sb : sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THU THẬP DỮ LIỆU
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Danh sách role BỊ BỎ QUA khỏi file lương/thưởng — quản lý cấp cao trở lên
     * không tính lương chung với nhân viên.
     *
     * <p>[Task #5, 2026] Chủ tịch (OWNER) / Giám đốc (ADMIN) / Kế toán trưởng
     * (SUPER_ACCOUNTANT) có cơ chế trả lương riêng, không đưa vào file này.
     */
    private static final Set<Role> EXCLUDED_ROLES = Set.of(
            Role.OWNER, Role.ADMIN, Role.SUPER_ACCOUNTANT
    );

    /**
     * Bộ phận được xem như văn phòng đủ công cố định — không phụ thuộc file
     * chấm công. [Task #4, 2026] Đủ công = số ngày làm việc chuẩn của tháng
     * (T2–T7), phụ cấp cơm = ngày chuẩn × 30.000đ. Xưởng SX + Tài xế vẫn tính
     * theo chấm công / điểm danh thật.
     */
    // PHASE 5 refactor (10/2026): kế toán, kinh doanh, kho giờ cũng tính theo
    // FILE CHẤM CÔNG CHUNG như xưởng và tài xế — không còn mặc định full công.
    // Nếu NV văn phòng vẫn muốn cách tính full-công tự động, hãy để tên NV
    // ngoài file chấm công hoặc dùng ManualAttendanceOverrides để fix tay.
    // Giữ set để hành vi code còn lại không gãy (empty = không ai auto-full).
    private static final Set<PayrollDepartment> OFFICE_FULL_ATTENDANCE_DEPTS = Set.of();

    private List<EmployeeRow> collectEmployees(String deptCode, int month, int year, double stdWorkdays) {
        PayrollDepartment dept = PayrollDepartment.parse(deptCode);
        if (dept == null) return new ArrayList<>();

        List<EmployeeRow> rows = new ArrayList<>();

        // Xuất file lương: BAO GỒM CẢ nhân viên đang bị khoá tài khoản để
        // vẫn có lương cho phần công/km đã làm trước khi bị khoá. Riêng phần
        // THƯỞNG/KPI của họ sẽ bằng 0 (do HrService/KpiService đã tự loại).
        List<User> employees = deptResolver.employeesWithLockedOf(dept);
        for (User u : employees) {
            if (EXCLUDED_USERNAME.equalsIgnoreCase(u.getUsername())) continue;

            // ── Task #5: bỏ Chủ tịch / Giám đốc / Kế toán trưởng ──────────
            Role payrollRole = deptResolver.payrollRoleOf(u);
            if (payrollRole != null && EXCLUDED_ROLES.contains(payrollRole)) continue;

            // Bảo vệ Q9 (attendanceExempt / FACTORY_SECURITY): tuỳ cờ INCLUDE_SECURITY_IN_SALARY
            if (!INCLUDE_SECURITY_IN_SALARY && deptResolver.isAttendanceExempt(u)) continue;

            // PHASE 5 (10/2026): Tài xế dùng CHUNG hrService như các bộ phận khác.
            //   - Lương theo chấm công, phụ cấp cơm, OT: lấy từ file chấm công
            //     chung (qua AttendanceEntry) — xem HrService.getSalaryBreakdownForUser.
            //   - Phụ cấp XĂNG XE và THƯỞNG ĐƠN HÀNG (dựa trên odo): KHÔNG còn
            //     nằm trong breakdown chính; chúng xuất hiện riêng ở file
            //     Thưởng tài xế (exportDriverBonusReport) với số liệu từ
            //     driverSalaryDetail cũ. Odo chỉ còn dùng để tính xăng/thưởng.
            SalaryBreakdownDto breakdown = hrService.getSalaryBreakdownForUser(u.getId(), month, year);
            if (breakdown == null) continue;

            // Chỉ hiển thị nhân viên có lương
            boolean hasSalary = (breakdown.getBaseSalary() != null && breakdown.getBaseSalary() > 0)
                    || (breakdown.getNetSalary() != null && breakdown.getNetSalary() > 0)
                    || (breakdown.getGrossSalary() != null && breakdown.getGrossSalary() > 0);
            if (!hasSalary) continue;

            // ── OVERRIDE role theo tên (VD Ngô Thị Mỹ Hạnh → Đóng gói) ───
            //   Đặt TRƯỚC khi gán row.payrollRole để sort + label đều dùng
            //   role đã override.
            Role effectiveRole = FactoryKpiService.overridePackagingRole(u, payrollRole);

            EmployeeRow row = new EmployeeRow();
            row.user = u;
            row.breakdown = breakdown;
            row.payrollRole = effectiveRole;
            // Nhãn chức vụ cũng theo role đã override để nhất quán
            row.roleLabel = effectiveRole != null
                    ? FactoryKpiService.ROLE_LABELS.getOrDefault(effectiveRole,
                    deptResolver.roleLabelOf(u))
                    : deptResolver.roleLabelOf(u);
            row.deptCode = deptCode;
            row.factorySecurity = (effectiveRole == Role.FACTORY_SECURITY);
            row.isDriver = (dept == PayrollDepartment.DRIVER);
            row.month = month;
            row.year  = year;

            // ── Ngày công ──────────────────────────────────────────────
            row.standardWorkdays = stdWorkdays;

            if (OFFICE_FULL_ATTENDANCE_DEPTS.contains(dept)) {
                // ── Task #4: Kế toán / Kinh doanh / Kho auto-full công ──
                //   Không phụ thuộc file chấm công. Đủ công = ngày chuẩn của
                //   tháng. Phụ cấp cơm cũng auto-full (đã xử lý trong
                //   HrService.mealAllowanceFor cho case entry == null).
                row.actualWorkdays = stdWorkdays;
                row.actualAttendanceDays = (int) Math.round(stdWorkdays);
            } else if (dept.isAttendanceBased()) {
                // Bộ phận có chấm công thật (Xưởng SX): lấy từ AttendanceEntry
                AttendanceEntry entry = entryRepo
                        .findByUserAndPeriod(u.getId(), month, year).orElse(null);
                if (entry != null) {
                    row.actualWorkdays = entry.getActualDays() != null ? entry.getActualDays() : 0;
                    // presentDays = số ngày có ít nhất 1 lần quẹt thẻ
                    row.actualAttendanceDays = entry.getPresentDays() != null ? entry.getPresentDays() : 0;
                } else {
                    row.actualWorkdays = 0;
                    row.actualAttendanceDays = 0;
                }
            }

            // ── OVERRIDE công thực tế (nếu có) ──────────────────────────────
            // Áp dụng SAU khi set công theo attendance — override được ưu
            // tiên bất kể bộ phận. Dùng khi HR biết nhân viên có đi làm
            // nhưng chấm công tự động ghi sót (quên quẹt thẻ, máy hỏng…).
            // Xem com.nhatnam.server.utils.ManualAttendanceOverrides.
            com.nhatnam.server.utils.ManualAttendanceOverrides.Override attOverride =
                    com.nhatnam.server.utils.ManualAttendanceOverrides.lookup(
                            u.getFullName(), year, month);
            if (attOverride != null) {
                row.actualWorkdays       = attOverride.actualDays();
                row.actualAttendanceDays = attOverride.actualDays();
            }

            // ── CỘNG CÔNG LỄ cho FACTORY và DRIVER ────────────────────────
            //   File chấm công xưởng và dữ liệu điểm danh ODO của tài xế
            //   KHÔNG có bản ghi của ngày lễ (NV không đi làm hôm đó). Nếu
            //   không bù lại, NV đi làm cả tháng sẽ bị thiếu đúng số ngày lễ
            //   trong tháng (VD T9/2026 thiếu 2 ngày 1-2/9) và bị trừ lương
            //   sai dù ngày lễ hợp pháp vẫn được hưởng lương.
            //
            //   Áp SAU khi đã đọc attendance/driver VÀ sau khi áp override.
            //   Override có skipHolidayBonus = true (VD Tuấn Tài vào làm 9/9,
            //   sau lễ) sẽ không được cộng — holidayBonusDaysFor trả về 0.
            //
            //   SALES/ACCOUNTING/WAREHOUSE không áp — đã auto-full theo
            //   stdWorkdays ở nhánh OFFICE_FULL_ATTENDANCE_DEPTS phía trên.
            //
            //   Cap tại stdWorkdays để tránh dôi nếu attendance đã bao gồm
            //   FULL_DAY_OFF cho ngày lễ (phòng khi sau này file chấm công
            //   bắt đầu có bản ghi lễ).
            if (dept == PayrollDepartment.FACTORY || dept == PayrollDepartment.DRIVER) {
                int holidayBonus = com.nhatnam.server.utils.ManualAttendanceOverrides
                        .holidayBonusDaysFor(u.getFullName(), year, month);
                if (holidayBonus > 0) {
                    double bumped = Math.min(stdWorkdays, row.actualWorkdays + holidayBonus);
                    row.actualWorkdays = bumped;
                    row.actualAttendanceDays = (int) Math.round(bumped);
                }
            }

            rows.add(row);
        }

        // Sắp xếp theo cấp bậc (role đã override), cùng bậc thì theo tên
        rows.sort(Comparator
                .comparingInt((EmployeeRow er) -> roleRank(deptCode, er.payrollRole))
                .thenComparing(er -> er.user.getFullName() != null ? er.user.getFullName() : "",
                        String.CASE_INSENSITIVE_ORDER));

        return rows;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WRITE EXCEL
    // ══════════════════════════════════════════════════════════════════════════

    private int writeHeader(XSSFSheet sheet, ExportStyles s, int r, int month, int year,
                            boolean includeSalary, boolean includeBonus, String[] allowanceLabels) {
        int totalCols = calcTotalColumns(includeSalary, includeBonus, allowanceLabels);
        LocalDate today = LocalDate.now();
        String createdDate = today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        Row row0 = sheet.createRow(r);
        row0.setHeightInPoints(30);
        if (totalCols > 1) sheet.addMergedRegion(new CellRangeAddress(r, r, 0, totalCols - 1));
        putCell(row0, 0, "CÔNG TY TNHH NHẤT NAM", s.companyName);
        r++;

        Row row1 = sheet.createRow(r);
        row1.setHeightInPoints(36);
        if (totalCols > 1) sheet.addMergedRegion(new CellRangeAddress(r, r, 0, totalCols - 1));
        String typeLabel = includeSalary && includeBonus ? "LƯƠNG VÀ THƯỞNG"
                : includeSalary ? "LƯƠNG" : "THƯỞNG";
        putCell(row1, 0, "BẢNG " + typeLabel + " THÁNG " + month + "/" + year, s.title);
        r++;

        Row row2 = sheet.createRow(r);
        row2.setHeightInPoints(22);
        if (totalCols > 1) sheet.addMergedRegion(new CellRangeAddress(r, r, 0, totalCols - 1));
        putCell(row2, 0, "Ngày tạo phiếu: " + createdDate, s.subTitle);
        r++;

        return r;
    }

    private int writeTableHeader(XSSFSheet sheet, ExportStyles s, int r,
                                 boolean includeSalary, boolean includeBonus, String[] allowanceLabels) {
        Row row = sheet.createRow(r);
        row.setHeightInPoints(40);

        int col = 0;
        putCell(row, col++, "STT",       s.headerCell);
        putCell(row, col++, "Họ và tên", s.headerCell);
        putCell(row, col++, "Chức vụ",   s.headerCell);

        if (includeSalary) {
            putCell(row, col++, "Lương cơ bản",           s.headerCell);
            putCell(row, col++, "Lương theo\nchấm công",  s.headerCell);
            putCell(row, col++, "Mức lương\nđóng BH",             s.headerCell);
            putCell(row, col++, "Tổng tiền\nBH (32%)",            s.headerCell);
            for (String label : allowanceLabels) {
                putCell(row, col++, label, s.headerCell);
            }
        }

        if (includeBonus) {
            putCell(row, col++, "Thưởng", s.headerCell);
        }

        putCell(row, col, "Lương\nthực nhận", s.headerNetCell);

        sheet.createFreezePane(0, r + 1);
        return r + 1;
    }

    private int writeDepartmentHeader(XSSFSheet sheet, ExportStyles s, int r,
                                      String deptCode, boolean includeSalary, boolean includeBonus,
                                      String[] allowanceLabels) {
        int totalCols = calcTotalColumns(includeSalary, includeBonus, allowanceLabels);
        Row row = sheet.createRow(r);
        row.setHeightInPoints(28);
        if (totalCols > 1) sheet.addMergedRegion(new CellRangeAddress(r, r, 0, totalCols - 1));
        String label = DEPARTMENT_LABELS.getOrDefault(deptCode, deptCode).toUpperCase();
        putCell(row, 0, label, s.deptHeader);
        return r + 1;
    }

    /** Lương cố định bảo vệ xưởng (thuê ngoài). */
    private static final long FACTORY_SECURITY_NET = 7_000_000L;

    private int writeEmployeeRow(XSSFSheet sheet, ExportStyles s, int r, int stt,
                                 EmployeeRow empRow, boolean includeSalary, boolean includeBonus,
                                 String[] allowanceLabels) {
        SalaryBreakdownDto bd = empRow.breakdown;
        boolean even = (stt % 2 == 0);
        CellStyle txtStyle   = even ? s.dataEven  : s.dataOdd;
        CellStyle moneyStyle = even ? s.moneyEven : s.moneyOdd;
        CellStyle netStyle   = even ? s.netEven   : s.netOdd;
        int totalCols = calcTotalColumns(includeSalary, includeBonus, allowanceLabels);

        boolean isSecurity = empRow.factorySecurity;

        boolean isMaternityLeave = FactoryKpiService.isMaternityLeave(empRow.user);

        if (isMaternityLeave) {
            // Dòng chính: 3 cột đầu bình thường
            Row row = sheet.createRow(r);
            row.setHeightInPoints(24);

            int col = 0;
            putCell(row, col++, String.valueOf(stt), even ? s.numEven : s.numOdd);
            putCell(row, col++, empRow.user.getFullName() != null
                    ? empRow.user.getFullName() : empRow.user.getUsername(), txtStyle);
            putCell(row, col++, empRow.roleLabel != null ? empRow.roleLabel : "", txtStyle);

            // Merge phần còn lại (trừ cột "Lương thực nhận" cuối)
            int mergeStart = col;                      // = 3 → cột D
            int mergeEnd   = totalCols - 2;            // ngay trước Lương thực nhận

            if (mergeEnd >= mergeStart) {
                if (mergeEnd > mergeStart) {
                    sheet.addMergedRegion(new CellRangeAddress(r, r, mergeStart, mergeEnd));
                }
                // Text căn giữa, in nghiêng, nền xám nhạt để phân biệt
                Cell c = row.createCell(mergeStart);
                c.setCellValue("Nghỉ thai sản theo luật lao động");
                c.setCellStyle(even ? s.italicMergedEven : s.italicMergedOdd);
                for (int cc = mergeStart + 1; cc <= mergeEnd; cc++) {
                    Cell empty = row.createCell(cc);
                    empty.setCellStyle(even ? s.italicMergedEven : s.italicMergedOdd);
                }
            }

            // Cột cuối: Lương thực nhận = 0đ (vẫn hiển thị số 0 để cột không trống)
            setMoneyCell(row, totalCols - 1, 0L, netStyle);

            r++;

            // Dòng phụ: giữ nguyên "Số ngày đi làm: Nghỉ thai sản theo luật"
            Row subRow = sheet.createRow(r);
            subRow.setHeightInPoints(16);
            CellStyle bgStyle = even ? s.dataEven : s.dataOdd;
            CellStyle subLine = even ? s.workdaySubEven : s.workdaySubOdd;

            putCell(subRow, 0, "", bgStyle);
            putCell(subRow, 1, "  ↳ Số ngày đi làm: Nghỉ thai sản theo luật", subLine);
            for (int c2 = 2; c2 < totalCols; c2++) {
                putCell(subRow, c2, "", bgStyle);
            }
            r++;

            return r;
        }


        // ── Dòng chính ──────────────────────────────────────────────────────
        Row row = sheet.createRow(r);
        row.setHeightInPoints(24);

        int col = 0;
        putCell(row, col++, String.valueOf(stt), even ? s.numEven : s.numOdd);
        putCell(row, col++, empRow.user.getFullName() != null ? empRow.user.getFullName() : empRow.user.getUsername(), txtStyle);
        putCell(row, col++, empRow.roleLabel != null ? empRow.roleLabel : "", txtStyle);

        if (isSecurity) {
            // ── BẢO VỆ XƯỞNG — thuê ngoài ──────────────────────────────────
            int mergeStart = col;
            if (includeSalary) col += 4 + allowanceLabels.length;
            if (includeBonus)  col += 1;
            int mergeEnd = col - 1;

            if (mergeEnd > mergeStart) {
                sheet.addMergedRegion(new CellRangeAddress(r, r, mergeStart, mergeEnd));
            }
            putCell(row, mergeStart, "Thuê ngoài", even ? s.outsourceEven : s.outsourceOdd);
            setMoneyCell(row, col, FACTORY_SECURITY_NET, netStyle);
        } else {
            // ── Nhân viên bình thường / Tài xế ──────────────────────────────
            //
            // Tích luỹ để tính "Lương thực nhận" = TỔNG các ô CASH đã hiển thị:
            //   attendanceSalary + Σ allowances hiển thị + bonus (nếu có cột)
            //
            // Trước đây dùng bd.getNetSalary() làm cột cuối. Với tài xế, driver-
            // SalaryDetail đẩy tiền XĂNG + THƯỞNG ĐƠN HÀNG vào "bonus" khi gọi
            // previewSalary → chúng đi vào netSalary. Nhưng file lương này KHÔNG
            // hiện xăng/thưởng đơn hàng ra cột riêng (xem file thưởng KPI xưởng
            // để lấy 2 khoản đó) → cột cuối lệch với tổng các ô nhìn thấy,
            // OWNER cộng nhẩm không ra. Bùi Kim Bảng: 6,219k + 720k = 6,939k
            // nhưng ô cuối cùng ghi 11,250k vì +4,3M xăng+thưởng ẩn.
            //
            // Đổi cách tính: cộng đúng các ô đang nhìn thấy → self-consistent
            // với mọi bộ phận. Các cột "Lương đóng BH" và "Tổng BH" không phải
            // tiền thực nhận nên KHÔNG cộng vào.
            long netReceived = 0L;

            if (includeSalary) {
                long baseSalaryFull = nz(bd.getStandardBaseSalary() != null ? bd.getStandardBaseSalary() : bd.getBaseSalary());
                setMoneyCell(row, col++, baseSalaryFull, moneyStyle);

                // PHASE 5: tài xế không còn tính riêng theo odo. Lương theo chấm
                // công = bd.getBaseSalary() (đã prorate trong hrService dựa trên
                // AttendanceEntry chung của toàn công ty) — giống mọi bộ phận.
                long attendanceSalary = nz(bd.getBaseSalary());
                setMoneyCell(row, col++, attendanceSalary, moneyStyle);
                netReceived += attendanceSalary;   // ← cộng vào thực nhận

                setMoneyCell(row, col++, nz(bd.getInsuranceSalary()), moneyStyle);

                long totalInsurance = nz(bd.getEmployeeInsuranceTotal()) + nz(bd.getEmployerInsuranceTotal());
                setMoneyCell(row, col++, totalInsurance, moneyStyle);

                Map<String, Long> allowanceMap = buildAllowanceMap(bd.getAllowances());

                // ── OVERRIDE phụ cấp cơm nếu có hardcode ngày công ───────────
                //   Breakdown đã tính cơm theo attendance → với các case
                //   override (quên quẹt thẻ…) cột cơm sẽ ra sai. Đổi lại theo
                //   mealDays của override, 30k/ngày. Chỉ chỉnh cột cơm; các
                //   phụ cấp khác giữ nguyên vì không phụ thuộc số công.
                com.nhatnam.server.utils.ManualAttendanceOverrides.Override mealOv =
                        com.nhatnam.server.utils.ManualAttendanceOverrides.lookup(
                                empRow.user.getFullName(), empRow.year, empRow.month);
                if (mealOv != null) {
                    allowanceMap.put("Phụ cấp cơm trưa",
                            30_000L * mealOv.mealDays());
                }

                // Phụ cấp cơm trưa của tài xế đã được tính trong driverSalaryDetail
                // → KHÔNG cộng thêm ở đây (tránh gấp đôi)

                for (String label : allowanceLabels) {
                    long val = allowanceMap.getOrDefault(label, 0L);
                    // Tài xế / Kho giao nhận: xăng xe tính riêng trong file thưởng → ghi 0
                    if (empRow.isDriver && DRIVER_ZERO_ALLOWANCES.contains(label)) val = 0;
                    setMoneyCell(row, col++, val, moneyStyle);
                    netReceived += val;            // ← cộng phụ cấp hiển thị
                }
            }

            if (includeBonus) {
                long bonus = effectiveBonusTotal(bd);
                setMoneyCell(row, col++, bonus, moneyStyle);
                netReceived += bonus;              // ← cộng thưởng khi có cột
            }

            // ── LƯƠNG THỰC NHẬN = TỔNG CÁC Ô ĐÃ HIỂN THỊ, LÀM TRÒN LÊN 1000 ─
            //   Số thực nhận gọn, dễ chuyển khoản. Các cột con giữ số chính xác
            //   nên tổng sẽ hơi lệch số cuối — chủ ý, vì cột cuối là số chi.
            long netReceivedRounded = com.nhatnam.server.utils.ManualAttendanceOverrides
                    .roundUpToThousand(netReceived);
            setMoneyCell(row, col, netReceivedRounded, netStyle);
        }
        r++;

        // ── Dòng phụ: công thực tế / công chuẩn ────────────────────────────
        // Bảo vệ xưởng (thuê ngoài) → KHÔNG hiển thị ngày công
        if (!isSecurity) {
            Row subRow = sheet.createRow(r);
            subRow.setHeightInPoints(16);

            CellStyle bgStyle = even ? s.dataEven : s.dataOdd;
            CellStyle subLine = even ? s.workdaySubEven : s.workdaySubOdd;

            putCell(subRow, 0, "", bgStyle);

            // ── HIỂN THỊ NGÀY CÔNG ──────────────────────────────────────────
            // Tài xế: hiển thị đơn giản "↳ Công thực tế X / Z"
            // Nhân viên sản xuất: hiển thị "↳ Công thực tế X / Z (đi làm Y ngày)"
            String workdayText;
            String actualWorkdayStr = fmtWorkday(empRow.actualWorkdays);
            String standardWorkdayStr = fmtWorkday(empRow.standardWorkdays);

            if (empRow.isDriver) {
                workdayText = "  ↳ Công thực tế " + actualWorkdayStr + " / " + standardWorkdayStr;
            } else {
                int actualDaysInt = empRow.actualAttendanceDays;
                workdayText = "  ↳ Công thực tế " + actualWorkdayStr + " / " + standardWorkdayStr + " (đi làm " + actualDaysInt + " ngày)";
            }

            putCell(subRow, 1, workdayText, subLine);
            for (int c = 2; c < totalCols; c++) {
                putCell(subRow, c, "", bgStyle);
            }
            r++;
        }

        return r;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 6 — SUBTOTAL TỪNG PHÒNG BAN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Dòng tổng cho MỘT phòng ban — in ngay sau khối nhân viên của phòng.
     * Cộng cùng 7 cột như {@link #writeGrandTotalRow} nhưng chỉ cho rows của
     * phòng này. Style nhạt hơn (nền xanh nhạt) để phân biệt với dòng tổng
     * toàn công ty ở cuối file.
     */
    private int writeDepartmentSubtotalRow(XSSFSheet sheet, ExportStyles s, int r,
                                           String deptCode, List<EmployeeRow> rows,
                                           String[] allowanceLabels, boolean includeBonus) {
        long tAttendance = 0, tInsurance32 = 0;
        long tMeal = 0, tPhone = 0, tOt = 0, tResp = 0;
        long tNet = 0;

        for (EmployeeRow er : rows) {
            if (er.factorySecurity) { tNet += FACTORY_SECURITY_NET; continue; }
            if (FactoryKpiService.isMaternityLeave(er.user)) continue;
            SalaryBreakdownDto bd = er.breakdown;
            if (bd == null) continue;

            long attendance = nz(bd.getBaseSalary());
            tAttendance += attendance;
            tInsurance32 += nz(bd.getEmployeeInsuranceTotal()) + nz(bd.getEmployerInsuranceTotal());

            Map<String, Long> allowanceMap = buildAllowanceMap(bd.getAllowances());
            com.nhatnam.server.utils.ManualAttendanceOverrides.Override mealOv =
                    com.nhatnam.server.utils.ManualAttendanceOverrides.lookup(
                            er.user.getFullName(), er.year, er.month);
            if (mealOv != null) {
                allowanceMap.put("Phụ cấp cơm trưa", 30_000L * mealOv.mealDays());
            }
            tMeal += allowanceMap.getOrDefault("Phụ cấp cơm trưa", 0L);
            tPhone += allowanceMap.getOrDefault("Phụ cấp điện thoại", 0L);
            tOt += allowanceMap.getOrDefault("Phụ cấp OT", 0L);
            tResp += allowanceMap.getOrDefault("Phụ cấp trách nhiệm", 0L);

            long netReceived = attendance;
            for (String label : allowanceLabels) {
                long val = allowanceMap.getOrDefault(label, 0L);
                if (er.isDriver && DRIVER_ZERO_ALLOWANCES.contains(label)) val = 0;
                netReceived += val;
            }
            if (includeBonus) netReceived += effectiveBonusTotal(bd);
            tNet += com.nhatnam.server.utils.ManualAttendanceOverrides.roundUpToThousand(netReceived);
        }

        Row row = sheet.createRow(r);
        row.setHeightInPoints(24);

        CellStyle lblStyle   = s.subtotalLabel != null ? s.subtotalLabel : s.headerCell;
        CellStyle moneyStyle = s.subtotalMoney != null ? s.subtotalMoney : s.headerNetCell;

        String deptLabel = PayrollDepartment.parse(deptCode) != null
                ? PayrollDepartment.parse(deptCode).getLabel()
                : deptCode;

        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 2));
        putCell(row, 0, "Cộng " + deptLabel, lblStyle);
        putCell(row, 1, "", lblStyle);
        putCell(row, 2, "", lblStyle);
        putCell(row, 3, "", lblStyle);                     // cột 3: Lương CB — bỏ trống
        setMoneyCell(row, 4, tAttendance, moneyStyle);     // Lương theo chấm công
        putCell(row, 5, "", lblStyle);                     // Lương đóng BH — bỏ trống
        setMoneyCell(row, 6, tInsurance32, moneyStyle);    // Tổng BH 32%

        int col = 7;
        for (String label : allowanceLabels) {
            long v = switch (label) {
                case "Phụ cấp cơm trưa"    -> tMeal;
                case "Phụ cấp điện thoại"  -> tPhone;
                case "Phụ cấp OT"          -> tOt;
                case "Phụ cấp trách nhiệm" -> tResp;
                default -> -1L;
            };
            if (v >= 0) setMoneyCell(row, col, v, moneyStyle);
            else putCell(row, col, "", lblStyle);
            col++;
        }
        if (includeBonus) putCell(row, col++, "", lblStyle);
        setMoneyCell(row, col, tNet, moneyStyle);
        return r + 1;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 4 — DÒNG TỔNG CẢ CÔNG TY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Dòng cuối file: tổng của toàn bộ nhân viên trong tháng cho 7 cột:
     * Lương theo chấm công · Tổng BH (32%) · Phụ cấp cơm · Phụ cấp điện thoại ·
     * Phụ cấp OT · Phụ cấp trách nhiệm · Lương thực nhận.
     *
     * <p>Các cột khác được để trống (STT, Họ tên, Chức vụ, Lương CB, Lương BH,
     * các phụ cấp khác, Thưởng). Dòng có style nền vàng đậm phân biệt với dòng
     * nhân viên bình thường.
     *
     * <p>Logic cộng tiền KHỚP CHÍNH XÁC với {@code writeEmployeeRow} để tổng
     * cột cuối cùng = tổng của các ô "Lương thực nhận" đã in — OWNER cộng
     * nhẩm cũng ra đúng con số này.
     */
    private int writeGrandTotalRow(XSSFSheet sheet, ExportStyles s, int r,
                                   Map<String, List<EmployeeRow>> deptData,
                                   String[] allowanceLabels, boolean includeBonus) {
        long tAttendance = 0, tInsurance32 = 0;
        long tMeal = 0, tPhone = 0, tOt = 0, tResp = 0;
        long tNet = 0;

        for (List<EmployeeRow> rows : deptData.values()) {
            for (EmployeeRow er : rows) {
                if (er.factorySecurity) {
                    tNet += FACTORY_SECURITY_NET;
                    continue;
                }
                if (FactoryKpiService.isMaternityLeave(er.user)) {
                    // Thai sản → ô net = 0, không cộng gì khác
                    continue;
                }

                SalaryBreakdownDto bd = er.breakdown;
                if (bd == null) continue;

                // Phase 5: tài xế dùng chung công thức với các bộ phận khác.
                long attendance = nz(bd.getBaseSalary());
                tAttendance += attendance;

                tInsurance32 += nz(bd.getEmployeeInsuranceTotal()) + nz(bd.getEmployerInsuranceTotal());

                Map<String, Long> allowanceMap = buildAllowanceMap(bd.getAllowances());

                // Áp override cơm (giống writeEmployeeRow)
                com.nhatnam.server.utils.ManualAttendanceOverrides.Override mealOv =
                        com.nhatnam.server.utils.ManualAttendanceOverrides.lookup(
                                er.user.getFullName(), er.year, er.month);
                if (mealOv != null) {
                    allowanceMap.put("Phụ cấp cơm trưa", 30_000L * mealOv.mealDays());
                }

                tMeal += allowanceMap.getOrDefault("Phụ cấp cơm trưa", 0L);
                tPhone += allowanceMap.getOrDefault("Phụ cấp điện thoại", 0L);
                tOt += allowanceMap.getOrDefault("Phụ cấp OT", 0L);
                tResp += allowanceMap.getOrDefault("Phụ cấp trách nhiệm", 0L);

                // Tính net theo đúng công thức writeEmployeeRow
                long netReceived = attendance;
                for (String label : allowanceLabels) {
                    long val = allowanceMap.getOrDefault(label, 0L);
                    if (er.isDriver && DRIVER_ZERO_ALLOWANCES.contains(label)) val = 0;
                    netReceived += val;
                }
                if (includeBonus) netReceived += effectiveBonusTotal(bd);
                tNet += com.nhatnam.server.utils.ManualAttendanceOverrides.roundUpToThousand(netReceived);
            }
        }

        // ── Dòng tổng — style nổi bật ────────────────────────────────────
        Row row = sheet.createRow(r);
        row.setHeightInPoints(28);

        CellStyle totalLabel = s.totalLabel != null ? s.totalLabel : s.headerCell;
        CellStyle totalMoney = s.totalMoney != null ? s.totalMoney : s.headerNetCell;
        CellStyle totalNet   = s.totalNet   != null ? s.totalNet   : s.headerNetCell;

        // Cột 0-2: nhãn "TỔNG CẢ CÔNG TY" merge
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 2));
        putCell(row, 0, "TỔNG CẢ CÔNG TY", totalLabel);
        putCell(row, 1, "", totalLabel);
        putCell(row, 2, "", totalLabel);

        // Cột 3: Lương CB — để trống (không tổng cột này theo yêu cầu)
        putCell(row, 3, "", totalLabel);

        // Cột 4: Lương theo chấm công
        setMoneyCell(row, 4, tAttendance, totalMoney);

        // Cột 5: Lương đóng BH — để trống
        putCell(row, 5, "", totalLabel);

        // Cột 6: Tổng BH 32%
        setMoneyCell(row, 6, tInsurance32, totalMoney);

        // Cột 7..7+n-1: các phụ cấp — fill 4 cột cần tổng, các cột khác để trống
        int col = 7;
        for (String label : allowanceLabels) {
            long v = switch (label) {
                case "Phụ cấp cơm trưa"    -> tMeal;
                case "Phụ cấp điện thoại"  -> tPhone;
                case "Phụ cấp OT"          -> tOt;
                case "Phụ cấp trách nhiệm" -> tResp;
                default -> -1L;
            };
            if (v >= 0) setMoneyCell(row, col, v, totalMoney);
            else putCell(row, col, "", totalLabel);
            col++;
        }

        // Cột thưởng — nếu có, để trống
        if (includeBonus) {
            putCell(row, col++, "", totalLabel);
        }

        // Cột cuối: Lương thực nhận tổng
        setMoneyCell(row, col, tNet, totalNet);

        return r + 1;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private long effectiveBonusTotal(SalaryBreakdownDto bd) {
        long total = 0;
        if (bd.getEffectiveBonusKpiOnly() != null) total += bd.getEffectiveBonusKpiOnly();
        if (bd.getBonusItemsTotal() != null) total += bd.getBonusItemsTotal();
        if (total == 0 && bd.getEffectiveBonus() != null) total = bd.getEffectiveBonus();
        return total;
    }

    private static long nz(Long v) { return v != null ? v : 0L; }

    private String fmtWorkday(double v) {
        if (v == Math.floor(v)) return String.valueOf((int) v);
        return String.format("%.2f", v);
    }

    private Map<String, Long> buildAllowanceMap(List<AllowanceItemDto> items) {
        Map<String, Long> map = new LinkedHashMap<>();
        if (items == null) return map;
        for (AllowanceItemDto item : items) {
            if (item.getLabel() != null) {
                // [Task #3, 2026] Bỏ phụ cấp auto-generated từ hỗ trợ giao hàng.
                // Nếu không lọc, "Phụ cấp xăng xe (150 km × 3.000đ)" sau khi
                // stripDetail sẽ bị gom vào cột "Phụ cấp xăng xe" → nhân viên
                // xưởng có Driver entity sẽ hiển thị số km × giá dù họ KHÔNG
                // được cấp phụ cấp xăng xe trong hồ sơ lương.
                if (isAutoGeneratedDeliveryAllowance(item.getLabel())) continue;
                // Gom theo nhãn gốc (bỏ chi tiết trong ngoặc)
                String baseLabel = stripDetail(item.getLabel());
                map.merge(baseLabel, item.getAmount() != null ? item.getAmount() : 0L, Long::sum);
            }
        }
        return map;
    }

    /**
     * Cắt bỏ phần chi tiết trong ngoặc của nhãn phụ cấp.
     * <p>VD: "Phụ cấp xăng xe (176 km × 3.000đ)" → "Phụ cấp xăng xe"
     * <p>VD: "Phụ cấp trách nhiệm" → "Phụ cấp trách nhiệm" (giữ nguyên)
     */
    private static String stripDetail(String label) {
        if (label == null) return null;
        int idx = label.indexOf(" (");
        return idx > 0 ? label.substring(0, idx).trim() : label.trim();
    }

    private int calcTotalColumns(boolean includeSalary, boolean includeBonus, String[] allowanceLabels) {
        int cols = 3; // STT, Họ tên, Chức vụ
        if (includeSalary) cols += 4 + allowanceLabels.length;
        if (includeBonus) cols += 1;
        cols += 1; // lương thực nhận
        return cols;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DATA CLASS
    // ══════════════════════════════════════════════════════════════════════════

    private static class EmployeeRow {
        User user;
        SalaryBreakdownDto breakdown;
        Role payrollRole;
        String roleLabel;
        String deptCode;
        double standardWorkdays;
        int actualAttendanceDays;
        double actualWorkdays;
        boolean factorySecurity; // Bảo vệ xưởng — thuê ngoài
        boolean isDriver;       // Tài xế — lương theo ngày chạy
        // Dùng ở writeEmployeeRow để tra ManualAttendanceOverrides cho cột cơm.
        int month;
        int year;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CELL HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private static void putCell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        if (style != null) c.setCellStyle(style);
    }

    private static void setMoneyCell(Row row, int col, long value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STYLES
    // ══════════════════════════════════════════════════════════════════════════

    private static class ExportStyles {
        final XSSFCellStyle companyName, title, subTitle;
        final XSSFCellStyle headerCell, headerNetCell, deptHeader;
        final XSSFCellStyle dataEven, dataOdd, numEven, numOdd, moneyEven, moneyOdd, netEven, netOdd;
        final XSSFCellStyle workdaySubEven, workdaySubOdd;
        final XSSFCellStyle outsourceEven, outsourceOdd;

        // ── Info header 2 dòng cho file thưởng xưởng ──
        final XSSFCellStyle normalInfoStyle, carryInStyle, carryOutStyle;
        final XSSFCellStyle bonusRateStyle, bonusPoolStyle;

        // ── Dòng nhân viên KHÔNG có bonus — in nghiêng ──
        final XSSFCellStyle italicDataEven, italicDataOdd;
        final XSSFCellStyle italicNumEven, italicNumOdd;
        final XSSFCellStyle italicMoneyEven, italicMoneyOdd;
        final XSSFCellStyle italicMergedEven, italicMergedOdd;

        /** Phase 4 — dòng tổng cuối file. 3 variant: nhãn / tiền thường / tiền thực nhận. */
        final XSSFCellStyle totalLabel, totalMoney, totalNet;

        /** Phase 6 — dòng subtotal mỗi phòng ban. Nhạt hơn totalRow để phân biệt. */
        final XSSFCellStyle subtotalLabel, subtotalMoney;

        private static final String C_NAVY   = "1A3C6E";
        private static final String C_BLUE   = "2E75B6";
        private static final String C_ACCENT = "D6E4F0";
        private static final String C_WHITE  = "FFFFFF";
        private static final String C_DEPT   = "E8F0FE";
        private static final String FMT      = "#,##0";

        ExportStyles(XSSFWorkbook wb) {
            DataFormat fmt = wb.createDataFormat();

            companyName = wb.createCellStyle();
            companyName.setFont(fnt(wb, 14, true, false, C_NAVY));
            companyName.setAlignment(HorizontalAlignment.CENTER);
            companyName.setVerticalAlignment(VerticalAlignment.CENTER);

            title = wb.createCellStyle();
            title.setFont(fnt(wb, 18, true, false, C_NAVY));
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            subTitle = wb.createCellStyle();
            subTitle.setFont(fnt(wb, 11, false, false, "5C5C5C"));
            subTitle.setAlignment(HorizontalAlignment.CENTER);
            subTitle.setVerticalAlignment(VerticalAlignment.CENTER);

            headerCell = wb.createCellStyle();
            headerCell.setFont(fnt(wb, 11, true, false, C_WHITE));
            headerCell.setFillForegroundColor(xc(C_NAVY));
            headerCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerCell.setAlignment(HorizontalAlignment.CENTER);
            headerCell.setVerticalAlignment(VerticalAlignment.CENTER);
            headerCell.setWrapText(true);
            border(headerCell, BorderStyle.MEDIUM, C_BLUE);

            headerNetCell = wb.createCellStyle();
            headerNetCell.setFont(fnt(wb, 11, true, false, C_WHITE));
            headerNetCell.setFillForegroundColor(xc("0D47A1"));
            headerNetCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerNetCell.setAlignment(HorizontalAlignment.CENTER);
            headerNetCell.setVerticalAlignment(VerticalAlignment.CENTER);
            headerNetCell.setWrapText(true);
            border(headerNetCell, BorderStyle.MEDIUM, C_BLUE);

            deptHeader = wb.createCellStyle();
            deptHeader.setFont(fnt(wb, 12, true, false, C_NAVY));
            deptHeader.setFillForegroundColor(xc(C_DEPT));
            deptHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            deptHeader.setAlignment(HorizontalAlignment.LEFT);
            deptHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            deptHeader.setIndention((short) 1);
            border(deptHeader, BorderStyle.THIN, "BDBDBD");

            XSSFFont fData = fnt(wb, 11, false, false, "1C1C1E");
            XSSFFont fNum  = fnt(wb, 11, true,  false, C_NAVY);
            XSSFFont fNet  = fnt(wb, 11, true,  false, C_NAVY);

            dataEven  = ds(wb, fData, C_ACCENT, HorizontalAlignment.LEFT,  null);
            dataOdd   = ds(wb, fData, C_WHITE,  HorizontalAlignment.LEFT,  null);
            numEven   = ds(wb, fNum,  C_ACCENT, HorizontalAlignment.CENTER, null);
            numOdd    = ds(wb, fNum,  C_WHITE,  HorizontalAlignment.CENTER, null);
            moneyEven = ds(wb, fData, C_ACCENT, HorizontalAlignment.RIGHT, fmt.getFormat(FMT));
            moneyOdd  = ds(wb, fData, C_WHITE,  HorizontalAlignment.RIGHT, fmt.getFormat(FMT));
            netEven   = ds(wb, fNet,  C_ACCENT, HorizontalAlignment.RIGHT, fmt.getFormat(FMT));
            netOdd    = ds(wb, fNet,  C_WHITE,  HorizontalAlignment.RIGHT, fmt.getFormat(FMT));

            // ── Dòng phụ "↳ Công thực tế X / Y" — nhỏ, in nghiêng, BG theo dòng ──
            XSSFFont fSub = fnt(wb, 9, false, true, "666666");
            workdaySubEven = wb.createCellStyle();
            workdaySubEven.setFont(fSub);
            workdaySubEven.setAlignment(HorizontalAlignment.LEFT);
            workdaySubEven.setVerticalAlignment(VerticalAlignment.CENTER);
            workdaySubEven.setIndention((short) 1);
            workdaySubEven.setFillForegroundColor(xc(C_ACCENT));
            workdaySubEven.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            workdaySubOdd = wb.createCellStyle();
            workdaySubOdd.setFont(fSub);
            workdaySubOdd.setAlignment(HorizontalAlignment.LEFT);
            workdaySubOdd.setVerticalAlignment(VerticalAlignment.CENTER);
            workdaySubOdd.setIndention((short) 1);
            workdaySubOdd.setFillForegroundColor(xc(C_WHITE));
            workdaySubOdd.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // ── "Thuê ngoài" — in đậm + in nghiêng, BG theo dòng ──
            XSSFFont fOut = fnt(wb, 11, true, true, "5C5C5C");
            outsourceEven = ds(wb, fOut, C_ACCENT, HorizontalAlignment.CENTER, null);
            outsourceOdd  = ds(wb, fOut, C_WHITE,  HorizontalAlignment.CENTER, null);

            // ══════════════════════════════════════════════════════════════
            // INFO HEADER 2 DÒNG CHO FILE THƯỞNG XƯỞNG
            // ══════════════════════════════════════════════════════════════

            // Dòng 1 trung tính (giữ lại phòng khi cần dùng)
            normalInfoStyle = wb.createCellStyle();
            normalInfoStyle.setFont(fnt(wb, 11, false, false, "5C5C5C"));
            normalInfoStyle.setAlignment(HorizontalAlignment.CENTER);
            normalInfoStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // Dòng 2 trái: Quỹ dư tháng trước — xanh lá
            carryInStyle = wb.createCellStyle();
            carryInStyle.setFont(fnt(wb, 11, true, false, "1B7F3A"));
            carryInStyle.setFillForegroundColor(xc("D9F2E0"));
            carryInStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            carryInStyle.setAlignment(HorizontalAlignment.CENTER);
            carryInStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            border(carryInStyle, BorderStyle.THIN, "1B7F3A");

            // Dòng 2 phải: Quỹ dư sau chia tháng này — xanh dương
            carryOutStyle = wb.createCellStyle();
            carryOutStyle.setFont(fnt(wb, 11, true, false, "0D47A1"));
            carryOutStyle.setFillForegroundColor(xc("D6E4F0"));
            carryOutStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            carryOutStyle.setAlignment(HorizontalAlignment.CENTER);
            carryOutStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            border(carryOutStyle, BorderStyle.THIN, "0D47A1");

            // ── Ô "Đơn giá bonus" — nền vàng nhạt, chữ nâu đậm ──
            bonusRateStyle = wb.createCellStyle();
            bonusRateStyle.setFont(fnt(wb, 11, true, false, "8A5A00"));
            bonusRateStyle.setFillForegroundColor(xc("FFF3CD"));
            bonusRateStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            bonusRateStyle.setAlignment(HorizontalAlignment.CENTER);
            bonusRateStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            border(bonusRateStyle, BorderStyle.THIN, "E0A800");

            // ── Ô "Quỹ thưởng tháng" — nền xanh ngọc nhạt, chữ xanh đậm ──
            bonusPoolStyle = wb.createCellStyle();
            bonusPoolStyle.setFont(fnt(wb, 11, true, false, "0B6E4F"));
            bonusPoolStyle.setFillForegroundColor(xc("D1F2E5"));
            bonusPoolStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            bonusPoolStyle.setAlignment(HorizontalAlignment.CENTER);
            bonusPoolStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            border(bonusPoolStyle, BorderStyle.THIN, "0B6E4F");

            // ══════════════════════════════════════════════════════════════
            // DÒNG NHÂN VIÊN KHÔNG CÓ BONUS — IN NGHIÊNG
            // ══════════════════════════════════════════════════════════════
            XSSFFont fItalic     = fnt(wb, 11, false, true, "1C1C1E");
            XSSFFont fItalicNum  = fnt(wb, 11, true,  true, C_NAVY);
            XSSFFont fItalicGray = fnt(wb, 10, false, true, "8A8A8A");

            italicDataEven  = ds(wb, fItalic, C_ACCENT, HorizontalAlignment.LEFT, null);
            italicDataOdd   = ds(wb, fItalic, C_WHITE,  HorizontalAlignment.LEFT, null);
            italicNumEven   = ds(wb, fItalicNum, C_ACCENT, HorizontalAlignment.CENTER, null);
            italicNumOdd    = ds(wb, fItalicNum, C_WHITE,  HorizontalAlignment.CENTER, null);
            italicMoneyEven = ds(wb, fItalic, C_ACCENT, HorizontalAlignment.RIGHT, fmt.getFormat(FMT));
            italicMoneyOdd  = ds(wb, fItalic, C_WHITE,  HorizontalAlignment.RIGHT, fmt.getFormat(FMT));

            italicMergedEven = wb.createCellStyle();
            italicMergedEven.setFont(fItalicGray);
            italicMergedEven.setFillForegroundColor(xc(C_ACCENT));
            italicMergedEven.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            italicMergedEven.setAlignment(HorizontalAlignment.CENTER);
            italicMergedEven.setVerticalAlignment(VerticalAlignment.CENTER);
            border(italicMergedEven, BorderStyle.THIN, "BDBDBD");

            italicMergedOdd = wb.createCellStyle();
            italicMergedOdd.cloneStyleFrom(italicMergedEven);
            italicMergedOdd.setFillForegroundColor(xc(C_WHITE));

            // ══════════════════════════════════════════════════════════════
            // PHASE 4 — DÒNG TỔNG CUỐI FILE (nền vàng đậm, in đậm)
            // ══════════════════════════════════════════════════════════════
            String C_TOTAL_BG   = "FFF2CC";    // vàng nhạt
            String C_TOTAL_FG   = "7F6000";    // vàng đậm
            String C_TOTAL_NET  = "FFD966";    // vàng đậm hơn cho cột thực nhận
            XSSFFont fTotalLbl   = fnt(wb, 12, true, false, C_TOTAL_FG);
            XSSFFont fTotalMoney = fnt(wb, 12, true, false, C_NAVY);

            totalLabel = wb.createCellStyle();
            totalLabel.setFont(fTotalLbl);
            totalLabel.setFillForegroundColor(xc(C_TOTAL_BG));
            totalLabel.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalLabel.setAlignment(HorizontalAlignment.CENTER);
            totalLabel.setVerticalAlignment(VerticalAlignment.CENTER);
            border(totalLabel, BorderStyle.MEDIUM, C_TOTAL_FG);

            totalMoney = wb.createCellStyle();
            totalMoney.setFont(fTotalMoney);
            totalMoney.setFillForegroundColor(xc(C_TOTAL_BG));
            totalMoney.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalMoney.setAlignment(HorizontalAlignment.RIGHT);
            totalMoney.setVerticalAlignment(VerticalAlignment.CENTER);
            totalMoney.setDataFormat(fmt.getFormat(FMT));
            border(totalMoney, BorderStyle.MEDIUM, C_TOTAL_FG);

            totalNet = wb.createCellStyle();
            totalNet.cloneStyleFrom(totalMoney);
            totalNet.setFillForegroundColor(xc(C_TOTAL_NET));

            // ── PHASE 6: Subtotal (nền xanh dương nhạt, viền mảnh) ──────────
            String C_SUB_BG = "DEEBF7";   // xanh dương nhạt
            String C_SUB_FG = "1F4E79";   // xanh dương đậm
            XSSFFont fSubLbl   = fnt(wb, 11, true, false, C_SUB_FG);
            XSSFFont fSubMoney = fnt(wb, 11, true, false, C_NAVY);

            subtotalLabel = wb.createCellStyle();
            subtotalLabel.setFont(fSubLbl);
            subtotalLabel.setFillForegroundColor(xc(C_SUB_BG));
            subtotalLabel.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            subtotalLabel.setAlignment(HorizontalAlignment.CENTER);
            subtotalLabel.setVerticalAlignment(VerticalAlignment.CENTER);
            border(subtotalLabel, BorderStyle.THIN, C_SUB_FG);

            subtotalMoney = wb.createCellStyle();
            subtotalMoney.setFont(fSubMoney);
            subtotalMoney.setFillForegroundColor(xc(C_SUB_BG));
            subtotalMoney.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            subtotalMoney.setAlignment(HorizontalAlignment.RIGHT);
            subtotalMoney.setVerticalAlignment(VerticalAlignment.CENTER);
            subtotalMoney.setDataFormat(fmt.getFormat(FMT));
            border(subtotalMoney, BorderStyle.THIN, C_SUB_FG);
        }

        private XSSFCellStyle ds(XSSFWorkbook wb, XSSFFont font, String bg,
                                 HorizontalAlignment a, Short df) {
            XSSFCellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setFillForegroundColor(xc(bg));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            st.setAlignment(a);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            if (df != null) st.setDataFormat(df);
            border(st, BorderStyle.THIN, "BDBDBD");
            return st;
        }

        private static void border(XSSFCellStyle st, BorderStyle bs, String h) {
            XSSFColor c = xc(h);
            st.setBorderTop(bs);    st.setTopBorderColor(c);
            st.setBorderBottom(bs); st.setBottomBorderColor(c);
            st.setBorderLeft(bs);   st.setLeftBorderColor(c);
            st.setBorderRight(bs);  st.setRightBorderColor(c);
        }

        private static XSSFFont fnt(XSSFWorkbook wb, int pt, boolean bold, boolean italic, String h) {
            XSSFFont f = wb.createFont();
            f.setFontName("Arial");
            f.setFontHeightInPoints((short) pt);
            f.setBold(bold);
            f.setItalic(italic);
            f.setColor(xc(h));
            return f;
        }

        private static XSSFColor xc(String h) {
            return new XSSFColor(new byte[]{
                    (byte) Integer.parseInt(h.substring(0, 2), 16),
                    (byte) Integer.parseInt(h.substring(2, 4), 16),
                    (byte) Integer.parseInt(h.substring(4, 6), 16)
            }, null);
        }
    }
}