package com.nhatnam.server.restcontroller.hr;

import com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos;
import com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.*;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.FactoryKpiBonus;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.FactoryKpiService;
import com.nhatnam.server.service.FactoryPayrollService;
import com.nhatnam.server.entity.MonthlyAdjustment;
import com.nhatnam.server.service.attendance.AttendanceTemplateService;
import com.nhatnam.server.service.attendance.PayrollAdjustmentTemplateService;
import com.nhatnam.server.service.hr.HrService;
import com.nhatnam.server.service.hr.PayrollAdjustmentService;
import com.nhatnam.server.service.hr.PayrollDepartmentResolver;
import com.nhatnam.server.service.hr.PayrollPasscodeService;
import com.nhatnam.server.service.hr.PaymentTransactionBackfillService;
import com.nhatnam.server.service.hr.SalaryExportService;
import com.nhatnam.server.service.hr.BankPaymentExportService;
import com.nhatnam.server.dto.factorypayroll.SalaryExportRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API "QUẢN LÝ LƯƠNG" cho nhân viên MỌI BỘ PHẬN + quản trị bảng chấm công.
 *
 * <pre>
 *  ── Nhân viên ───────────────────────────────────────────────────────────────
 *  GET  /api/factory-payroll/me                    → bộ phận / role nhận lương của tôi
 *  GET  /api/factory-payroll/periods               → danh sách tháng ĐÃ QUA để chọn
 *  GET  /api/factory-payroll/my-payslip?month&year → phiếu lương tháng đó
 *
 *  ── OWNER / ADMIN / HR / SUPER_ACCOUNTANT ───────────────────────────────────
 *  GET  /api/factory-payroll/departments                        → 5 bộ phận
 *  GET  /api/factory-payroll/sheets?department                  → các bảng đã upload
 *  GET  /api/factory-payroll/sheets/periods?department          → tháng thao tác được
 *  GET  /api/factory-payroll/sheets/status?month&year&department→ trạng thái 1 bộ phận
 *  GET  /api/factory-payroll/sheets/status-all?month&year       → trạng thái CẢ 5 bộ phận
 *  POST /api/factory-payroll/sheets/upload?month&year&department
 *  POST /api/factory-payroll/exceptions/upload?month&year&department
 *  POST /api/factory-payroll/leaves/upload?month&year&department
 *  DEL  /api/factory-payroll/files/{kind}?month&year&department
 *  POST /api/factory-payroll/finalize?month&year&department     → HOÀN TẤT
 *  POST /api/factory-payroll/reopen?month&year&department       → mở lại
 *  GET  /api/factory-payroll/department-payroll?month&year&department
 *                                                → 2 bảng Phiếu lương + Ngày công
 *  GET  /api/factory-payroll/employee-attendance?userId&month&year
 *  GET  /api/factory-payroll/employee-driver?userId&month&year
 *  GET  /api/factory-payroll/kpi?month&year
 *  POST /api/factory-payroll/kpi/recompute?month&year&securityRate
 *  GET  /api/factory-payroll/kpi/carry-over-seeds?month&year  → quỹ dư khai báo tay
 *  POST /api/factory-payroll/kpi/carry-over-seeds             → thêm khoản dư
 *  DEL  /api/factory-payroll/kpi/carry-over-seeds/{id}
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/factory-payroll")
@RequiredArgsConstructor
public class FactoryPayrollController {

    private final FactoryPayrollService payrollService;
    private final FactoryKpiService kpiService;
    private final PayrollDepartmentResolver deptResolver;
    private final PayrollAdjustmentService adjustmentService;
    private final HrService hrService;
    private final PayrollPasscodeService passcodeService;
    private final SalaryExportService salaryExportService;
    private final BankPaymentExportService bankPaymentExportService;
    private final PaymentTransactionBackfillService ptBackfillService;

    /** Các role được quản trị bảng chấm công. */
    private static final String ADMIN_ROLES = "hasAnyRole('OWNER','ADMIN','HR','SUPER_ACCOUNTANT')";

    // ══════════════════════════════════════════════════════════════════════════
    // NHÂN VIÊN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Bộ phận + role NHẬN LƯƠNG của tài khoản đang đăng nhập.
     * FE dùng để biết có hiện bảng "Thưởng KPI sản xuất" hay panel số km hay không.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@AuthenticationPrincipal User user) {
        try {
            PayrollDepartment d = deptResolver.departmentOf(user);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", user.getId());
            m.put("fullName", user.getFullName());
            m.put("payrollRole", deptResolver.payrollRoleOf(user) != null
                    ? deptResolver.payrollRoleOf(user).name() : null);
            m.put("roleLabel", deptResolver.roleLabelOf(user));
            m.put("department", d != null ? d.name() : null);
            m.put("departmentLabel", d != null ? d.getLabel() : null);
            m.put("hasKpiBonus", d != null && d.isKpiBonus());
            m.put("attendanceBased", d != null && d.isAttendanceBased());
            m.put("hasPayroll", d != null);
            return ResponseEntity.ok(ApiResponse.success(m, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Các tháng ĐÃ KẾT THÚC — tháng hiện tại không có trong danh sách. */
    @GetMapping("/periods")
    public ResponseEntity<ApiResponse<List<PeriodOptionDto>>> periods(
            @AuthenticationPrincipal User user) {
        try {
            PayrollDepartment d = deptResolver.departmentOf(user);
            return ResponseEntity.ok(ApiResponse.success(payrollService.availablePeriods(d), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Phiếu lương của chính nhân viên đang đăng nhập.
     * {@code status = PROCESSING} khi OWNER chưa bấm "Hoàn tất" cho tháng + bộ phận.
     */
    /**
     * PHIẾU LƯƠNG CỦA TÔI.
     *
     * <p>Chặn bằng PASSCODE XEM LƯƠNG: phải nhập đúng 6 số ở màn hình khoá thì
     * mới có "vé" hợp lệ. Kiểm tra ở đây (không chỉ ở FE) vì màn hình khoá phía
     * client có thể bị bỏ qua bằng cách gọi thẳng endpoint này.
     */
    @GetMapping("/my-payslip")
    public ResponseEntity<ApiResponse<MyPayslipDto>> myPayslip(
            @AuthenticationPrincipal User user,
            @RequestParam int month,
            @RequestParam int year) {
        try {
            if (passcodeService.isLocked(user))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.PAYROLL_PASSCODE_LOCKED,
                        "Chức năng xem lương đang bị khoá. Vui lòng liên hệ quản trị viên."));

            if (!passcodeService.hasValidAccessFor(user))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.PAYROLL_PASSCODE_REQUIRED,
                        "Vui lòng nhập mật khẩu xem lương."));

            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.getMyPayslip(user, month, year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi lấy phiếu lương {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // QUẢN TRỊ BẢNG CHẤM CÔNG
    // ══════════════════════════════════════════════════════════════════════════

    /** Danh sách 5 bộ phận để FE dựng tab. */
    @GetMapping("/departments")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> departments() {
        List<Map<String, Object>> out = Arrays.stream(PayrollDepartment.values())
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", d.name());
                    m.put("label", d.getLabel());
                    m.put("attendanceBased", d.isAttendanceBased());
                    m.put("hasKpiBonus", d.isKpiBonus());
                    m.put("employeeCount", payrollService.employeesOf(d).size());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(out, "OK"));
    }

    @GetMapping("/sheets")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<List<AttendanceSheetDto>>> sheets(
            @RequestParam(required = false) String department) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.listSheets(PayrollDepartment.parse(department)), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** OWNER upload bảng chấm công Excel cho 1 tháng × bộ phận. Upload lại = GHI ĐÈ. */
    @PostMapping(value = "/sheets/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceImportResultDto>> uploadSheet(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file,
            @RequestParam int month,
            @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            if (file == null || file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Vui lòng chọn file"));

            PayrollDepartment d = requireDept(department);
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.uploadSheet(file, month, year, d, user),
                    "Đã tải lên bảng chấm công %s tháng %d/%d".formatted(d.getLabel(), month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi upload chấm công {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Các tháng được phép thao tác: tháng hiện tại + quá khứ, KHÔNG có tương lai. */
    @GetMapping("/sheets/periods")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<List<PeriodOptionDto>>> uploadablePeriods(
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.uploadablePeriods(requireDept(department)), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Trạng thái 3 loại file + cờ Hoàn tất của 1 tháng × bộ phận. */
    @GetMapping("/sheets/status")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceSheetDto>> monthStatus(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.monthStatus(month, year, requireDept(department)), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Trạng thái của CẢ 5 bộ phận trong 1 tháng — FE dựng tab bar 1 lần. */
    @GetMapping("/sheets/status-all")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<List<AttendanceSheetDto>>> monthStatusAll(
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.monthStatusAll(month, year), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Lịch nghỉ / đi trễ / về sớm của BỘ PHẬN ───────────────────────────────

    @PostMapping(value = "/exceptions/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceImportResultDto>> uploadExceptions(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file,
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            if (file == null || file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Vui lòng chọn file"));
            PayrollDepartment d = requireDept(department);
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.uploadExceptionFile(file, month, year, d, user),
                    "Đã tải lên lịch nghỉ %s tháng %d/%d".formatted(d.getLabel(), month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi upload lịch nghỉ {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Đơn xin đi trễ / về sớm / nghỉ phép CÁ NHÂN ───────────────────────────

    @PostMapping(value = "/leaves/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceImportResultDto>> uploadLeaves(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file,
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            if (file == null || file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Vui lòng chọn file"));
            PayrollDepartment d = requireDept(department);
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.uploadLeaveFile(file, month, year, d, user),
                    "Đã tải lên đơn xin nghỉ %s tháng %d/%d".formatted(d.getLabel(), month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi upload đơn xin nghỉ {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Xoá file của tháng × bộ phận ──────────────────────────────────────────

    /** @param kind ATTENDANCE | EXCEPTION | LEAVE */
    @DeleteMapping("/files/{kind}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<String>> deleteFile(
            @PathVariable String kind,
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            FactoryPayrollService.FileKind k =
                    FactoryPayrollService.FileKind.valueOf(kind.toUpperCase());
            PayrollDepartment d = requireDept(department);
            payrollService.deleteFile(k, month, year, d);
            return ResponseEntity.ok(ApiResponse.success("OK",
                    "Đã xoá file của %s tháng %d/%d".formatted(d.getLabel(), month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi xoá file {} {}/{}", kind, month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── HOÀN TẤT / MỞ LẠI ─────────────────────────────────────────────────────

    /** OWNER bấm "Hoàn tất" — nhân viên bộ phận đó xem được phiếu lương của tháng. */
    @PostMapping("/finalize")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceSheetDto>> finalizePeriod(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            PayrollDepartment d = requireDept(department);
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.finalizePeriod(month, year, d, user),
                    "Đã hoàn tất xử lý lương %s tháng %d/%d".formatted(d.getLabel(), month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi hoàn tất lương {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Mở lại tháng đã hoàn tất LƯƠNG — nhân viên quay về "Đang xử lý lương". */
    @PostMapping("/reopen")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceSheetDto>> reopenPeriod(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            PayrollDepartment d = requireDept(department);
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.reopenPeriod(month, year, d),
                    "Đã mở lại tháng %d/%d của %s".formatted(month, year, d.getLabel())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * OWNER bấm "Hoàn tất KPI / Thưởng" — nhân viên thấy KPI và bonus.
     * Phải gọi sau khi đã hoàn tất Lương.
     */
    @PostMapping("/finalize-kpi")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceSheetDto>> finalizeKpi(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            PayrollDepartment d = requireDept(department);
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.finalizeKpi(month, year, d, user),
                    "Đã hoàn tất KPI/Thưởng %s tháng %d/%d".formatted(d.getLabel(), month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi hoàn tất KPI {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Mở lại KPI/Thưởng đã hoàn tất — nhân viên quay về "Đang tính thưởng". */
    @PostMapping("/reopen-kpi")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceSheetDto>> reopenKpi(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            PayrollDepartment d = requireDept(department);
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.reopenKpi(month, year, d),
                    "Đã mở lại KPI tháng %d/%d của %s".formatted(month, year, d.getLabel())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }


    // ══════════════════════════════════════════════════════════════════════════
    // THƯỞNG DOANH THU — chỉ SALES và ACCOUNTING (bước 3)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Tính và chốt thưởng doanh thu cho SALES hoặc ACCOUNTING.
     * Phải gọi sau khi đã hoàn tất KPI.
     *
     * <p>POST /api/factory-payroll/finalize-bonus?month=8&year=2025&department=SALES
     */
    @PostMapping("/finalize-bonus")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<FactoryPayrollDtos.OfficeBonusSummaryDto>> finalizeBonus(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "SALES") String department,
            @RequestParam(required = false) Long unitPrice) {
        try {
            PayrollDepartment d = requireDept(department);
            FactoryPayrollDtos.OfficeBonusSummaryDto result =
                    payrollService.finalizeBonus(month, year, d, user, unitPrice);
            return ResponseEntity.ok(ApiResponse.success(result,
                    "Đã tính hoa hồng %s tháng %d/%d: pool %,dđ"
                            .formatted(d.getLabel(), month, year, result.getTotalBonusPool())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi tính hoa hồng {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Mở lại Thưởng doanh thu — nhân viên quay về "Đang tính thưởng".
     *
     * <p>POST /api/factory-payroll/reopen-bonus?month=8&year=2025&department=SALES
     */
    @PostMapping("/reopen-bonus")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceSheetDto>> reopenBonus(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "SALES") String department) {
        try {
            PayrollDepartment d = requireDept(department);
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.reopenBonus(month, year, d),
                    "Đã mở lại Thưởng tháng %d/%d của %s".formatted(month, year, d.getLabel())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Lấy kết quả thưởng doanh thu đã tính (hoặc null nếu chưa tính).
     *
     * <p>GET /api/factory-payroll/office-bonus?month=8&year=2025&department=SALES
     */
    @GetMapping("/office-bonus")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<FactoryPayrollDtos.OfficeBonusSummaryDto>> getOfficeBonus(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "SALES") String department) {
        try {
            PayrollDepartment d = requireDept(department);
            FactoryPayrollDtos.OfficeBonusSummaryDto result =
                    payrollService.getOfficeBonusSummary(month, year, d);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HOA HỒNG DOANH THU (11/2026) — preview + đơn giá tháng trước + backfill
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Preview Thưởng/Hoa hồng cho SALES/ACCOUNTING — chạy được cả KHI CHƯA TÍNH
     * (bonusAmount = null) và SAU KHI đã tính. Trả 3 stat phòng + mỗi nhân viên 1 dòng.
     *
     * <p>GET /api/factory-payroll/office-bonus-preview?month=9&year=2026&department=SALES
     */
    @GetMapping("/office-bonus-preview")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<FactoryPayrollDtos.OfficeBonusPreviewDto>> officeBonusPreview(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "SALES") String department) {
        try {
            PayrollDepartment d = requireDept(department);
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.getOfficeBonusPreview(month, year, d), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi preview office-bonus {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Trả về đơn giá hoa hồng của THÁNG GẦN NHẤT có record (null nếu chưa từng).
     * FE dùng làm placeholder cho ô input "Đơn giá".
     *
     * <p>GET /api/factory-payroll/office-bonus/last-unit-price?month=9&year=2026&department=SALES
     */
    @GetMapping("/office-bonus/last-unit-price")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<Map<String, Object>>> lastCommissionUnitPrice(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "SALES") String department) {
        try {
            PayrollDepartment d = requireDept(department);
            Long price = payrollService.findLastCommissionUnitPrice(month, year, d);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("department", d.name());
            body.put("month", month);
            body.put("year", year);
            body.put("lastCommissionUnitPrice", price);
            return ResponseEntity.ok(ApiResponse.success(body, "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * BACKFILL PaymentTransaction từ order_log — dùng 1 lần sau khi deploy để
     * tạo PT cho các đơn thu CŨ (trước khi code mới ghi PT trực tiếp).
     *
     * <p>POST /api/factory-payroll/payment-transactions/backfill
     *      ?dryRun=true                     — scan + đếm, không ghi (MẶC ĐỊNH để an toàn)
     *      [&month=9&year=2026]             — chỉ 1 tháng (giờ VN)
     *      [&fromMs=...&toMs=...]           — khoảng epoch ms tuỳ ý
     *      (bỏ trống cả 2 → backfill TOÀN BỘ từ 2020-01-01 VN)
     *
     * <p>Chỉ OWNER / ADMIN được chạy.
     */
    @PostMapping("/payment-transactions/backfill")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<FactoryPayrollDtos.PaymentBackfillReportDto>> backfillPaymentTransactions(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long fromMs,
            @RequestParam(required = false) Long toMs) {
        try {
            FactoryPayrollDtos.PaymentBackfillReportDto report;
            if (month != null && year != null) {
                report = ptBackfillService.backfillMonth(month, year, dryRun);
            } else if (fromMs != null && toMs != null) {
                report = ptBackfillService.backfillRange(fromMs, toMs, dryRun);
            } else {
                report = ptBackfillService.backfillAll(dryRun);
            }
            String msg = dryRun
                    ? "DRY-RUN: scan %d, sẽ tạo %d, bỏ qua %d (đã tồn tại) + %d (ko parse được)"
                    .formatted(report.getScannedLogs(), report.getCreatedTransactions(),
                            report.getSkippedExisting(), report.getSkippedUnparseable())
                    : "Đã tạo %d PaymentTransaction (bỏ qua %d đã tồn tại)"
                    .formatted(report.getCreatedTransactions(), report.getSkippedExisting());
            return ResponseEntity.ok(ApiResponse.success(report, msg));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi backfill PaymentTransaction", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── 2 bảng OWNER xem sau khi Hoàn tất ─────────────────────────────────────

    @GetMapping("/department-payroll")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<DepartmentPayrollDto>> departmentPayroll(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.departmentPayroll(month, year, requireDept(department)), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi lấy bảng lương bộ phận {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Chi tiết ngày công của 1 nhân viên — OWNER bấm vào 1 dòng trong bảng. */
    @GetMapping("/employee-attendance")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<AttendanceSummaryDto>> employeeAttendance(
            @RequestParam Long userId, @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.employeeAttendance(userId, month, year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Số km theo ngày của 1 tài xế — OWNER bấm vào 1 dòng tài xế. */
    @GetMapping("/employee-driver")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<DriverMonthDto>> employeeDriver(
            @RequestParam Long userId, @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.employeeDriverMonth(userId, month, year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Chi tiết breakdown lương của 1 user cho tháng — OWNER xem trong Sơ đồ tổ chức. */
    @GetMapping("/employee-salary-breakdown")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<com.nhatnam.server.dto.hr.HrDtos.SalaryBreakdownDto>> employeeSalaryBreakdown(
            @RequestParam Long userId, @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    hrService.getSalaryBreakdownForUser(userId, month, year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi lấy breakdown lương của user {}", userId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── LƯƠNG TÀI XẾ — giá xăng + đơn giá thưởng ──────────────────────────────

    /** Cấu hình + bảng lương tài xế của tháng (OWNER xem, tính thử theo giá đã nhập). */
    @GetMapping("/driver-config")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<DriverPayrollConfigDto>> driverConfig(
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.driverPayrollConfig(month, year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi lấy cấu hình lương tài xế {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** OWNER nhập / cập nhật giá xăng + đơn giá thưởng (xe máy & xe tải) cho tháng. */
    @PostMapping("/driver-config")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<DriverPayrollConfigDto>> saveDriverConfig(
            @RequestBody DriverConfigRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.saveDriverPayrollConfig(
                            req.month(), req.year(), req.gasPrice(),
                            req.bonusUnitPrice(), req.truckBonusUnitPrice()),
                    "Đã lưu giá xăng và đơn giá thưởng."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi lưu cấu hình lương tài xế", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    public record DriverConfigRequest(int month, int year, Long gasPrice,
                                      Long bonusUnitPrice, Long truckBonusUnitPrice) {}

    /** Chi tiết lương 1 tài xế trong tháng — dùng cho Tab 2 modal chi tiết. */
    @GetMapping("/driver-salary-detail")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<com.nhatnam.server.dto.hr.HrDtos.SalaryBreakdownDto>> driverSalaryDetail(
            @RequestParam Long userId, @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    payrollService.driverSalaryDetail(userId, month, year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi lấy chi tiết lương tài xế", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Tải file Excel mẫu ────────────────────────────────────────────────────

    /** Template lịch nghỉ — đã kê sẵn từng ngày của tháng. */
    @GetMapping("/templates/exception")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<Resource> exceptionTemplate(
            @RequestParam int month, @RequestParam int year) throws Exception {
        byte[] data = AttendanceTemplateService.buildExceptionTemplate(month, year);
        return xlsx(data, AttendanceTemplateService.exceptionFileName(month, year));
    }

    /**
     * DANH SÁCH NHÂN SỰ CỦA BỘ PHẬN — modal "Chi tiết bộ phận".
     *
     * <p>Không nhận tháng/năm: trả về ai đang thuộc bộ phận ngay lúc gọi, để
     * OWNER đối chiếu trước khi tải bảng chấm công lên.
     */
    @GetMapping("/departments/{department}/members")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<DepartmentMembersDto>> departmentMembers(
            @PathVariable String department) {
        PayrollDepartment d = requireDept(department);
        return ResponseEntity.ok(ApiResponse.success(payrollService.departmentMembers(d), "OK"));
    }

    /**
     * Template đơn xin nghỉ cá nhân — cột Nhân viên là dropdown lấy đúng danh
     * sách nhân sự CỦA BỘ PHẬN đang chọn nên không lo gõ sai tên.
     */
    @GetMapping("/templates/leave")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<Resource> leaveTemplate(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(defaultValue = "FACTORY") String department) throws Exception {
        PayrollDepartment d = requireDept(department);
        List<String> names = payrollService.employeesOf(d).stream()
                .map(User::getFullName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();
        byte[] data = AttendanceTemplateService.buildLeaveRequestTemplate(month, year, names);
        return xlsx(data, AttendanceTemplateService.leaveFileName(month, year));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THƯỞNG & PHỤ CẤP THEO THÁNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Template import THƯỞNG — prefill TOÀN BỘ nhân viên công ty đang hoạt
     * động (bao gồm Chủ tịch / Giám đốc / Kế toán trưởng), nhãn thưởng gõ 1
     * lần ở ô B2 áp dụng cho cả file.
     *
     * <p>FIX (10/2026 — Phase 7): bỏ phân mảnh theo {@code department}. Luồng
     * thưởng giờ import 1 file cho cả công ty (xem CompanyPayrollPanel). Tham
     * số {@code department} vẫn chấp nhận để tương thích API cũ nhưng KHÔNG
     * còn tác dụng — mọi lần tải đều ra cùng 1 danh sách công ty.
     */
    @GetMapping("/templates/bonus")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<Resource> bonusTemplate(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(required = false, defaultValue = "FACTORY") String department) throws Exception {
        byte[] data = PayrollAdjustmentTemplateService.buildBonusTemplate(
                month, year, deptResolver.allActiveWithExec(), "Cả công ty");
        return xlsx(data, PayrollAdjustmentTemplateService.bonusFileName(month, year));
    }

    /**
     * Template import PHỤ CẤP — prefill TOÀN BỘ nhân viên công ty đang hoạt
     * động (gồm Chủ tịch / Giám đốc / Kế toán trưởng); mỗi nhân viên có 4 cặp
     * (Khoản, Số tiền), ô Khoản là dropdown lấy từ {@code AllowanceLabel}.
     *
     * <p>FIX (10/2026 — Phase 7): xem chú thích {@link #bonusTemplate}.
     */
    @GetMapping("/templates/allowance")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<Resource> allowanceTemplate(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(required = false, defaultValue = "FACTORY") String department) throws Exception {
        List<String> labels = hrService.listAllowanceLabels().stream()
                .map(l -> l.getName())
                .filter(n -> n != null && !n.isBlank())
                .toList();
        byte[] data = PayrollAdjustmentTemplateService.buildAllowanceTemplate(
                month, year, deptResolver.allActiveWithExec(), labels, "Cả công ty");
        return xlsx(data, PayrollAdjustmentTemplateService.allowanceFileName(month, year));
    }

    @PostMapping(value = "/bonus/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<PayrollAdjustmentService.ImportResult>> uploadBonus(
            @RequestParam("file") MultipartFile file,
            @RequestParam int month, @RequestParam int year,
            @RequestParam(required = false) String department) {
        try {
            if (file == null || file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Vui lòng chọn file"));
            return ResponseEntity.ok(ApiResponse.success(
                    adjustmentService.importBonus(file, month, year, department),
                    "Đã import thưởng tháng %d/%d".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi import thưởng {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/allowance/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<PayrollAdjustmentService.ImportResult>> uploadAllowance(
            @RequestParam("file") MultipartFile file,
            @RequestParam int month, @RequestParam int year,
            @RequestParam(required = false) String department) {
        try {
            if (file == null || file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Vui lòng chọn file"));
            return ResponseEntity.ok(ApiResponse.success(
                    adjustmentService.importAllowance(file, month, year, department),
                    "Đã import phụ cấp tháng %d/%d".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi import phụ cấp {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Số dòng thưởng / phụ cấp đã import của kỳ + bộ phận — FE hiện badge. */
    @GetMapping("/adjustments/status")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<Map<String, Long>>> adjustmentStatus(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(required = false) String department) {
        return ResponseEntity.ok(ApiResponse.success(
                adjustmentService.counts(month, year, department), "OK"));
    }

    /** Danh sách khoản thưởng đã import (theo bộ phận). */
    @GetMapping("/adjustments/bonus/batches")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<List<PayrollAdjustmentService.AdjustmentBatch>>> bonusBatches(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(required = false) String department) {
        return ResponseEntity.ok(ApiResponse.success(
                adjustmentService.bonusBatches(month, year, department), "OK"));
    }

    /** PREVIEW dữ liệu đã import — trả về bảng đã lưu để OWNER xem lại (không sửa). */
    @GetMapping("/adjustments/preview")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<PayrollAdjustmentService.PreviewResult>> adjustmentPreview(
            @RequestParam int month, @RequestParam int year,
            @RequestParam String type,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String department) {
        try {
            MonthlyAdjustment.Type t = MonthlyAdjustment.Type.valueOf(type.toUpperCase());
            return ResponseEntity.ok(ApiResponse.success(
                    adjustmentService.preview(month, year, t, label, department), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @DeleteMapping("/adjustments/bonus")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<String>> clearBonusLabel(
            @RequestParam int month, @RequestParam int year,
            @RequestParam String label,
            @RequestParam(required = false) String department) {
        try {
            adjustmentService.clearBonusLabel(month, year, label, department);
            return ResponseEntity.ok(ApiResponse.success("OK", "Đã xoá khoản thưởng \"" + label + "\""));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @DeleteMapping("/adjustments/{type}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<String>> clearAdjustments(
            @PathVariable String type,
            @RequestParam int month, @RequestParam int year,
            @RequestParam(required = false) String department) {
        try {
            MonthlyAdjustment.Type t = MonthlyAdjustment.Type.valueOf(type.toUpperCase());
            adjustmentService.clear(month, year, t, department);
            return ResponseEntity.ok(ApiResponse.success("OK", "Đã xoá dữ liệu đã import"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Loại không hợp lệ: " + type));
        }
    }

    private ResponseEntity<Resource> xlsx(byte[] data, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // KPI (chỉ bộ phận Xưởng sản xuất)
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/kpi")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','HR','SUPER_ACCOUNTANT','SUPER_FACTORY_WORKER','FACTORY_MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> kpi(
            @RequestParam int month, @RequestParam int year) {
        try {
            payrollService.validatePastPeriod(month, year);
            return ResponseEntity.ok(ApiResponse.success(toKpiMap(kpiService.getOrCompute(month, year)), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/kpi/recompute")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recompute(
            @RequestParam int month, @RequestParam int year,
            @RequestParam(required = false) Long securityRate) {
        try {
            payrollService.validatePastPeriod(month, year);
            // Bỏ trống securityRate = giữ nguyên mức đã dùng cho tháng này.
            return ResponseEntity.ok(ApiResponse.success(
                    toKpiMap(kpiService.recompute(month, year, securityRate)),
                    "Đã tính lại thưởng KPI"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── QUỸ DƯ KPI KHAI BÁO TAY ───────────────────────────────────────────────
    //
    // Dùng khi tháng trước đã chia thưởng NGOÀI app và còn dư: không có bản ghi
    // FactoryKpiBonus nào để kế thừa sổ dư, nên phải khai báo tay khoản đó vào
    // tháng đầu tiên được tính trên app. Xem FactoryKpiCarryOverSeed.

    @GetMapping("/kpi/carry-over-seeds")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','HR','SUPER_ACCOUNTANT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listCarryOverSeeds(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year) {
        try {
            var list = (month != null && year != null)
                    ? kpiService.listCarryOverSeeds(month, year)
                    : kpiService.listCarryOverSeeds();
            return ResponseEntity.ok(ApiResponse.success(
                    list.stream().map(this::toSeedMap).toList(), "OK"));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi lấy danh sách quỹ dư khai báo tay", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/kpi/carry-over-seeds")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addCarryOverSeed(
            @AuthenticationPrincipal User actor,
            @RequestBody CarryOverSeedRequest req) {
        try {
            String name = actor != null
                    ? (actor.getFullName() != null ? actor.getFullName() : actor.getUsername())
                    : "?";
            var seed = kpiService.addCarryOverSeed(
                    req.getApplyMonth(), req.getApplyYear(),
                    req.getSourceMonth(), req.getSourceYear(),
                    req.getAmount() != null ? req.getAmount() : 0L,
                    req.getNote(), name);
            return ResponseEntity.ok(ApiResponse.success(toSeedMap(seed),
                    "Đã lưu quỹ dư. Bấm \"Tính lại thưởng KPI\" để áp dụng vào tháng này."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi lưu quỹ dư khai báo tay", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @DeleteMapping("/kpi/carry-over-seeds/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteCarryOverSeed(@PathVariable long id) {
        try {
            kpiService.deleteCarryOverSeed(id);
            return ResponseEntity.ok(ApiResponse.success(null,
                    "Đã xoá. Bấm \"Tính lại thưởng KPI\" để cập nhật lại quỹ."));
        } catch (Exception e) {
            log.error("[FactoryPayroll] Lỗi xoá quỹ dư khai báo tay id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private Map<String, Object> toSeedMap(com.nhatnam.server.entity.FactoryKpiCarryOverSeed s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("applyMonth", s.getApplyMonth());
        m.put("applyYear", s.getApplyYear());
        m.put("applyLabel", "T%d/%d".formatted(s.getApplyMonth(), s.getApplyYear()));
        m.put("sourceMonth", s.getSourceMonth());
        m.put("sourceYear", s.getSourceYear());
        m.put("sourceLabel", "T%d/%d".formatted(s.getSourceMonth(), s.getSourceYear()));
        m.put("amount", s.getAmount());
        m.put("note", s.getNote());
        m.put("createdAt", s.getCreatedAt());
        m.put("createdBy", s.getCreatedBy());
        return m;
    }

    /** Body khai báo một khoản quỹ dư. */
    @lombok.Data
    public static class CarryOverSeedRequest {
        private Integer applyMonth;
        private Integer applyYear;
        private Integer sourceMonth;
        private Integer sourceYear;
        private Long amount;
        private String note;
    }

    private Map<String, Object> toKpiMap(FactoryKpiBonus k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("month", k.getMonth());
        m.put("year", k.getYear());
        m.put("totalOutputKg", k.getTotalOutputKg());
        m.put("totalOutputTon", k.getTotalOutputTon());
        m.put("ratePerTon", k.getRatePerTon());
        m.put("securityRate", k.getSecurityRate());
        m.put("bonusPool", k.getBonusPool());
        m.put("carryOverIn", k.getCarryOverIn());
        m.put("carryOverOut", k.getCarryOverOut());
        m.put("securityTotal", k.getSecurityTotal());
        m.put("distributedTotal", k.getDistributedTotal());
        m.put("totalWeight", k.getTotalWeight());
        // Quỹ dư đầu vào tách theo tháng phát sinh — để card tổng hợp nói được
        // "513.000đ gồm 113.000đ của T6 và 400.000đ của T7"
        m.put("carryOverInDetail", kpiService.carryOverInDetail(k.getMonth(), k.getYear()).stream()
                .map(e -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("month", e.getMonth());
                    cm.put("year", e.getYear());
                    cm.put("amount", e.getAmount());
                    cm.put("label", "T%d/%d".formatted(e.getMonth(), e.getYear()));
                    return cm;
                }).toList());
        m.put("items", k.getItems().stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("userId", i.getUser() != null ? i.getUser().getId() : null);
            im.put("userFullName", i.getUserFullName());
            im.put("role", i.getRole() != null ? i.getRole().name() : null);
            im.put("roleLabel", i.getRoleLabel());
            im.put("weight", i.getWeight());
            im.put("fixedAmountRole", i.getFixedAmountRole());
            im.put("rawAmount", i.getRawAmount());
            im.put("amount", i.getAmount());
            return im;
        }).toList());
        return m;
    }

    // ══════════════════════════════════════════════════════════════════════════

    private PayrollDepartment requireDept(String raw) {
        PayrollDepartment d = PayrollDepartment.parse(raw);
        if (d == null) throw new IllegalArgumentException("Bộ phận không hợp lệ: " + raw);
        return d;
    }

    // ── EXPORT FILE LƯƠNG TỔNG HỢP ─────────────────────────────────────────────

    /**
     * Export file lương tổng hợp theo phòng ban — chỉ OWNER/ADMIN.
     *
     * <p>Request body:
     * <pre>{ "departments": ["MANAGEMENT","ACCOUNTING","FACTORY","SALES","WAREHOUSE"],
     *   "exportType": "SALARY_AND_BONUS" }</pre>
     *
     * exportType: SALARY_ONLY | BONUS_ONLY | SALARY_AND_BONUS
     */
    @PostMapping("/salary-export")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> exportSalaryReport(
            @RequestParam int month,
            @RequestParam int year,
            @RequestBody SalaryExportRequest request) {
        // KHÔNG chặn khi tháng chưa có PayrollBatch APPROVED/PENDING_APPROVAL.
        //
        // File "lương tổng hợp" này được dùng để XEM TRƯỚC (preview) trong lúc
        // HR đang tính lương — HR cần thấy con số chi tiết trên Excel ĐỂ QUYẾT
        // ĐỊNH có tạo batch chính thức hay không, hoặc có phải sửa lại hồ sơ
        // lương / thưởng KPI trước không. Chặn ở đây sẽ tạo vòng lặp con-gà-quả-
        // trứng: muốn tính lương phải có batch, muốn có batch phải xem lại lương.
        //
        // Bug về "dùng lương tháng cũ" chỉ còn nguy cơ ở file NH (chuyển khoản
        // thật) → giữ check ở endpoint đó thôi. File preview thì cứ tính ra
        // theo hồ sơ lương + attendance hiện có, có khác tháng cũ hay không là
        // trách nhiệm của HR khi review.
        try {
            byte[] bytes = salaryExportService.exportSalaryReport(
                    month, year, request.getDepartments(), request.getExportType());

            String typeSlug = switch (request.getExportType()) {
                case "BONUS_ONLY" -> "thuong";
                case "SALARY_ONLY" -> "luong";
                default -> "luong-thuong";
            };
            String filename = "bang-" + typeSlug + "-thang-" + month + "-" + year + ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[SALARY-EXPORT] error month={} year={}", month, year, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /**
     * Xuất file chi lương theo mẫu ngân hàng — chỉ OWNER/ADMIN.
     *
     * <p>File .xlsx 1 sheet {@code NHẤT NAM} với layout đúng mẫu VietinBank CN2:
     * STT · Họ tên · Số TK · Ngân hàng · Số tiền. Cột Số tiền = LƯƠNG THỰC NHẬN
     * (lương cơ bản đã trừ công + phụ cấp, KHÔNG thưởng/KPI). Cột Số TK / Ngân
     * hàng để trống, quản lý tự điền vì app không lưu thông tin ngân hàng của
     * từng nhân viên.
     *
     * <p>GET /api/factory-payroll/bank-payment-export?month=9&amp;year=2026
     */
    @GetMapping("/bank-payment-export")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> exportBankPayment(
            @RequestParam int month,
            @RequestParam int year) {
        try {
            if (month < 1 || month > 12)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Tháng phải nằm trong 1..12"));

            // KHÔNG chặn khi tháng chưa có PayrollBatch APPROVED/PENDING_APPROVAL.
            //
            // Giống "Xuất file lương tổng hợp": file NH tính theo cơ chế PREVIEW
            // — BankPaymentExportService dùng cùng công thức (hrService.
            // getSalaryBreakdownForUser cho văn phòng, factoryPayrollService.
            // driverSalaryDetail cho tài xế) để dựng số ngay từ hồ sơ lương
            // hiện hành + attendance đã upload. HR xuất ra để REVIEW số sẽ gửi
            // ngân hàng; nếu chưa chốt batch cũng không sao — file này chỉ là
            // bản làm việc, OWNER có thể quay lại điều chỉnh hồ sơ / chấm công
            // rồi xuất lại.
            //
            // Giữ các filter khác trong service (không có TK NH, chưa có hồ sơ
            // lương APPROVED, nghỉ thai sản, Q9…) — chúng vẫn chặn người không
            // thể/không nên nhận chuyển khoản.

            byte[] bytes = bankPaymentExportService.exportBankPaymentReport(month, year);
            String filename = String.format("danh-sach-chi-luong-thang-%02d-%d.xlsx", month, year);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[BANK-PAYMENT-EXPORT] error month={} year={}", month, year, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }
}