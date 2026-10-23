package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * THƯỞNG CHUYÊN CẦN per-user per-month (10/2026).
 * OWNER nhập trực tiếp qua modal "Thưởng chuyên cần" trên trang Bảng chấm công.
 * Giá trị được cộng vào file thưởng mỗi phòng (sau Bonus, trước Tạm ứng).
 */
@Data @Builder
@NoArgsConstructor @AllArgsConstructor
@Entity
@Table(name = "attendance_bonus",
        uniqueConstraints = @UniqueConstraint(name = "uk_att_bonus_user_month",
                columnNames = {"user_id", "month", "year"}))
public class AttendanceBonus {

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