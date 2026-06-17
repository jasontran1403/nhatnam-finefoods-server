package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.entity.Notification;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.NotificationRepository;
import com.nhatnam.server.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepo;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    @Transactional
    public void sendToRole(String role, String eventType, String message, String payload) {
        Notification n = Notification.builder()
                .targetRole(role)
                .eventType(eventType)
                .message(message)
                .payload(payload)
                .isRead(false)
                .createdAt(System.currentTimeMillis())
                .build();
        notificationRepo.save(n);

        try {
            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + role.toLowerCase(),
                    Map.of(
                            "id",        n.getId(),
                            "eventType", eventType,
                            "message",   message,
                            "payload",   payload != null ? payload : "",
                            "createdAt", n.getCreatedAt()
                    )
            );
        } catch (Exception e) {
            log.warn("[WS] Failed to push notification to role {}: {}", role, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void sendToUser(User user, String activeRole, String eventType, String message, String payload) {
        String roleName = activeRole != null ? activeRole.toUpperCase() :
                          (user.getRole() != null ? user.getRole().name() : "ADMIN");

        Notification n = Notification.builder()
                .targetUser(user)
                .targetRole(roleName)          // lưu role để query đúng khi FE đổi role
                .eventType(eventType)
                .message(message)
                .payload(payload)
                .isRead(false)
                .createdAt(System.currentTimeMillis())
                .build();
        notificationRepo.save(n);

        try {
            Map<String, Object> body = Map.of(
                    "id",           n.getId(),
                    "eventType",    eventType,
                    "message",      message,
                    "payload",      payload != null ? payload : "",
                    "createdAt",    n.getCreatedAt(),
                    "targetUserId", user.getId(),
                    "targetRole",   roleName
            );

            // Push chỉ vào topic role/userId — FE subscribe đúng topic khi đang dùng role đó
            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + roleName.toLowerCase() + "/" + user.getId(), body);
        } catch (Exception e) {
            log.warn("[WS] Failed to push notification to user {}: {}", user.getId(), e.getMessage());
        }
    }

    @Override
    public Page<Notification> getNotifications(String role, Long userId, Pageable pageable) {
        return notificationRepo.findForUser(role, userId, pageable);
    }

    @Override
    public long countUnread(String role, Long userId) {
        return notificationRepo.countUnread(role, userId);
    }

    @Override
    @Transactional
    public void markRead(Long notificationId) {
        notificationRepo.markAsRead(notificationId);
    }

    @Override
    @Transactional
    public void markAllRead(String role, Long userId) {
        notificationRepo.markAllReadForUser(role, userId);
    }
}
