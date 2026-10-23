package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * THƯỞNG CỨNG CHO NV KHO (10/2026).
 *
 * <p>Phòng Kho không có flow tính thưởng tự động — OWNER nhập trực tiếp số tiền
 * thưởng cho mỗi NV theo từng tháng trên trang "Biến động thưởng".
 */
@Data @Builder
@NoArgsConstructor @AllArgsConstructor
@Entity
@Table(name = "warehouse_bonus",
        uniqueConstraints = @UniqueConstraint(name = "uk_wh_bonus_user_month",
                columnNames = {"user_id", "month", "year"}))
public class WarehouseBonus {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "amount", nullable = false)
    @Builder.Default
    private Long amount = 0L;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @Column(name = "updated_by_name", length = 200)
    private String updatedByName;

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }
}