package com.nhatnam.server.restcontroller;

import com.nhatnam.server.config.JwtService;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.Notification;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Log4j2
public class NotificationController {

    private final NotificationService notificationService;
    private final JwtService jwtService;

    /** Lấy active role từ JWT (selected_role claim), fallback về user.role */
    private String getActiveRole(Authentication auth, HttpServletRequest request) {
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String jwt = authHeader.substring(7);
                String selectedRole = jwtService.extractSelectedRole(jwt);
                if (selectedRole != null && !selectedRole.isBlank()) return selectedRole.toUpperCase();
            }
        } catch (Exception ignored) {}
        // Fallback
        User user = (User) auth.getPrincipal();
        return user.getRole() != null ? user.getRole().name() : "ADMIN";
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNotifications(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth,
            HttpServletRequest request) {
        try {
            User user = (User) auth.getPrincipal();
            String role = getActiveRole(auth, request);
            Page<Notification> result = notificationService.getNotifications(
                    role, user.getId(),
                    PageRequest.of(page, size, Sort.by("createdAt").descending()));

            List<Map<String, Object>> content = result.getContent().stream().map(n -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",        n.getId());
                m.put("eventType", n.getEventType());
                m.put("message",   n.getMessage());
                m.put("payload",   n.getPayload());
                m.put("isRead",    n.getIsRead());
                m.put("createdAt", n.getCreatedAt());
                return m;
            }).collect(Collectors.toList());

            long unread = notificationService.countUnread(role, user.getId());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content",     content);
            data.put("totalItems",  result.getTotalElements());
            data.put("totalPages",  result.getTotalPages());
            data.put("currentPage", page);
            data.put("unreadCount", unread);

            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUnreadCount(
            Authentication auth, HttpServletRequest request) {
        try {
            User user = (User) auth.getPrincipal();
            String role = getActiveRole(auth, request);
            long count = notificationService.countUnread(role, user.getId());
            return ResponseEntity.ok(ApiResponse.success(Map.of("count", count), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Object>> markRead(@PathVariable Long id) {
        try {
            notificationService.markRead(id);
            return ResponseEntity.ok(ApiResponse.success(null, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Object>> markAllRead(
            Authentication auth, HttpServletRequest request) {
        try {
            User user = (User) auth.getPrincipal();
            String role = getActiveRole(auth, request);
            notificationService.markAllRead(role, user.getId());
            return ResponseEntity.ok(ApiResponse.success(null, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}
