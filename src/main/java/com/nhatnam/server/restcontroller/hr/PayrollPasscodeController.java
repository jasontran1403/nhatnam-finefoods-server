package com.nhatnam.server.restcontroller.hr;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.hr.PayrollPasscodeService;
import com.nhatnam.server.service.hr.PayrollPasscodeService.PasscodeLockedException;
import com.nhatnam.server.service.hr.PayrollPasscodeService.WrongPasscodeException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API MẬT KHẨU XEM LƯƠNG (passcode 6 số).
 *
 * <pre>
 *  ── Nhân viên ───────────────────────────────────────────────────────────────
 *  GET    /api/payroll-passcode/status   → còn "vé" không / đã khoá chưa / còn mấy lần
 *  POST   /api/payroll-passcode/verify   → nhập 6 số, đúng thì cấp vé 15 phút
 *  PUT    /api/payroll-passcode          → đổi passcode (cũ + mới + xác nhận)
 *  POST   /api/payroll-passcode/lock     → chủ động huỷ vé (rời trang / khoá tay)
 *
 *  ── OWNER / ADMIN / HR / SUPERADMIN ─────────────────────────────────────────
 *  GET    /api/payroll-passcode/locked-users        → ai đang bị khoá
 *  POST   /api/payroll-passcode/{userId}/unlock     → mở khoá (?reset=true để về 000000)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/payroll-passcode")
@RequiredArgsConstructor
public class PayrollPasscodeController {

    private static final String ADMIN_ROLES =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN','HR')";

    private final PayrollPasscodeService service;

    // ══════════════════════════════════════════════════════════════════════════
    // NHÂN VIÊN
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal User user) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.status(user), "OK"));
        } catch (Exception e) {
            log.error("[PayrollPasscode] Lỗi lấy trạng thái", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal User user,
            @RequestBody VerifyRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    service.verify(user, req.getPasscode()), "Xác thực thành công"));

        } catch (PasscodeLockedException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.PAYROLL_PASSCODE_LOCKED,
                    Map.<String, Object>of("locked", true, "remainingAttempts", 0),
                    e.getMessage()));

        } catch (WrongPasscodeException e) {
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.PAYROLL_PASSCODE_WRONG,
                    Map.<String, Object>of("locked", false, "remainingAttempts", e.getRemainingAttempts()),
                    e.getMessage()));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));

        } catch (Exception e) {
            log.error("[PayrollPasscode] Lỗi xác thực", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Void>> change(
            @AuthenticationPrincipal User user,
            @RequestBody ChangeRequest req) {
        try {
            service.changePasscode(user,
                    req.getCurrentPasscode(), req.getNewPasscode(), req.getConfirmPasscode());
            return ResponseEntity.ok(ApiResponse.success(null, "Đổi mật khẩu xem lương thành công"));

        } catch (PasscodeLockedException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.PAYROLL_PASSCODE_LOCKED, e.getMessage()));

        } catch (WrongPasscodeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.PAYROLL_PASSCODE_WRONG, e.getMessage()));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));

        } catch (Exception e) {
            log.error("[PayrollPasscode] Lỗi đổi passcode", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Huỷ vé chủ động — FE gọi khi rời trang lương. */
    @PostMapping("/lock")
    public ResponseEntity<ApiResponse<Void>> lock(@AuthenticationPrincipal User user) {
        try {
            service.revokeAccess(user);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã khoá lại"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // QUẢN TRỊ
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/locked-users")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> lockedUsers() {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.lockedUsers(), "OK"));
        } catch (Exception e) {
            log.error("[PayrollPasscode] Lỗi lấy danh sách khoá", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/{userId}/unlock")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<Map<String, Object>>> unlock(
            @AuthenticationPrincipal User actor,
            @PathVariable long userId,
            @RequestParam(defaultValue = "true") boolean reset) {
        try {
            String actorName = actor != null
                    ? (actor.getFullName() != null ? actor.getFullName() : actor.getUsername())
                    : "?";
            return ResponseEntity.ok(ApiResponse.success(
                    service.unlock(userId, reset, actorName),
                    reset ? "Đã mở khoá và đặt lại mật khẩu xem lương về 000000"
                          : "Đã mở khoá xem lương"));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("[PayrollPasscode] Lỗi mở khoá userId={}", userId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Request DTOs ─────────────────────────────────────────────────────────

    @Data
    public static class VerifyRequest {
        private String passcode;
    }

    @Data
    public static class ChangeRequest {
        private String currentPasscode;
        private String newPasscode;
        private String confirmPasscode;
    }
}
