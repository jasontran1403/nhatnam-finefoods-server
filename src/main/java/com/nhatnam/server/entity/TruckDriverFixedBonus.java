package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * THƯỞNG CỐ ĐỊNH CHO TÀI XẾ XE TẢI per-user per-month (10/2026).
 *
 * <p>Khác với tài xế xe máy (lương = km × giá xăng + lượt × đơn giá), tài xế
 * xe tải nhận một khoản <b>cố định tháng</b> do OWNER quyết định, không phụ
 * thuộc vào số lượt / số đơn trong tháng. OWNER nhập trực tiếp trong modal
 * "Tính thưởng" ở tab Tài xế → Bảng chấm công.
 *
 * <p>User được coi là tài xế xe tải khi <code>position = "Tài xế xe tải"</code>.
 * Các cột km / xăng / lượt xe máy / thưởng xe máy / lượt xe tải trong bảng
 * thưởng sẽ hiện emdash cho nhóm này — chỉ có "Thưởng xe tải" là số cố định
 * từ bảng này.
 */
@Data @Builder
@NoArgsConstructor @AllArgsConstructor
@Entity
@Table(name = "truck_driver_fixed_bonus",
        uniqueConstraints = @UniqueConstraint(name = "uk_truck_fixed_bonus_user_month",
                columnNames = {"user_id", "month", "year"}))
public class TruckDriverFixedBonus {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    /** Số tiền thưởng cố định tháng (đồng). */
    @Column(name = "amount", nullable = false)
    @Builder.Default
    private Long amount = 0L;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = System.currentTimeMillis();
    }
}