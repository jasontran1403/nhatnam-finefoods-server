package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "driver")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Driver {

    public enum VehicleType { TRUCK, MOTORBIKE, BOTH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type")
    @Builder.Default
    private VehicleType vehicleType = VehicleType.BOTH;

    /**
     * TÀI KHOẢN đăng nhập gắn với tài xế này (quan hệ 1–1).
     *
     * <p>Tài xế có tài khoản (role {@code DRIVER}) sẽ thấy dashboard các đơn
     * đang giao của mình và bấm "Hoàn thành" để xác nhận đã giao.
     * Tài xế thời vụ không có tài khoản thì để {@code null}.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    /**
     * TÀI XẾ HỆ THỐNG — KHÔNG cần gắn với tài khoản đăng nhập.
     *
     * <p>Dùng cho các lựa chọn giao hàng "ảo" như <i>Giao tại kho</i>,
     * <i>Khách tự lấy</i>… Những bản ghi này bị BỎ QUA khi backfill tạo tài
     * khoản và khi đồng bộ trạng thái theo user.
     */
    @Column(name = "system_driver", nullable = false)
    @Builder.Default
    private boolean systemDriver = false;

    /** Tên gốc trước khi bị xoá mềm — dùng để khôi phục lại đúng tên cũ. */
    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
        if (vehicleType == null) vehicleType = VehicleType.BOTH;
    }
}
