package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.UserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API profile dùng chung cho tất cả các role.
 * - GET  /api/profile        — xem thông tin cá nhân
 * - PUT  /api/profile        — cập nhật email, số điện thoại
 * - PUT  /api/profile/password — đổi mật khẩu
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@Log4j2
public class ProfileController {

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;

    // ── GET: Xem thông tin cá nhân ────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProfile(Authentication auth) {
        User user = resolveUser(auth);
        if (user == null)
            return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Chưa đăng nhập"));
        return ResponseEntity.ok(ApiResponse.success(toMap(user), "OK"));
    }

    // ── PUT: Cập nhật email, số điện thoại ────────────────────────────────────
    @PutMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(
            @RequestBody UpdateProfileRequest req,
            Authentication auth) {
        try {
            User user = resolveUser(auth);
            if (user == null)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Chưa đăng nhập"));

            // Validate email format nếu có
            if (req.getEmail() != null && !req.getEmail().isBlank()) {
                if (!req.getEmail().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
                    return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Email không hợp lệ"));

                // Kiểm tra email trùng với user khác
                userRepository.findByEmail(req.getEmail().trim()).ifPresent(existing -> {
                    if (existing.getId() != user.getId())
                        throw new RuntimeException("Email đã được sử dụng bởi tài khoản khác");
                });
                user.setEmail(req.getEmail().trim());
            } else if (req.getEmail() != null && req.getEmail().isBlank()) {
                user.setEmail(null); // cho phép xóa email
            }

            if (req.getFullName() != null && !req.getFullName().isBlank()) {
                user.setFullName(req.getFullName().trim());
            }

            if (req.getPhoneNumber() != null) {
                String phone = req.getPhoneNumber().trim();
                if (!phone.isBlank()) {
                    // Kiểm tra SĐT trùng với user khác
                    userRepository.findByPhoneNumber(phone).ifPresent(existing -> {
                        if (existing.getId() != user.getId())
                            throw new RuntimeException("Số điện thoại đã được sử dụng bởi tài khoản khác");
                    });
                    user.setPhoneNumber(phone);
                } else {
                    user.setPhoneNumber(null);
                }
            }

            userRepository.save(user);
            return ResponseEntity.ok(ApiResponse.success(toMap(user), "Cập nhật thành công"));

        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("updateProfile error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── PUT: Đổi mật khẩu ────────────────────────────────────────────────────
    @PutMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @RequestBody ChangePasswordRequest req,
            Authentication auth) {
        try {
            User user = resolveUser(auth);
            if (user == null)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Chưa đăng nhập"));

            // Validate
            if (req.getCurrentPassword() == null || req.getCurrentPassword().isBlank())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Vui lòng nhập mật khẩu hiện tại"));

            if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPassword()))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Mật khẩu hiện tại không đúng"));

            if (req.getNewPassword() == null || req.getNewPassword().length() < 6)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Mật khẩu mới phải tối thiểu 6 ký tự"));

            if (req.getNewPassword().equals(req.getCurrentPassword()))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Mật khẩu mới phải khác mật khẩu hiện tại"));

            user.setPassword(passwordEncoder.encode(req.getNewPassword()));
            userRepository.save(user);

            return ResponseEntity.ok(ApiResponse.success(null, "Đổi mật khẩu thành công"));

        } catch (Exception e) {
            log.error("changePassword error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User resolveUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (!(principal instanceof User u)) return null;
        // Reload từ DB để có data mới nhất
        return userRepository.findById(u.getId()).orElse(null);
    }

    private Map<String, Object> toMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          u.getId());
        m.put("username",    u.getUsername());
        m.put("fullName",    u.getFullName());
        m.put("email",       u.getEmail());
        m.put("phoneNumber", u.getPhoneNumber());
        m.put("role",        u.getRole() != null ? u.getRole().name() : null);
        m.put("roles",       u.getAllRoles().stream().map(Enum::name).toList());
        return m;
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    @Data
    public static class UpdateProfileRequest {
        private String email;
        private String phoneNumber;
        private String fullName;
    }

    @Data
    public static class ChangePasswordRequest {
        private String currentPassword;
        private String newPassword;
    }
}