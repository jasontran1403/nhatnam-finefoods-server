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
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepo;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * ROLE KHÔNG NHẬN PUSH WEBSOCKET cho các sự kiện đơn hàng.
     *
     * <p>OWNER/ADMIN theo dõi toàn bộ đơn của công ty. Ở quy mô vài chục đơn mỗi ngày,
     * mỗi lần tạo đơn / đổi hình thức thanh toán / lập phiếu thu đều bắn toast thì màn
     * hình của họ liên tục bị che, và đúng cái toast quan trọng lại bị bỏ qua vì đã
     * quen tay tắt.
     */
    private static final Set<String> SILENT_ROLES_FOR_ORDER_EVENTS = Set.of("OWNER", "ADMIN");

    /**
     * SỰ KIỆN ĐƠN HÀNG — vẫn LƯU thông báo vào DB (OWNER/ADMIN mở chuông là thấy đủ),
     * chỉ bỏ bước đẩy WebSocket nên không nổi toast.
     *
     * <p>Lọc theo tiền tố thay vì liệt kê từng mã: các sự kiện đơn hàng đều bắt đầu bằng
     * {@code ORDER_} hoặc {@code PAYMENT_}, và sự kiện mới thêm sau này sẽ tự động được
     * áp dụng mà không ai phải nhớ quay lại sửa danh sách ở đây.
     */
    private static boolean isOrderEvent(String eventType) {
        if (eventType == null) return false;
        return eventType.startsWith("ORDER_") || eventType.startsWith("PAYMENT_");
    }

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

        // Thông báo đã nằm trong DB. Với sự kiện đơn hàng gửi cho OWNER/ADMIN thì dừng
        // ở đây — không push WS nên FE không hiện toast, nhưng chuông vẫn đếm và danh
        // sách thông báo vẫn đầy đủ.
        if (isOrderEvent(eventType)
                && SILENT_ROLES_FOR_ORDER_EVENTS.contains(role.toUpperCase())) {
            log.debug("[WS] Bỏ qua push {} cho role {} (sự kiện đơn hàng)", eventType, role);
            return;
        }

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

        // Cùng lý do như sendToRole: OWNER/ADMIN không nhận toast cho sự kiện đơn hàng.
        if (isOrderEvent(eventType)
                && SILENT_ROLES_FOR_ORDER_EVENTS.contains(roleName)) {
            log.debug("[WS] Bỏ qua push {} cho user {} role {} (sự kiện đơn hàng)",
                    eventType, user.getId(), roleName);
            return;
        }

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
