// src/main/java/com/nhatnam/server/entity/OrderLog.java
package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "order_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Tên action: CREATED, DELIVERING, PENDING_PAYMENT, COMPLETED, CANCELLED,
     *  PAYMENT_METHOD_UPDATED, DEADLINE_EXTENDED */
    @Column(name = "action", length = 50, nullable = false)
    private String action;

    /** Tên đầy đủ của người thực hiện */
    @Column(name = "actor_name", length = 100)
    private String actorName;

    /** Role: SELLER, WAREHOUSE, ACCOUNTANT, ADMIN */
    @Column(name = "actor_role", length = 30)
    private String actorRole;

    /** Ghi chú thêm (lý do hủy, số ngày gia hạn, ...) */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
    }
}