package com.nhatnam.server.restcontroller.hr;

import com.nhatnam.server.dto.hr.EmployeeRequestDtos.*;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.EmployeeRequestStatus;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.hr.EmployeeRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * API "PHIẾU NGHỈ / ĐI TRỄ / VỀ SỚM / CÔNG TÁC / QUÊN CHẤM CÔNG".
 *
 * <pre>
 *  ── Mọi nhân viên đã đăng nhập ──────────────────────────────────────────────
 *  GET    /api/employee-requests/form-config     → loại phiếu + giới hạn lịch
 *  GET    /api/employee-requests/mine?page&size  → phiếu tôi đã gửi
 *  POST   /api/employee-requests                 → tạo phiếu (lý do bắt buộc)
 *  DELETE /api/employee-requests/{id}            → tự huỷ phiếu chưa duyệt
 *  GET    /api/employee-requests/{id}            → chi tiết phiếu
 *
 *  ── OWNER / ADMIN ───────────────────────────────────────────────────────────
 *  GET    /api/employee-requests?department&status&userId&from&to&page&size
 *  GET    /api/employee-requests/summary?department   → số phiếu đang chờ
 *  GET    /api/employee-requests/statuses             → danh mục trạng thái
 *  POST   /api/employee-requests/{id}/decide          → duyệt / trừ công / từ chối
 * </pre>
 *
 * <p>Lỗi nghiệp vụ ({@link IllegalArgumentException} từ service — sai cửa sổ ngày,
 * thiếu lý do, phiếu đã xử lý…) được trả về dưới dạng {@code ApiResponse.error}
 * với đúng câu tiếng Việt của service, để FE hiện thẳng lên toast thay vì phải
 * dịch mã lỗi.
 */
@Slf4j
@RestController
@RequestMapping("/api/employee-requests")
@RequiredArgsConstructor
public class EmployeeRequestController {

    private final EmployeeRequestService service;

    // ══════════════════════════════════════════════════════════════════════════
    // NHÂN VIÊN
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/form-config")
    public ResponseEntity<ApiResponse<RequestFormConfigDto>> formConfig() {
        return ResponseEntity.ok(ApiResponse.success(service.formConfig(), "OK"));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EmployeeRequestDto>> create(
            @AuthenticationPrincipal User me,
            @Valid @RequestBody CreateRequestDto body) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    service.create(me, body), "Đã gửi phiếu, chờ duyệt."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<Page<EmployeeRequestDto>>> mine(
            @AuthenticationPrincipal User me,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.myRequests(me, page, size), "OK"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> cancel(@AuthenticationPrincipal User me,
                                                    @PathVariable Long id) {
        try {
            service.cancel(me, id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã huỷ phiếu."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EmployeeRequestDto>> detail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.detail(id), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OWNER / ADMIN
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<Page<EmployeeRequestDto>>> search(
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PayrollDepartment dept = PayrollDepartment.parse(department);
        EmployeeRequestStatus st = parseStatus(status);

        return ResponseEntity.ok(ApiResponse.success(
                service.searchForOwner(dept, st, userId, from, to, page, size), "OK"));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<RequestSummaryDto>> summary(
            @RequestParam(required = false) String department) {
        return ResponseEntity.ok(ApiResponse.success(
                service.summary(PayrollDepartment.parse(department)), "OK"));
    }

    /** Ba thao tác của OWNER gộp vào một endpoint, phân nhánh bằng {@code action}. */
    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<EmployeeRequestDto>> decide(
            @AuthenticationPrincipal User owner,
            @PathVariable Long id,
            @Valid @RequestBody DecideRequestDto body) {
        try {
            EmployeeRequestDto dto = service.decide(owner, id, body);
            return ResponseEntity.ok(ApiResponse.success(dto, "Đã cập nhật: " + dto.getStatusLabel()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /** Danh mục trạng thái cho bộ lọc của panel. */
    // ══════════════════════════════════════════════════════════════════════════
    //  QUỸ NGÀY PHÉP
    // ══════════════════════════════════════════════════════════════════════════

    /** Số dư phép CỦA CHÍNH MÌNH — card trên màn hình xin nghỉ phép. */
    @GetMapping("/my-leave-balance")
    public ResponseEntity<ApiResponse<LeaveBalanceDto>> myLeaveBalance(
            @AuthenticationPrincipal User me,
            @RequestParam(required = false) Integer year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.leaveBalance(me.getId(), year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /** Lịch sử nghỉ phép CỦA CHÍNH MÌNH. */
    @GetMapping("/my-leave-history")
    public ResponseEntity<ApiResponse<List<LeaveHistoryItemDto>>> myLeaveHistory(
            @AuthenticationPrincipal User me,
            @RequestParam(required = false) Integer year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.leaveHistory(me.getId(), year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /**
     * Số dư phép của MỘT NHÂN VIÊN — người duyệt cần thấy trước khi bấm duyệt.
     *
     * <p>Đây là dữ liệu nhân sự của NGƯỜI KHÁC nên chặn theo nhóm có quyền duyệt,
     * không để lộ cho mọi tài khoản đã đăng nhập.
     */
    @GetMapping("/{userId}/leave-balance")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPER_ACCOUNTANT','HR')")
    public ResponseEntity<ApiResponse<LeaveBalanceDto>> leaveBalance(
            @PathVariable Long userId,
            @RequestParam(required = false) Integer year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.leaveBalance(userId, year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /** Lịch sử nghỉ phép của MỘT NHÂN VIÊN — nút "Xem lịch sử" khi duyệt. */
    @GetMapping("/{userId}/leave-history")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPER_ACCOUNTANT','HR')")
    public ResponseEntity<ApiResponse<List<LeaveHistoryItemDto>>> leaveHistory(
            @PathVariable Long userId,
            @RequestParam(required = false) Integer year) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.leaveHistory(userId, year), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @GetMapping("/statuses")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> statuses() {
        List<Map<String, String>> list = Arrays.stream(EmployeeRequestStatus.values())
                .map(s -> Map.of("value", s.name(), "label", s.getLabel()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    private static EmployeeRequestStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return EmployeeRequestStatus.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}