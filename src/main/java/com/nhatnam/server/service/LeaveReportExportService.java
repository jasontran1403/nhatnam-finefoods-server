package com.nhatnam.server.service;

import com.nhatnam.server.entity.AttendanceEntry;
import com.nhatnam.server.entity.EmployeeRequest;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.AttendanceEntryRepository;
import com.nhatnam.server.repository.EmployeeRequestRepository;
import com.nhatnam.server.repository.ManualLeaveUsageRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.hr.PayrollDepartmentResolver;
import com.nhatnam.server.utils.LeaveBalanceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Export báo cáo ngày phép toàn công ty ra file Excel.
 *
 * <p>Layout:
 * <pre>
 *   Dòng 1: "Leave Report For {năm}" - căn giữa, merge hết các cột
 *   Dòng 2: "Ngày export: dd/MM/yyyy — Người export: {tên}" - căn giữa
 *   Dòng 3+: Bảng dữ liệu, nhóm theo phòng ban
 * </pre>
 *
 * <p>Cột: Tên | Vị trí | Ngày bắt đầu làm việc |
 *         T1 | T2 | ... | T12 | 2025 | 2026 |
 *         Tổng ngày phép đã nghỉ | Tổng ngày phép được cộng | Remaining
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveReportExportService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DD_MM_YYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final List<String> EXCLUDED_USERNAMES = Arrays.asList("nguyenhai", "q9", "baoveq9");

    /** Số phút chuẩn của 1 ngày phép — khớp với FactoryPayrollService.LEAVE_MINUTES_PER_DAY. */
    private static final int LEAVE_MINUTES_PER_DAY = 480;

    private final UserRepository userRepository;
    private final EmployeeRequestRepository requestRepo;
    private final PayrollDepartmentResolver deptResolver;
    private final AttendanceEntryRepository attendanceEntryRepo;
    private final ManualLeaveUsageRepository manualUsageRepo;

    /**
     * Ranh giới ô tháng "nhập tay" vs "tính từ phiếu" — phải trùng với
     * {@code LeaveManagementService.MANUAL_USAGE_MAX_MONTH} để export khớp
     * bảng UI. Bên UI đọc từ ManualLeaveUsage; export cũng phải làm y hệt.
     */
    private static final int MANUAL_USAGE_MAX_MONTH = 8;

    /** Thứ tự phòng ban khi xuất file — dùng lại từ SalaryExportService. */
    private static final List<String> DEPARTMENT_ORDER = List.of(
            "MANAGEMENT", "ACCOUNTING", "FACTORY", "SALES", "WAREHOUSE_AND_DRIVER"
    );

    private static final Map<String, String> DEPARTMENT_LABELS = Map.of(
            "MANAGEMENT",           "Quản lý cấp cao",
            "ACCOUNTING",           "Kế toán",
            "FACTORY",              "Xưởng sản xuất",
            "SALES",                "Kinh doanh",
            "WAREHOUSE_AND_DRIVER", "Kho và giao nhận"
    );

    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public byte[] export(String exporterName) throws Exception {
        int currentYear = LocalDate.now(VN).getYear();
        int priorYear   = currentYear - 1; // 2025

        // ── 1. Lấy nhân viên đang hoạt động (không khoá, không xoá mềm) ─────
        // Bổ sung filter !isDeleted() để khớp với endpoint JSON "Quản lý phép"
        // (LeaveManagementService.buildResponse) — 2 con đường phải hiện cùng
        // danh sách nhân viên, nếu không Owner sẽ đối chiếu ra lệch.
        List<User> activeUsers = userRepository.findAll().stream()
                .filter(u -> u.getRole() != null && !u.isLockAccount() && !u.isDeleted())
                .filter(u -> u.getUsername() != null
                        && !EXCLUDED_USERNAMES.contains(u.getUsername().toLowerCase()))
                .toList();

        // ── 2. Lấy toàn bộ phiếu nghỉ phép đã duyệt trong năm hiện tại ─────
        List<EmployeeRequest> allLeaves = requestRepo.findAllApprovedLeavesOfYear(currentYear);
        Map<Long, List<EmployeeRequest>> leavesByUser = allLeaves.stream()
                .collect(Collectors.groupingBy(r -> r.getUser().getId()));

        // ── 2b. Manual usage T1..T{MANUAL_USAGE_MAX_MONTH} — OWNER nhập tay ở
        // bảng "Quản lý phép" (bảng manual_leave_usage). Phải đọc ở đây thì
        // file Leave Report mới trùng với UI bảng phép; nếu không, cột T1..T8
        // trong file sẽ vẫn là "0 ngày" dù trên UI OWNER đã sửa.
        Map<Long, Map<Integer, Integer>> manualByUserMonth = manualUsageRepo
                .findAllForYear(currentYear).stream()
                .collect(Collectors.groupingBy(
                        m -> m.getUser().getId(),
                        Collectors.toMap(
                                com.nhatnam.server.entity.ManualLeaveUsage::getMonth,
                                com.nhatnam.server.entity.ManualLeaveUsage::getTotalMinutes,
                                (a, b) -> a)));

        // ── 3. Nhóm nhân viên theo phòng ban ─────────────────────────────────
        // Gom WAREHOUSE + DRIVER thành "WAREHOUSE_AND_DRIVER"
        Map<String, List<User>> grouped = new LinkedHashMap<>();
        for (String dept : DEPARTMENT_ORDER) grouped.put(dept, new ArrayList<>());

        for (User u : activeUsers) {
            PayrollDepartment pd = deptResolver.departmentOf(u);
            if (pd == null) continue;
            String key = (pd == PayrollDepartment.WAREHOUSE || pd == PayrollDepartment.DRIVER)
                    ? "WAREHOUSE_AND_DRIVER" : pd.name();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
        }

        // Sắp xếp trong mỗi phòng ban theo vị trí (role rank)
        for (var entry : grouped.entrySet()) {
            entry.getValue().sort(Comparator
                    .comparingInt((User u) -> roleRank(entry.getKey(), u.getRole()))
                    .thenComparing(u -> u.getFullName() != null ? u.getFullName() : ""));
        }

        // ── 4. Tạo workbook ──────────────────────────────────────────────────
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Leave Report " + currentYear);

            // Styles
            // Title style - to, đậm, màu xanh đậm
            XSSFCellStyle titleStyle = wb.createCellStyle();
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 18);
            titleFont.setColor(IndexedColors.DARK_BLUE.getIndex());
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // Subtitle style
            XSSFCellStyle subtitleStyle = wb.createCellStyle();
            XSSFFont subtitleFont = wb.createFont();
            subtitleFont.setFontHeightInPoints((short) 11);
            subtitleFont.setItalic(true);
            subtitleStyle.setFont(subtitleFont);
            subtitleStyle.setAlignment(HorizontalAlignment.CENTER);
            subtitleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // Department header style - nổi bật với màu nền và viền
            XSSFCellStyle deptStyle = wb.createCellStyle();
            XSSFFont deptFont = wb.createFont();
            deptFont.setBold(true);
            deptFont.setFontHeightInPoints((short) 14);
            deptFont.setColor(IndexedColors.WHITE.getIndex());
            deptStyle.setFont(deptFont);
            deptStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            deptStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            deptStyle.setAlignment(HorizontalAlignment.LEFT);
            deptStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            deptStyle.setBorderBottom(BorderStyle.MEDIUM);
            deptStyle.setBorderTop(BorderStyle.MEDIUM);
            deptStyle.setBorderLeft(BorderStyle.MEDIUM);
            deptStyle.setBorderRight(BorderStyle.MEDIUM);

            // Table header style - màu xám đậm, chữ trắng, căn giữa
            XSSFCellStyle headerStyle = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerFont.setFontHeightInPoints((short) 11);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setWrapText(true);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            // Data styles
            XSSFCellStyle dataStyle = wb.createCellStyle();
            dataStyle.setBorderBottom(BorderStyle.THIN);
            dataStyle.setBorderTop(BorderStyle.THIN);
            dataStyle.setBorderLeft(BorderStyle.THIN);
            dataStyle.setBorderRight(BorderStyle.THIN);
            dataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            dataStyle.setAlignment(HorizontalAlignment.LEFT);

            XSSFCellStyle numStyle = wb.createCellStyle();
            numStyle.cloneStyleFrom(dataStyle);
            numStyle.setAlignment(HorizontalAlignment.CENTER);
            numStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("0.0"));

            // Style dùng cho ô hiển thị "X ngày Y phút" — text, căn giữa
            XSSFCellStyle daysMinutesStyle = wb.createCellStyle();
            daysMinutesStyle.cloneStyleFrom(dataStyle);
            daysMinutesStyle.setAlignment(HorizontalAlignment.CENTER);

            XSSFCellStyle dateColStyle = wb.createCellStyle();
            dateColStyle.cloneStyleFrom(dataStyle);
            dateColStyle.setAlignment(HorizontalAlignment.CENTER);

            // ── Số cột: 20 cột (0..19) - đã bỏ cột Phòng ban ──────────────
            int totalColumns = 20; // 0-19

            // ── Dòng 1: Title - căn giữa, merge hết các cột ─────────────────
            int rowIdx = 0;
            Row titleRow = sheet.createRow(rowIdx++);
            titleRow.setHeightInPoints(35);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("LEAVE REPORT FOR " + currentYear);
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, totalColumns - 1));

            // ── Dòng 2: Subtitle - căn giữa, merge hết các cột ──────────────
            Row subtitleRow = sheet.createRow(rowIdx++);
            subtitleRow.setHeightInPoints(25);
            Cell subCell = subtitleRow.createCell(0);
            subCell.setCellValue("Ngày export: " + LocalDate.now(VN).format(DD_MM_YYYY)
                    + " — Người export: " + (exporterName != null ? exporterName : "N/A"));
            subCell.setCellStyle(subtitleStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, totalColumns - 1));

            rowIdx++; // dòng trống

            // ── Headers ───────────────────────────────────────────────────────
            // Cột: 0=Tên, 1=Vị trí, 2=Ngày bắt đầu,
            //       3..14=T1..T12, 15=2025, 16=2026,
            //       17=Tổng đã nghỉ, 18=Tổng được cộng, 19=Remaining
            String[] headers = {
                    "Tên", "Vị trí", "Ngày bắt đầu\nlàm việc",
                    "T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9", "T10", "T11", "T12",
                    String.valueOf(priorYear), String.valueOf(currentYear),
                    "Tổng ngày phép\nđã nghỉ", "Tổng ngày phép\nđược cộng", "Remaining"
            };

            // ── Render từng phòng ban ─────────────────────────────────────────
            for (String deptKey : DEPARTMENT_ORDER) {
                List<User> users = grouped.getOrDefault(deptKey, List.of());
                if (users.isEmpty()) continue;

                // Tên phòng ban - nổi bật với nền xanh đậm, chữ trắng, đậm
                Row deptRow = sheet.createRow(rowIdx++);
                deptRow.setHeightInPoints(32);
                // Merge từ cột 0 đến hết để tạo thanh ngang nổi bật
                sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, totalColumns - 1));

                Cell deptCell = deptRow.createCell(0);
                String deptLabel = DEPARTMENT_LABELS.getOrDefault(deptKey, deptKey);
                deptCell.setCellValue("▶ " + deptLabel + " (" + users.size() + " nhân viên)");
                deptCell.setCellStyle(deptStyle);

                // Header row - màu xám đậm
                Row hRow = sheet.createRow(rowIdx++);
                hRow.setHeightInPoints(35);
                for (int c = 0; c < headers.length; c++) {
                    Cell cell = hRow.createCell(c);
                    cell.setCellValue(headers[c]);
                    cell.setCellStyle(headerStyle);
                }

                // Data rows
                for (User u : users) {
                    Row row = sheet.createRow(rowIdx++);
                    row.setHeightInPoints(22);
                    int col = 0;

                    // Tên
                    Cell nameCell = row.createCell(col++);
                    nameCell.setCellValue(u.getFullName() != null ? u.getFullName() : u.getUsername());
                    nameCell.setCellStyle(dataStyle);

                    // Vị trí
                    Cell posCell = row.createCell(col++);
                    posCell.setCellValue(u.getPosition() != null ? u.getPosition()
                            : (u.getRole() != null ? u.getRole().name() : "-"));
                    posCell.setCellStyle(dataStyle);

                    // Ngày bắt đầu làm việc
                    Cell startCell = row.createCell(col++);
                    if (u.getWorkStartDate() != null) {
                        LocalDate wsd = Instant.ofEpochMilli(u.getWorkStartDate())
                                .atZone(VN).toLocalDate();
                        startCell.setCellValue(wsd.format(DD_MM_YYYY));
                    } else {
                        startCell.setCellValue("-");
                    }
                    startCell.setCellStyle(dateColStyle);

                    // T1..T12 — ngày phép đã DUYỆT nghỉ + ngày phép BÙ TRỪ trễ/sớm
                    //
                    //   ── Vì sao gộp cả 2 vào 1 cột "Đã nghỉ tháng M" ──
                    //   Phòng sản xuất áp dụng logic "trừ trễ/sớm vào ngày phép trước".
                    //   Mỗi lần import bảng chấm công, {@code AttendanceEntry.leaveMinutesUsed}
                    //   ghi lại số PHÚT PHÉP bị tiêu để bù trễ/sớm tháng đó. Số phút
                    //   này quy đổi ra ngày = phút / 480 và cộng vào cột tháng tương
                    //   ứng để bảng phép phản ánh đúng thực tế.
                    //
                    //   ── Khi mở lại (import file mới) ──
                    //   FactoryPayrollService.buildEntry() RESET leaveMinutesUsed về 0
                    //   rồi tính lại từ đầu (xem javadoc field leaveMinutesUsed) → số
                    //   phút bù trừ tự "cộng ngược vào phép" mà không cần thao tác gì
                    //   ở đây. Lần export tiếp theo sẽ đọc con số mới nhất.
                    // ── Tính TỔNG PHÚT PHÉP đã dùng trong năm để tổng cột "Đã nghỉ" ─────
                    // Nguyên tắc: LUÔN dùng đơn vị phút làm chân lý duy nhất; ngày chỉ là
                    // biểu diễn cho người đọc. Nhờ vậy 19 phút trễ không bị nuốt do rounding.
                    //
                    // T1..T{MANUAL_USAGE_MAX_MONTH}: đọc từ ManualLeaveUsage (OWNER nhập tay).
                    // T{MANUAL_USAGE_MAX_MONTH+1}..T12: tính tự động từ phiếu nghỉ + phút trễ/sớm.
                    List<EmployeeRequest> userLeaves = leavesByUser.getOrDefault(u.getId(), List.of());
                    Map<Integer, Integer> userManualByMonth =
                            manualByUserMonth.getOrDefault(u.getId(), Map.of());
                    long totalUsedMinutesInYear = 0;
                    for (int m = 1; m <= 12; m++) {
                        long monthMinutes;
                        if (m <= MANUAL_USAGE_MAX_MONTH) {
                            // Manual — 0 nếu OWNER chưa nhập cho tháng đó
                            monthMinutes = userManualByMonth.getOrDefault(m, 0);
                        } else {
                            double monthPaidDays = calcPaidLeaveDaysInMonth(userLeaves, m, currentYear);
                            int deductMinutes    = leaveMinutesUsedInMonth(u.getId(), m, currentYear);
                            monthMinutes = Math.round(monthPaidDays * LEAVE_MINUTES_PER_DAY)
                                    + deductMinutes;
                        }
                        totalUsedMinutesInYear += monthMinutes;

                        Cell mCell = row.createCell(col++);
                        mCell.setCellValue(formatDaysMinutes(monthMinutes));
                        mCell.setCellStyle(daysMinutesStyle);
                    }

                    // Cột năm trước (2025) — lấy từ priorYearLeaveBalance
                    double prior = u.getPriorYearLeaveBalance() != null
                            ? u.getPriorYearLeaveBalance() : 0.0;
                    Cell priorCell = row.createCell(col++);
                    priorCell.setCellValue(prior);
                    priorCell.setCellStyle(numStyle);

                    // Cột năm hiện tại (2026) — auto formula + offset OWNER nhập tay
                    // (giữ đúng công thức bảng UI: auto tự lên 1 mỗi tháng, offset đè).
                    double autoEntitled = LeaveBalanceCalculator
                            .entitledDaysFor(u.getWorkStartDate(), currentYear);
                    double entitledOffset = u.getEntitledOffsetDays() != null
                            ? u.getEntitledOffsetDays() : 0.0;
                    double currentEntitled = autoEntitled + entitledOffset;
                    Cell curCell = row.createCell(col++);
                    curCell.setCellValue(currentEntitled);
                    curCell.setCellStyle(numStyle);

                    // Tổng ngày phép đã nghỉ = ngày phép đã duyệt + ngày phép bù trễ/sớm
                    // Hiển thị "X ngày Y phút" thay vì làm tròn 2 chữ số như trước.
                    Cell usedCell = row.createCell(col++);
                    usedCell.setCellValue(formatDaysMinutes(totalUsedMinutesInYear));
                    usedCell.setCellStyle(daysMinutesStyle);

                    // Tổng ngày phép được cộng: thâm niên + OT/công tác/hỗ trợ
//                    double seniorityBonus = LeaveBalanceCalculator
//                            .seniorityBonusDays(u.getWorkStartDate(), currentYear);
                    double seniorityBonus = 0.0;
                    double otherBonus = u.getBonusLeaveDays() != null ? u.getBonusLeaveDays() : 0.0;
                    double totalPlus = seniorityBonus + otherBonus;
                    Cell bonusCell = row.createCell(col++);
                    bonusCell.setCellValue(totalPlus);
                    bonusCell.setCellStyle(numStyle);

                    // Remaining = prior + currentEntitled + totalPlus (đơn vị ngày, luôn là bội số của 0.5)
                    //           - totalUsedMinutesInYear (đơn vị phút, có thể lẻ vì bù trừ trễ/sớm)
                    // Quy hết về phút để không mất chính xác, rồi hiển thị "X ngày Y phút".
                    long entitledMinutes = Math.round((prior + currentEntitled + totalPlus) * LEAVE_MINUTES_PER_DAY);
                    long remainingMinutes = entitledMinutes - totalUsedMinutesInYear;
                    Cell remCell = row.createCell(col++);
                    remCell.setCellValue(formatDaysMinutes(remainingMinutes));
                    remCell.setCellStyle(daysMinutesStyle);
                }

                // 2 dòng trống giữa các phòng ban
                rowIdx += 2;
            }

            // Auto-size columns
            for (int c = 0; c < headers.length; c++) {
                sheet.autoSizeColumn(c);
                // Minimum width for month columns
                if (c >= 3 && c <= 14) {
                    int minWidth = 1800; // ~6 chars
                    if (sheet.getColumnWidth(c) < minWidth) sheet.setColumnWidth(c, minWidth);
                }
            }
            // Wider for name and position
            if (sheet.getColumnWidth(0) < 6000) sheet.setColumnWidth(0, 6000);
            if (sheet.getColumnWidth(1) < 4000) sheet.setColumnWidth(1, 4000);
            if (sheet.getColumnWidth(2) < 4500) sheet.setColumnWidth(2, 4500);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Tính số ngày phép CÓ LƯƠNG đã nghỉ trong 1 tháng cụ thể.
     *
     * <p>Với phiếu vắt qua nhiều tháng, chỉ đếm các ngày rơi vào tháng đang tính.
     * Dùng danh sách LeaveDay nếu có, fallback đếm ngày lịch.
     */
    private double calcPaidLeaveDaysInMonth(List<EmployeeRequest> leaves, int month, int year) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd   = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        double total = 0;

        for (EmployeeRequest r : leaves) {
            if (r.getFromDate() == null || r.getToDate() == null) continue;
            // Phiếu không chồng lấn tháng này → bỏ qua
            if (r.getToDate().isBefore(monthStart) || r.getFromDate().isAfter(monthEnd)) continue;

            double paidDays = r.getPaidLeaveDays() != null ? r.getPaidLeaveDays() : 0.0;
            if (paidDays <= 0) continue;

            if (r.getDays() != null && !r.getDays().isEmpty()) {
                // ── FIX: `days` chứa TẤT CẢ các buổi (paid + unpaid). Cộng
                //   dayValue nguyên vẹn của mọi buổi → trừ quỹ theo tổng ngày
                //   XIN chứ không phải phần được duyệt CÓ phép → quỹ về âm khi
                //   phiếu bị auto-split (còn 1 phép, xin 3 → split 1 paid + 2
                //   unpaid, nhưng vòng lặp cũ vẫn cộng 3).
                //
                //   Sửa: chia tỷ lệ paidDays theo dayValue của tháng / tổng
                //   dayValue toàn phiếu — khớp với LeaveManagementService.
                double totalDayValues = 0;
                double thisMonthDayValues = 0;
                for (EmployeeRequest.LeaveDay ld : r.getDays()) {
                    if (ld.getDate() == null) continue;
                    double dv = ld.dayValue();
                    totalDayValues += dv;
                    if (!ld.getDate().isBefore(monthStart) && !ld.getDate().isAfter(monthEnd)) {
                        thisMonthDayValues += dv;
                    }
                }
                if (totalDayValues > 0) {
                    total += paidDays * thisMonthDayValues / totalDayValues;
                }
            } else {
                // Fallback: chia tỷ lệ theo ngày lịch
                LocalDate effStart = r.getFromDate().isBefore(monthStart) ? monthStart : r.getFromDate();
                LocalDate effEnd   = r.getToDate().isAfter(monthEnd) ? monthEnd : r.getToDate();
                long daysInMonth = effStart.until(effEnd).getDays() + 1;
                long totalDays   = r.getFromDate().until(r.getToDate()).getDays() + 1;
                if (totalDays > 0) {
                    total += paidDays * daysInMonth / totalDays;
                }
            }
        }
        return round1(total);
    }

    /** Role rank cho sắp xếp trong phòng ban — giống SalaryExportService. */
    private static int roleRank(String department, Role role) {
        if (role == null) return 99;
        return switch (department) {
            case "MANAGEMENT" -> switch (role) {
                case OWNER -> 0; case ADMIN -> 1; default -> 99;
            };
            case "ACCOUNTING" -> switch (role) {
                case SUPER_ACCOUNTANT -> 0; case ACCOUNTANT -> 1; default -> 99;
            };
            case "FACTORY" -> switch (role) {
                case SUPER_FACTORY_WORKER -> 0; case FACTORY_MANAGER -> 1;
                case FACTORY_STAFF -> 2; case FACTORY_ACCOUNTANT -> 3;
                case FACTORY_PRODUCTION_WORKER -> 4; case FACTORY_WORKER -> 5;
                case FACTORY_SECURITY -> 6; default -> 99;
            };
            case "SALES" -> switch (role) {
                case SUPER_SELLER -> 0; case SELLER -> 1; default -> 99;
            };
            case "WAREHOUSE_AND_DRIVER" -> switch (role) {
                case SUPER_WAREHOUSE -> 0; case WAREHOUSE -> 1; case DRIVER -> 2; default -> 99;
            };
            default -> 99;
        };
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Quy đổi tổng số phút phép sang chuỗi "X ngày Y phút" (hoặc "X.5 ngày Y phút").
     *
     * <p>Bước quy đổi = 1 buổi = 240 phút. Số nửa-buổi lẻ → phần ".5", còn thừa
     * dưới 240 phút → giữ ở phần "Y phút". Giữ nguyên số phút để không thiệt
     * cho nhân viên vì rounding.
     *
     * <p>Ví dụ:
     * <ul>
     *   <li>2141 phút → 8 nửa-buổi dư 221 phút → "4 ngày 221 phút"</li>
     *   <li>1901 phút → 7 nửa-buổi dư 221 phút → "3.5 ngày 221 phút"</li>
     *   <li>1440 phút → 6 nửa-buổi dư 0     → "3 ngày"</li>
     *   <li>0 phút   → "0 ngày"</li>
     *   <li>-19 phút → "-19 phút" (số dư âm — xảy ra khi nhân viên đã dùng vượt quỹ)</li>
     * </ul>
     */
    static String formatDaysMinutes(long totalMinutes) {
        if (totalMinutes == 0) return "0 ngày";
        if (totalMinutes < 0) {
            // Dưới quỹ — chỉ hiện dấu âm, dùng lại cùng format cho phần dương.
            return "-" + formatDaysMinutes(-totalMinutes);
        }
        long halfDays   = totalMinutes / 240;   // số nửa-buổi (mỗi buổi = 240 phút)
        long remMinutes = totalMinutes % 240;

        // Số ngày nhỏ hơn 1 buổi → chỉ hiện phần phút ("19 phút") cho gọn.
        if (halfDays == 0) return remMinutes + " phút";

        String daysStr = (halfDays % 2 == 0)
                ? String.valueOf(halfDays / 2)
                : (halfDays / 2) + ".5";
        return remMinutes == 0
                ? daysStr + " ngày"
                : daysStr + " ngày " + remMinutes + " phút";
    }

    /**
     * Số PHÚT PHÉP đã tiêu để bù trễ/sớm cho user trong 1 tháng cụ thể.
     *
     * <p>Đọc từ {@link AttendanceEntry#getLeaveMinutesUsed()} của bảng chấm công
     * đang sinh sống trong DB. Vì {@code leaveMinutesUsed} được TÍNH LẠI TỪ ĐẦU
     * mỗi lần import file mới (xem javadoc field trên entity), số trả về ở đây
     * luôn phản ánh trạng thái mới nhất — không có "cộng dồn ngầm".
     *
     * <p>Chỉ áp dụng cho FACTORY / ACCOUNTING (bộ phận "trừ phép trước"). Với
     * các bộ phận khác, {@code leaveMinutesUsed} luôn = 0 theo logic buildEntry.
     *
     * @return số phút; 0 nếu không có bản ghi chấm công tháng đó.
     */
    private int leaveMinutesUsedInMonth(Long userId, int month, int year) {
        return attendanceEntryRepo
                .findByUserAndPeriod(userId, month, year)
                .map(e -> e.getLeaveMinutesUsed() != null ? e.getLeaveMinutesUsed() : 0)
                .orElse(0);
    }
}