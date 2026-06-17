package com.nhatnam.server.service;

import com.nhatnam.server.entity.Notification;
import com.nhatnam.server.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {
    /** Gửi thông báo tới tất cả user có role đó (lưu DB + push WS) */
    void sendToRole(String role, String eventType, String message, String payload);

    /** Gửi thông báo tới 1 user cụ thể, đúng role đang active (lưu DB + push WS) */
    void sendToUser(User user, String activeRole, String eventType, String message, String payload);

    /**
     * Gửi tới user — tự suy ra activeRole từ user.getRole().
     * Dùng khi caller không biết/không quan tâm activeRole cụ thể (broadcast-style).
     */
    default void sendToUser(User user, String eventType, String message, String payload) {
        String role = user.getRole() != null ? user.getRole().name() : "ADMIN";
        sendToUser(user, role, eventType, message, payload);
    }

    /** Lấy danh sách thông báo cho user (dựa role + userId) */
    Page<Notification> getNotifications(String role, Long userId, Pageable pageable);

    /** Đếm chưa đọc */
    long countUnread(String role, Long userId);

    /** Đánh dấu đã đọc 1 thông báo */
    void markRead(Long notificationId);

    /** Đánh dấu tất cả đã đọc */
    void markAllRead(String role, Long userId);
}
