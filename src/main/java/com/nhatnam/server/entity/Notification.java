package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Thông báo lưu DB — user xem lại sau khi refresh.
 */
@Entity
@Table(name = "notification",
       indexes = {
           @Index(columnList = "target_role, created_at"),
           @Index(columnList = "user_id, created_at")
       })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Role nhận thông báo: ADMIN, ACCOUNTANT, WAREHOUSE, SELLER
     * null = dành cho 1 user cụ thể
     */
    @Column(name = "target_role", length = 30)
    private String targetRole;

    /** Nếu gửi tới 1 user cụ thể */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User targetUser;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;   // ORDER_CREATED, ORDER_DELIVERING, BATCH_PENDING, PAYMENT_UPDATED...

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    /** JSON payload tuỳ ý (orderId, batchId...) */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Builder.Default
    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
