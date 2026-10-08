package com.nhatnam.server.restcontroller.hr;

import com.nhatnam.server.dto.hr.CompanyPayrollDtos.*;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.hr.CompanyAttendanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * API quản lý TÍNH LƯƠNG CẢ CÔNG TY — Phase 2 refactor (10/2026).
 *
 * <p>Thay thế cho các endpoint {@code /api/factory-payroll/sheets/upload?department=...}
 * cũ. Bộ API này không nhận {@code department} — mọi file / lifecycle áp dụng
 * cho toàn công ty trong 1 tháng.
 *
 * <pre>
 *   GET  /api/company-payroll/status?month&year              — trạng thái + flags enable
 *
 *   # Upload file (NONE/UPLOADED mới cho upload adjustment)
 *   POST /api/company-payroll/attendance/upload?month&year   — chấm công (bắt buộc trước Tính lương)
 *   POST /api/company-payroll/exception/upload?month&year    — đi trễ / về sớm / lịch nghỉ
 *   POST /api/company-payroll/leave/upload?month&year        — đơn xin nghỉ phép
 *   DELETE /api/company-payroll/files/{kind}?month&year      — kind = ATTENDANCE | EXCEPTION | LEAVE
 *
 *   # Vòng đời
 *   POST /api/company-payroll/calculate?month&year           — UPLOADED → CALCULATED
 *   POST /api/company-payroll/publish?month&year             — CALCULATED → PUBLISHED
 *   POST /api/company-payroll/unpublish?month&year           — PUBLISHED → CALCULATED
 *   POST /api/company-payroll/reopen?month&year              — CALCULATED → UPLOADED
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/company-payroll")
@RequiredArgsConstructor
public class CompanyPayrollController {

    private final CompanyAttendanceService svc;
    private final com.nhatnam.server.service.hr.AttendanceDetailService attendanceDetailService;

    private static final String ADMIN_ROLES = "hasAnyRole('OWNER','ADMIN','HR','SUPER_ACCOUNTANT')";

    // ══════════════════════════════════════════════════════════════════════════
    // STATUS
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/status")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> status(
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(svc.getStatus(month, year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] status {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 6 — CHUYÊN CẦN (matrix đi trễ / về sớm theo ngày)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Trả về matrix dữ liệu chuyên cần cho 1 tháng.
     *
     * <p>Chỉ enable khi tháng đã có file chấm công VÀ đã bấm Tính lương
     * (calcStatus ≥ CALCULATED). FE kiểm tra từ {@code status.canChuyenCan}.
     */
    @GetMapping("/attendance-detail")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<com.nhatnam.server.dto.hr.AttendanceDetailDtos.AttendanceDetailResponse>>
    attendanceDetail(@RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    attendanceDetailService.getDetail(month, year),
                    "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] attendance-detail {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UPLOAD
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping(value = "/attendance/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UploadResultDto>> uploadAttendance(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file,
            @RequestParam int month, @RequestParam int year) {
        try {
            UploadResultDto r = svc.uploadAttendance(file, month, year, user);
            return ResponseEntity.ok(ApiResponse.success(r,
                    "Đã tải lên bảng chấm công tháng %d/%d (khớp %d/%d nhân viên)"
                            .formatted(month, year, r.getMatchedUsers(), r.getParsedRows())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] uploadAttendance {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/exception/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UploadResultDto>> uploadException(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file,
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    svc.uploadException(file, month, year, user),
                    "Đã tải lên lịch nghỉ / đi trễ / về sớm tháng %d/%d".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] uploadException {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/leave/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UploadResultDto>> uploadLeave(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file,
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    svc.uploadLeave(file, month, year, user),
                    "Đã tải lên đơn xin nghỉ phép tháng %d/%d".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] uploadLeave {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/bonus/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UploadResultDto>> uploadBonus(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file,
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    svc.uploadBonus(file, month, year, user),
                    "Đã import thưởng tháng %d/%d".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] uploadBonus {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/allowance/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<UploadResultDto>> uploadAllowance(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file,
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    svc.uploadAllowance(file, month, year, user),
                    "Đã import phụ cấp tháng %d/%d".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] uploadAllowance {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @DeleteMapping("/files/{kind}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> deleteFile(
            @PathVariable String kind,
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    svc.deleteFile(kind, month, year),
                    "Đã xoá file " + kind + " tháng %d/%d".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] deleteFile {} {}/{}", kind, month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VÒNG ĐỜI
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/calculate")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CalculateResultDto>> calculate(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        try {
            CalculateResultDto r = svc.calculate(month, year, user);
            return ResponseEntity.ok(ApiResponse.success(r,
                    "Đã tính lương tháng %d/%d — %d nhân viên, OT %,dđ"
                            .formatted(month, year, r.getEmployeesComputed(), r.getOtTotalAmount())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] calculate {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/publish")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> publish(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    svc.publish(month, year, user),
                    "Đã Public lương tháng %d/%d cho nhân viên xem".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] publish {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/unpublish")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> unpublish(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    svc.unpublish(month, year, user),
                    "Đã Unpublic lương tháng %d/%d".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] unpublish {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/reopen")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> reopen(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    svc.reopen(month, year, user),
                    "Đã Mở lại tháng %d/%d — có thể upload/import lại".formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] reopen {}/{}", month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 4 — LIFECYCLE KPI (xưởng) + BONUS (sales/accounting)
    //
    // Áp dụng cùng pattern Calculate/Public/Unpublic/Reopen cho 2 luồng này.
    // Phần tính toán nghiệp vụ KPI/Bonus vẫn do FactoryKpiService/OfficeBonus
    // Service đảm nhiệm — các endpoint dưới đây chỉ chuyển trạng thái sheet.
    //
    // KPI endpoint: /kpi/calculate · /kpi/publish · /kpi/unpublish · /kpi/reopen
    // Bonus endpoint:/bonus/calculate · /bonus/publish · /bonus/unpublish · /bonus/reopen
    //
    // FE Phase 3 panel KPI + Bonus (chưa viết, scope Phase 4+) gọi 4 endpoint
    // theo đúng cùng cách CompanyPayrollPanel đang dùng cho lifecycle lương.
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/kpi/calculate")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> kpiCalculate(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        return wrap(() -> svc.kpiCalculate(month, year, user),
                month, year, "KPI", "Đã tính KPI xưởng tháng %d/%d");
    }

    @PostMapping("/kpi/publish")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> kpiPublish(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        return wrap(() -> svc.kpiPublish(month, year, user),
                month, year, "KPI publish", "Đã Public KPI xưởng tháng %d/%d");
    }

    @PostMapping("/kpi/unpublish")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> kpiUnpublish(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        return wrap(() -> svc.kpiUnpublish(month, year, user),
                month, year, "KPI unpublish", "Đã Unpublic KPI xưởng tháng %d/%d");
    }

    @PostMapping("/kpi/reopen")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> kpiReopen(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        return wrap(() -> svc.kpiReopen(month, year, user),
                month, year, "KPI reopen", "Đã Mở lại KPI xưởng tháng %d/%d");
    }

    @PostMapping("/bonus/calculate")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> bonusCalculate(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        return wrap(() -> svc.bonusCalculate(month, year, user),
                month, year, "Bonus", "Đã tính Thưởng DT tháng %d/%d");
    }

    @PostMapping("/bonus/publish")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> bonusPublish(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        return wrap(() -> svc.bonusPublish(month, year, user),
                month, year, "Bonus publish", "Đã Public Thưởng DT tháng %d/%d");
    }

    @PostMapping("/bonus/unpublish")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> bonusUnpublish(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        return wrap(() -> svc.bonusUnpublish(month, year, user),
                month, year, "Bonus unpublish", "Đã Unpublic Thưởng DT tháng %d/%d");
    }

    @PostMapping("/bonus/reopen")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> bonusReopen(
            @AuthenticationPrincipal User user,
            @RequestParam int month, @RequestParam int year) {
        return wrap(() -> svc.bonusReopen(month, year, user),
                month, year, "Bonus reopen", "Đã Mở lại Thưởng DT tháng %d/%d");
    }

    /** Wrapper gọi service + trả ApiResponse — gom try/catch chung cho 8 endpoint trên. */
    private ResponseEntity<ApiResponse<CompanyPeriodStatusDto>> wrap(
            java.util.concurrent.Callable<CompanyPeriodStatusDto> action,
            int month, int year, String logTag, String successFmt) {
        try {
            return ResponseEntity.ok(ApiResponse.success(action.call(),
                    successFmt.formatted(month, year)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[CompanyPayroll] {} {}/{}", logTag, month, year, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}