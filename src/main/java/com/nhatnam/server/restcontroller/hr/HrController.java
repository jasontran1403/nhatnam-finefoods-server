package com.nhatnam.server.restcontroller.hr;

import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.hr.HrDtos.*;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.repository.EmployeeSalaryRepository;
import com.nhatnam.server.service.hr.HrService;
import com.nhatnam.server.service.hr.HrService.SalaryAdvanceInfoDto;
import org.springframework.security.access.prepost.PreAuthorize;
import com.nhatnam.server.utils.SeniorityCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.nhatnam.server.service.LeaveReportExportService;
import com.nhatnam.server.service.LeaveManagementService;
import com.nhatnam.server.dto.hr.LeaveManagementDtos.LeaveManagementResponse;
import com.nhatnam.server.dto.common.ApiResponse;


@RestController
@RequestMapping("/api/hr")
@RequiredArgsConstructor
@Slf4j
public class HrController {

    private final HrService hrService;
    private final UserRepository userRepository;
    private final EmployeeSalaryRepository salaryRepository;
    private final LeaveReportExportService leaveReportExportService;
    private final LeaveManagementService leaveManagementService;

    @Value("${application.security.jwt.secret-key}")
    private String jwtSecretKey;

    // ── Export token (same pattern as CustomerAdminController) ────────────────

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String TOKEN_PREFIX_HR_SALARY = "export-hr-salary:";
    private final ConcurrentHashMap<String, Long> usedExportTokens = new ConcurrentHashMap<>();

    private String _makeToken(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(jwtSecretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal((TOKEN_PREFIX_HR_SALARY + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) { throw new RuntimeException("Không thể tạo token"); }
    }

    /**
     * Dữ liệu bảng "Quản lý phép" (JSON) — cùng bộ số với XLSX export bên dưới.
     * Chỉ OWNER/ADMIN được xem (spec: nút "Quản lý phép" nằm ở page Người dùng của Owner).
     */
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    @GetMapping("/leave-management")
    public ApiResponse<LeaveManagementResponse> leaveManagement() {
        return ApiResponse.ok(leaveManagementService.buildResponse());
    }

    /**
     * Ghi 1 ô "Đã dùng T1..T8" của bảng "Quản lý phép" — OWNER click vào ô,
     * nhập X ngày + Y phút, blur/Enter thì FE gửi tổng số phút xuống đây.
     *
     * <p>Body: {@code { userId, year, month, minutes }}. minutes = 0 → xoá bản
     * ghi (ô hiển thị " - ").
     */
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    @PatchMapping("/leave-management/manual-usage")
    public ApiResponse<LeaveManagementResponse> updateManualUsage(
            @RequestBody ManualUsageUpdateRequest body,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "System";
        LeaveManagementResponse res = leaveManagementService.setManualUsage(
                body.userId(), body.year(), body.month(), body.minutes(), actor);
        return ApiResponse.ok(res);
    }

    /** Body cho PATCH /api/hr/leave-management/manual-usage. */
    public record ManualUsageUpdateRequest(
            Long userId,
            int year,
            int month,
            int minutes) {}

    /**
     * Ghi 1 ô cột "TỒN NĂM TRƯỚC" ({@code priorYearLeaveBalance}) của bảng
     * "Quản lý phép" — OWNER click sửa. Body: {@code { userId, days }}.
     * {@code days} nhận số thập phân (nửa buổi = 0.5; nhỏ hơn để bù phút).
     */
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    @PatchMapping("/leave-management/prior-year-balance")
    public ApiResponse<LeaveManagementResponse> updatePriorYearBalance(
            @RequestBody BalanceUpdateRequest body) {
        return ApiResponse.ok(leaveManagementService.setPriorYearBalance(
                body.userId(), body.days()));
    }

    /**
     * Ghi 1 ô cột "PHÉP NĂM HIỆN TẠI" của bảng "Quản lý phép" — OWNER click sửa.
     * BE lưu offset so với auto formula để mỗi 1 đầu tháng con số tự tăng 1
     * mà không cần cronjob. Body: {@code { userId, days }}.
     */
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    @PatchMapping("/leave-management/current-year-entitled")
    public ApiResponse<LeaveManagementResponse> updateCurrentYearEntitled(
            @RequestBody BalanceUpdateRequest body) {
        return ApiResponse.ok(leaveManagementService.setCurrentYearEntitled(
                body.userId(), body.days()));
    }

    /** Body chung cho 2 endpoint set số ngày phép trực tiếp trên bảng. */
    public record BalanceUpdateRequest(Long userId, double days) {}

    @GetMapping("/leave-report/export")
    public ResponseEntity<byte[]> exportLeaveReport(Authentication auth) throws Exception {
        String exporterName = auth != null ? auth.getName() : "System";

        // Lấy fullName nếu có
        try {
            User me = userRepository.findByUsername(exporterName).orElse(null);
            if (me != null && me.getFullName() != null && !me.getFullName().isBlank()) {
                exporterName = me.getFullName();
            }
        } catch (Exception ignored) {}

        byte[] data = leaveReportExportService.export(exporterName);

        String filename = "Leave_Report_%d.xlsx".formatted(java.time.LocalDate.now().getYear());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    private String _generateExportToken() {
        String ts = String.valueOf(System.currentTimeMillis());
        return ts + ":" + _makeToken(ts);
    }

    private void _validateAndConsumeToken(String token) {
        if (token == null || token.isBlank())
            throw new IllegalStateException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
        String[] parts = token.split(":", 2);
        if (parts.length != 2 || !_makeToken(parts[0]).equals(parts[1]))
            throw new IllegalStateException("File không hợp lệ: mã xác thực không khớp. Vui lòng Export lại file mới.");
        if (usedExportTokens.containsKey(token))
            throw new IllegalStateException("File này đã được import rồi. Vui lòng Export file mới để import lại.");
        usedExportTokens.put(token, System.currentTimeMillis());
    }

    // ── Danh mục Bộ phận / Chức vụ ────────────────────────────────────────────

    /**
     * DANH MỤC CỐ ĐỊNH cho 2 dropdown "Bộ phận" và "Chức vụ" ở trang Nhân sự.
     *
     * <p>FE hiển thị đúng danh sách này, KHÔNG cho nhập tay. Mỗi chức vụ đã gắn
     * sẵn role hưởng lương nên khi Kế toán trưởng chọn xong là biết ngay nhân
     * viên sẽ được tính lương ở bộ phận nào.
     *
     * <pre>
     * GET /api/hr/org-structure
     * [
     *   { "department": "Xưởng sản xuất", "payrollDepartment": "FACTORY",
     *     "positions": [ { "label": "Trưởng xưởng", "payrollRole": "SUPER_FACTORY_WORKER" }, ... ] },
     *   ...
     * ]
     * </pre>
     */
    @GetMapping("/org-structure")
    public ResponseEntity<List<Map<String, Object>>> orgStructure() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.nhatnam.server.enumtype.OrgCatalog.Department d
                : com.nhatnam.server.enumtype.OrgCatalog.departments()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("department", d.getLabel());
            dm.put("payrollDepartment", d.getPayrollDepartment().name());
            dm.put("payrollDepartmentLabel", d.getPayrollDepartment().getLabel());

            List<Map<String, Object>> ps = new ArrayList<>();
            for (com.nhatnam.server.enumtype.OrgCatalog.Position p : d.getPositions()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("label", p.getLabel());
                pm.put("payrollRole", p.getPayrollRole().name());
                ps.add(pm);
            }
            dm.put("positions", ps);
            out.add(dm);
        }
        return ResponseEntity.ok(out);
    }

    // ── Employee info ─────────────────────────────────────────────────────────

    @PutMapping("/employees/{userId}/info")
    public ResponseEntity<Void> updateEmployeeInfo(@PathVariable Long userId,
                                                   @RequestBody UpdateEmployeeInfoRequest req) {
        hrService.updateEmployeeInfo(userId, req);
        return ResponseEntity.ok().build();
    }

    // ── List all employees (for HR manage page) ───────────────────────────────

    @GetMapping("/employees")
    public ResponseEntity<List<Map<String, Object>>> listEmployees() {
        List<User> users = userRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (User u : users) {
            if (u.getRole() == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("fullName", u.getFullName());
            m.put("username", u.getUsername());
            m.put("role", u.getRole().name());
            m.put("department", u.getDepartment());
            m.put("division", u.getDivision());
            m.put("position", u.getPosition());
            // Ngày vào làm — modal "Bộ phận / Chức vụ" nạp lại giá trị đang có,
            // và bảng nhân sự hiển thị để nhìn ra ngay ai còn thiếu.
            m.put("workStartDate", u.getWorkStartDate());
            m.put("isLockAccount", u.isLockAccount());
            // Thông tin ngân hàng — để modal nạp lại và bảng hiển thị badge cho
            // nhân viên đã điền đủ (dùng cho file chi lương gửi ngân hàng).
            m.put("bankAccountNumber", u.getBankAccountNumber());
            m.put("bankName", u.getBankName());
            // Nghỉ thai sản — bảng nhân sự hiện badge, file chi lương NH bỏ qua.
            m.put("onMaternityLeave", u.isOnMaternityLeave());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── Salary ────────────────────────────────────────────────────────────────

    @PostMapping("/salaries")
    public ResponseEntity<SalaryDto> setSalary(@RequestBody SalaryRequest req,
                                               Authentication auth) {
        return ResponseEntity.ok(hrService.setSalary(req, auth));
    }

    @PostMapping("/salaries/batch")
    public ResponseEntity<List<SalaryDto>> batchSetSalary(@RequestBody BatchSalaryRequest req) {
        return ResponseEntity.ok(hrService.batchSetSalary(req));
    }

    /** Xem trước breakdown lương từ dữ liệu đang nhập (chưa lưu) — cho panel preview của SUPER_ACCOUNTANT. */
    @PostMapping("/salaries/preview")
    public ResponseEntity<SalaryBreakdownDto> previewSalary(@RequestBody SalaryRequest req) {
        return ResponseEntity.ok(hrService.previewSalary(req));
    }

    /** Danh mục nhãn phụ cấp (để chọn lại). Tự seed các nhãn mặc định lần đầu. */
    @GetMapping("/allowance-labels")
    public ResponseEntity<List<AllowanceLabelDto>> listAllowanceLabels() {
        return ResponseEntity.ok(hrService.listAllowanceLabels());
    }

    /** Tạo nhãn phụ cấp mới (lưu lại để lần sau chọn). */
    @PostMapping("/allowance-labels")
    public ResponseEntity<AllowanceLabelDto> createAllowanceLabel(@RequestBody AllowanceLabelCreateRequest req) {
        return ResponseEntity.ok(hrService.createAllowanceLabel(req.getName()));
    }

    @GetMapping("/salaries")
    public ResponseEntity<PageResponse<SalaryDto>> listSalaries(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(hrService.listSalaries(status, pageable));
    }

    /** Lương hiện hành (mới nhất, bất kể trạng thái) của 1 nhân viên — dùng để hiển thị lại khi mở form cập nhật lương */
    @GetMapping("/salaries/current/{userId}")
    public ResponseEntity<SalaryDto> getCurrentSalary(@PathVariable Long userId) {
        SalaryDto dto = hrService.getCurrentSalary(userId);
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    /**
     * LƯƠNG ĐANG ÁP DỤNG + PHIẾU LƯƠNG MỚI CHỜ DUYỆT của 1 nhân viên.
     *
     * <p>Phục vụ nút "Xem lương" trên trang Nhân viên của OWNER: chưa có lương
     * thì hiện phiếu chờ kèm nút Duyệt, đã có lương mà phát sinh phiếu mới thì
     * hiện hai card cạnh nhau để so sánh trước khi duyệt / từ chối.
     *
     * <pre>GET /api/hr/salaries/overview/{userId}</pre>
     */
    @GetMapping("/salaries/overview/{userId}")
    public ResponseEntity<SalaryOverviewDto> getSalaryOverview(@PathVariable Long userId) {
        return ResponseEntity.ok(hrService.getSalaryOverview(userId));
    }

    /** Lương hiện hành của TẤT CẢ nhân viên — dùng cho trang Owner xem breakdown lương toàn bộ nhân sự */
    @GetMapping("/salaries/current")
    public ResponseEntity<List<SalaryDto>> listCurrentSalaries() {
        return ResponseEntity.ok(hrService.listCurrentSalaries());
    }

    /**
     * Breakdown lương toàn bộ nhân sự cho Owner: lương trước thuế, tổng tiền
     * bảo hiểm DN đóng (21.5%), bảo hiểm + thuế TNCN NLĐ đóng, lương NET, và
     * 1 dòng tổng cộng tất cả nhân viên ở cuối.
     */
    @GetMapping("/salaries/breakdown")
    public ResponseEntity<SalaryBreakdownSummaryDto> getSalaryBreakdown() {
        return ResponseEntity.ok(hrService.getSalaryBreakdownSummary());
    }

    @PutMapping("/salaries/{id}/approve")
    public ResponseEntity<SalaryDto> approveSalary(@PathVariable Long id) {
        return ResponseEntity.ok(hrService.approveSalary(id));
    }

    @PutMapping("/salaries/bulk-approve")
    public ResponseEntity<List<SalaryDto>> bulkApproveSalary(@RequestBody BulkApproveSalaryRequest req) {
        return ResponseEntity.ok(hrService.bulkApproveSalary(req));
    }

    @PutMapping("/salaries/{id}/reject")
    public ResponseEntity<SalaryDto> rejectSalary(@PathVariable Long id,
                                                  @RequestBody RejectSalaryRequest req) {
        return ResponseEntity.ok(hrService.rejectSalary(id, req));
    }

    // ── Export employees Excel ─────────────────────────────────────────────────
    // Cột: ID, Tên NV, Bộ phận, Phòng ban, Chức vụ, Ngày vào làm, Lương NET thực
    // nhận, Lương đóng thuế/BH, Phụ cấp, Thưởng, Số người phụ thuộc.
    // LƯU Ý: cột "Lương" ở đây là LƯƠNG NET THỰC NHẬN (không phải GROSS) — GROSS
    // được tính ngược ra từ NET + số người phụ thuộc, xem
    // PayrollTaxCalculator.calcGrossFromNet.

    private static final int COL_ID = 0;
    private static final int COL_NAME = 1;
    private static final int COL_DEPARTMENT = 2;
    private static final int COL_DIVISION = 3;
    private static final int COL_POSITION = 4;

    /** NGÀY VÀO LÀM — căn cứ tính thâm niên. Xếp cạnh nhóm cột thông tin nhân sự. */
    private static final int COL_WORK_START_DATE = 5;

    private static final int COL_BASE_SALARY = 6;
    private static final int COL_INSURANCE_SALARY = 7;
    private static final int COL_ALLOWANCE = 8;
    private static final int COL_BONUS = 9;
    private static final int COL_DEPENDENTS = 10;
    private static final int TOTAL_COLS = 11;

    /** Định dạng ngày hiển thị trong file Excel (và chấp nhận khi đọc ngược lại). */
    private static final String WORK_START_DATE_FORMAT = "dd/MM/yyyy";

    /** Tiêu đề cột ngày vào làm — dùng luôn để nhận diện file export đời cũ. */
    private static final String WORK_START_DATE_HEADER = "Ngày vào làm (dd/MM/yyyy)";

    @GetMapping("/salaries/export")
    public ResponseEntity<byte[]> exportSalaries() throws Exception {
        String exportToken = _generateExportToken();

        // Danh sách nhân viên đang hoạt động (loại bỏ tài khoản khóa / không có role)
        List<User> users = userRepository.findAll().stream()
                .filter(u -> u.getRole() != null && !u.isLockAccount())
                .sorted(Comparator.comparing(User::getId))
                .collect(java.util.stream.Collectors.toList());

        // Lấy lương APPROVED mới nhất cho mỗi user
        List<SalaryDto> salaries = hrService.listLatestApprovedSalaries();
        Map<Long, SalaryDto> salaryMap = new LinkedHashMap<>();
        for (SalaryDto s : salaries) salaryMap.put(s.getUserId(), s);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // Sheet META — token xác thực + số lượng nhân viên tại thời điểm export
            XSSFSheet meta = wb.createSheet("META");
            wb.setSheetHidden(wb.getSheetIndex("META"), true);
            Row metaRow = meta.createRow(0);
            metaRow.createCell(0).setCellValue(exportToken);
            metaRow.createCell(1).setCellValue(users.size());

            // Sheet DATA
            XSSFSheet sheet = wb.createSheet("Bảng lương nhân viên");

            XSSFCellStyle headerStyle = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setWrapText(true);

            XSSFCellStyle lockStyle = wb.createCellStyle();
            lockStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            lockStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Ô ngày THẬT (không phải chuỗi) để HR dùng được lịch của Excel và
            // không gõ lệch định dạng.
            XSSFCellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper()
                    .createDataFormat().getFormat(WORK_START_DATE_FORMAT));

            String[] headers = {
                    "ID", "Tên nhân viên", "Bộ phận", "Phòng ban", "Chức vụ", WORK_START_DATE_HEADER, "Lương NET thực nhận", "Lương đóng thuế/BH", "Phụ cấp", "Thưởng", "Số người phụ thuộc"
            };
            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, i == COL_NAME ? 7000 : 4500);
            }
            sheet.createFreezePane(0, 1);

            int rowIdx = 1;
            for (User u : users) {
                SalaryDto s = salaryMap.get(u.getId());
                Row row = sheet.createRow(rowIdx++);

                Cell idCell = row.createCell(COL_ID);
                idCell.setCellValue(u.getId());
                idCell.setCellStyle(lockStyle); // không sửa cột ID

                row.createCell(COL_NAME).setCellValue(u.getFullName() != null ? u.getFullName() : u.getUsername());
                row.createCell(COL_DEPARTMENT).setCellValue(u.getDepartment() != null ? u.getDepartment() : "");
                row.createCell(COL_DIVISION).setCellValue(u.getDivision() != null ? u.getDivision() : "");
                row.createCell(COL_POSITION).setCellValue(u.getPosition() != null ? u.getPosition() : "");

                // NGÀY VÀO LÀM — CHƯA CÓ thì ĐỂ TRỐNG, không điền ngày mặc định:
                // ô trống là tín hiệu để HR nhìn ra ngay ai còn thiếu và điền vào.
                Cell startCell = row.createCell(COL_WORK_START_DATE);
                startCell.setCellStyle(dateStyle);
                if (u.getWorkStartDate() != null) {
                    startCell.setCellValue(new java.util.Date(u.getWorkStartDate()));
                }

                row.createCell(COL_BASE_SALARY).setCellValue(
                        s != null && s.getBaseSalary() != null ? s.getBaseSalary() : 0);
                row.createCell(COL_INSURANCE_SALARY).setCellValue(
                        s != null && s.getInsuranceSalary() != null && s.getInsuranceSalary() > 0
                                ? s.getInsuranceSalary()
                                : (s != null && s.getBaseSalary() != null ? s.getBaseSalary() : 0));
                row.createCell(COL_ALLOWANCE).setCellValue(
                        s != null && s.getAllowance() != null ? s.getAllowance() : 0);
                row.createCell(COL_BONUS).setCellValue(
                        s != null && s.getBonus() != null ? s.getBonus() : 0);
                row.createCell(COL_DEPENDENTS).setCellValue(
                        s != null && s.getDependents() != null ? s.getDependents() : 0);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            byte[] bytes = out.toByteArray();

            String filename = "bang-luong-nhan-su-" +
                    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy")) + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        }
    }

    // ── Import salary Excel ───────────────────────────────────────────────────
    // - Chỉ chấp nhận file vừa export từ hệ thống (token hợp lệ, chưa dùng).
    // - Số lượng nhân viên trong file phải khớp số lượng hiện tại.
    // - Mỗi dòng cập nhật đúng nhân viên theo ID ở cột A (không dùng index/STT).

    @PostMapping("/salaries/import")
    public ResponseEntity<ApiResponseLike> importSalaries(
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws Exception {
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream())) {
            // ── 1. Validate token từ sheet META ───────────────────────────
            Sheet meta = wb.getSheet("META");
            if (meta == null || meta.getRow(0) == null)
                return badRequest("File không hợp lệ: thiếu sheet xác thực. Vui lòng Export lại file mới.");
            Row metaRow = meta.getRow(0);
            String token = _cellStr(metaRow, 0);
            try {
                _validateAndConsumeToken(token);
            } catch (IllegalStateException ex) {
                return badRequest(ex.getMessage());
            }
            long exportedCount = (long) getDouble(metaRow, 1);

            Sheet sheet = wb.getSheet("Bảng lương nhân viên");
            if (sheet == null) return badRequest("File không hợp lệ: thiếu sheet 'Bảng lương nhân viên'.");

            // ── 1b. Chặn file export ĐỜI CŨ ────────────────────────────────
            //   Cột "Ngày vào làm" nằm GIỮA (sau Chức vụ) nên file cũ có mọi cột
            //   lương lệch đi một ô. Không chặn thì lương ghi vào ô phụ cấp, phụ
            //   cấp ghi vào ô thưởng — sai toàn bộ bảng lương mà KHÔNG có lỗi nào
            //   báo ra. Token của file cũ vẫn hợp lệ nên phải nhận diện bằng tiêu đề.
            Row headerRow = sheet.getRow(0);
            String workStartHeader = headerRow != null ? _cellStr(headerRow, COL_WORK_START_DATE) : null;
            if (workStartHeader == null || !workStartHeader.startsWith("Ngày vào làm")) {
                return badRequest("File này được Export từ phiên bản cũ (chưa có cột \"Ngày vào làm\"). "
                        + "Vui lòng Export lại file mới rồi nhập liệu trên file đó.");
            }

            // ── 2. Danh sách nhân viên hiện tại (cùng filter lúc export) ──
            List<User> currentUsers = userRepository.findAll().stream()
                    .filter(u -> u.getRole() != null && !u.isLockAccount())
                    .collect(java.util.stream.Collectors.toList());
            Map<Long, User> userById = currentUsers.stream()
                    .collect(java.util.stream.Collectors.toMap(User::getId, u -> u, (a, b) -> a));

            // ── 3. Đọc danh sách ID trong file ─────────────────────────────
            List<Long> fileIds = new ArrayList<>();
            List<Row> dataRows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String idStr = _cellStr(row, COL_ID);
                if (idStr == null || idStr.isBlank()) continue;
                Long id;
                try { id = Long.parseLong(idStr.trim()); }
                catch (Exception e) { continue; }
                fileIds.add(id);
                dataRows.add(row);
            }

            // ── 4. Kiểm tra số lượng nhân viên khớp ────────────────────────
            if (exportedCount > 0 && fileIds.size() != exportedCount)
                return badRequest("Số lượng nhân viên trong file (" + fileIds.size()
                        + ") không khớp với số lượng đã export (" + exportedCount
                        + "). Vui lòng không thêm/xóa dòng — Export lại file mới nếu danh sách nhân viên đã thay đổi.");
            if (fileIds.size() != currentUsers.size())
                return badRequest("Số lượng nhân viên trong file (" + fileIds.size()
                        + ") không khớp với số lượng nhân viên hiện tại (" + currentUsers.size()
                        + "). Vui lòng Export file mới để import lại.");

            // ── 5. Kiểm tra các ID khác nhau (không trùng lặp) ─────────────
            Set<Long> idSet = new HashSet<>(fileIds);
            if (idSet.size() != fileIds.size())
                return badRequest("File chứa ID nhân viên bị trùng lặp. Vui lòng Export lại file mới.");

            // ── 6. Kiểm tra mọi ID đều tồn tại trong hệ thống hiện tại ─────
            List<Long> unknownIds = fileIds.stream().filter(id -> !userById.containsKey(id)).toList();
            if (!unknownIds.isEmpty())
                return badRequest("File chứa ID nhân viên không tồn tại hoặc không còn hoạt động: " + unknownIds
                        + ". Vui lòng Export lại file mới.");

            // ── 7. Cập nhật từng dòng theo ID ──────────────────────────────
            int updated = 0, skipped = 0;
            List<String> errors = new ArrayList<>();

            // MỘT mốc ngày duy nhất cho cả lần import: tính trước vòng lặp để 200
            // nhân viên thiếu ngày đều nhận CÙNG một ngày, không bị lệch nhau vài
            // mili giây (hoặc lệch hẳn một ngày nếu import vắt qua nửa đêm).
            long importDate = java.time.LocalDate.now(SeniorityCalculator.ZONE)
                    .atStartOfDay(SeniorityCalculator.ZONE)
                    .toInstant().toEpochMilli();
            int backfilled = 0;

            for (Row row : dataRows) {
                Long id = Long.parseLong(_cellStr(row, COL_ID).trim());
                User u = userById.get(id);
                try {
                    String department = _cellStr(row, COL_DEPARTMENT);
                    String division   = _cellStr(row, COL_DIVISION);
                    String position   = _cellStr(row, COL_POSITION);

                    // ── NGÀY VÀO LÀM ──────────────────────────────────────
                    //   Có trong file → dùng luôn.
                    //   Ô trống       → CHƯA có ngày trong hệ thống ⇒ lấy NGÀY
                    //                   IMPORT làm mốc; ĐÃ có ngày rồi ⇒ giữ
                    //                   nguyên, không đụng tới.
                    Long workStartDate = _cellDateMillis(row, COL_WORK_START_DATE);
                    if (workStartDate == null && (u == null || u.getWorkStartDate() == null)) {
                        workStartDate = importDate;
                        backfilled++;
                    }

                    UpdateEmployeeInfoRequest infoReq = new UpdateEmployeeInfoRequest();
                    infoReq.setDepartment(department);
                    infoReq.setDivision(division);
                    infoReq.setPosition(position);
                    infoReq.setWorkStartDate(workStartDate);   // null = giữ nguyên
                    hrService.updateEmployeeInfo(id, infoReq);

                    SalaryRequest req = new SalaryRequest();
                    req.setUserId(id);
                    req.setBaseSalary((long) getDouble(row, COL_BASE_SALARY));
                    req.setInsuranceSalary((long) getDouble(row, COL_INSURANCE_SALARY));
                    req.setAllowance((long) getDouble(row, COL_ALLOWANCE));
                    req.setBonus((long) getDouble(row, COL_BONUS));
                    req.setDependents((int) getDouble(row, COL_DEPENDENTS));

                    hrService.setSalary(req, auth);
                    updated++;
                } catch (Exception ex) {
                    errors.add("ID " + id + " (" + (u != null ? u.getFullName() : "?") + "): " + ex.getMessage());
                    skipped++;
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("updated", updated);
            result.put("skipped", skipped);
            result.put("errors", errors);
            result.put("workStartDateBackfilled", backfilled);

            // Nói rõ đã tự điền ngày cho bao nhiêu người: đây là thao tác ghi dữ
            // liệu mà HR không chủ động yêu cầu, im lặng làm thì họ không biết
            // đường mà sửa lại người nào có ngày vào làm thật khác.
            String msg = "Import hoàn tất: " + updated + " thành công, " + skipped + " bỏ qua";
            if (backfilled > 0) {
                msg += " — đã tự điền ngày vào làm (" + java.time.LocalDate.now(SeniorityCalculator.ZONE)
                        .format(java.time.format.DateTimeFormatter.ofPattern(WORK_START_DATE_FORMAT))
                        + ") cho " + backfilled + " nhân viên còn trống";
            }
            return ResponseEntity.ok(ApiResponseLike.success(result, msg));
        }
    }

    private ResponseEntity<ApiResponseLike> badRequest(String message) {
        return ResponseEntity.ok(ApiResponseLike.error(message));
    }

    /** Wrapper response nhẹ — tránh phụ thuộc trực tiếp vào ApiResponse của module khác. */
    public static class ApiResponseLike {
        public boolean success;
        public String message;
        public Object data;

        public static ApiResponseLike success(Object data, String message) {
            ApiResponseLike r = new ApiResponseLike();
            r.success = true; r.message = message; r.data = data;
            return r;
        }
        public static ApiResponseLike error(String message) {
            ApiResponseLike r = new ApiResponseLike();
            r.success = false; r.message = message; r.data = null;
            return r;
        }
    }

    private String _cellStr(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> null;
        };
    }

    private double getDouble(Row row, int col) {
        try { Cell c = row.getCell(col); return c == null ? 0.0 : c.getNumericCellValue(); } catch (Exception e) { return 0.0; }
    }

    /**
     * ĐỌC Ô NGÀY từ file import → epoch millis (mốc 00:00 giờ VN).
     *
     * <p>Chấp nhận cả hai kiểu vì thực tế HR nhập cả hai:
     * <ul>
     *   <li><b>Ô ngày thật của Excel</b> — kiểu file export ra, hoặc HR chọn từ
     *       lịch. POI trả về NUMERIC nên phải hỏi {@code DateUtil} mới biết đó là
     *       ngày chứ không phải con số.</li>
     *   <li><b>Chuỗi gõ tay</b> — {@code dd/MM/yyyy} (kiểu VN, ưu tiên) hoặc
     *       {@code yyyy-MM-dd}. Nhiều người dán từ chỗ khác vào nên ô thành text
     *       chứ không thành ngày; từ chối thẳng thì họ không hiểu vì sao sai.</li>
     * </ul>
     *
     * @return {@code null} khi ô trống hoặc không đọc được thành ngày
     */
    private Long _cellDateMillis(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;

        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                if (!DateUtil.isCellDateFormatted(cell)) return null;
                java.util.Date d = cell.getDateCellValue();
                if (d == null) return null;
                return d.toInstant()
                        .atZone(SeniorityCalculator.ZONE)
                        .toLocalDate()
                        .atStartOfDay(SeniorityCalculator.ZONE)
                        .toInstant().toEpochMilli();
            }
        } catch (Exception ignored) { /* rơi xuống nhánh đọc chuỗi bên dưới */ }

        String raw = _cellStr(row, col);
        if (raw == null || raw.isBlank()) return null;
        raw = raw.trim();

        for (String pattern : new String[] { WORK_START_DATE_FORMAT, "d/M/yyyy", "yyyy-MM-dd", "dd-MM-yyyy" }) {
            try {
                java.time.LocalDate d = java.time.LocalDate.parse(raw,
                        java.time.format.DateTimeFormatter.ofPattern(pattern));
                return d.atStartOfDay(SeniorityCalculator.ZONE).toInstant().toEpochMilli();
            } catch (Exception ignored) { /* thử mẫu tiếp theo */ }
        }
        return null;   // gõ sai định dạng ⇒ coi như để trống
    }

    // ── Leave ─────────────────────────────────────────────────────────────────

    @PostMapping("/leaves")
    public ResponseEntity<LeaveRequestDto> createLeave(@RequestBody LeaveRequestCreate req) {
        return ResponseEntity.ok(hrService.createLeave(req));
    }

    @GetMapping("/leaves")
    public ResponseEntity<PageResponse<LeaveRequestDto>> listLeaves(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(hrService.listLeaves(from, to, pageable));
    }

    @GetMapping("/leaves/{id}")
    public ResponseEntity<LeaveRequestDto> getLeave(@PathVariable Long id) {
        return ResponseEntity.ok(hrService.getLeave(id));
    }

    // ── Overtime ──────────────────────────────────────────────────────────────

    @PostMapping("/overtimes")
    public ResponseEntity<OvertimeRequestDto> createOvertime(@RequestBody OvertimeRequestCreate req) {
        return ResponseEntity.ok(hrService.createOvertime(req));
    }

    @GetMapping("/overtimes")
    public ResponseEntity<PageResponse<OvertimeRequestDto>> listOvertimes(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(hrService.listOvertimes(from, to, pageable));
    }

    @GetMapping("/overtimes/{id}")
    public ResponseEntity<OvertimeRequestDto> getOvertime(@PathVariable Long id) {
        return ResponseEntity.ok(hrService.getOvertime(id));
    }

    // ── Ứng lương ─────────────────────────────────────────────────────────────

    /**
     * Thông tin ứng lương của nhân viên trong tháng hiện tại:
     * - lương cơ bản (từ hồ sơ lương đã duyệt)
     * - tổng đã ứng (tổng phiếu chi SALARY_ADVANCE đã duyệt trong tháng)
     * - còn lại có thể ứng = cơ bản - đã ứng
     *
     * Dùng cho form tạo phiếu chi khi chọn layout ỨNG LƯƠNG.
     */
    @GetMapping("/salary-advance/info/{userId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SUPER_ACCOUNTANT','ACCOUNTANT')")
    public ResponseEntity<SalaryAdvanceInfoDto> getSalaryAdvanceInfo(@PathVariable Long userId) {
        return ResponseEntity.ok(hrService.getSalaryAdvanceInfo(userId));
    }
}