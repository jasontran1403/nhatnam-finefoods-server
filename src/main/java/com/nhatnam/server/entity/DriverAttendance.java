package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Lưu điểm danh odo của tài xế mỗi ngày.
 * Mỗi record = 1 lần nhập odo (vào ca hoặc kết ca) cho 1 tài xế + 1 loại xe.
 * Tài xế BOTH sẽ có 2 record riêng (MOTORBIKE + TRUCK) mỗi lần điểm danh.
 */
@Entity
@Table(name = "driver_attendance",
       indexes = {
           @Index(name = "idx_da_date_session", columnList = "attendance_date, session_type"),
           @Index(name = "idx_da_driver", columnList = "driver_id")
       })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DriverAttendance {

    public enum SessionType { START, END }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;

    /** Ngày điểm danh dạng "yyyy-MM-dd" */
    @Column(name = "attendance_date", nullable = false, length = 10)
    private String attendanceDate;

    /** Vào ca = START, Kết ca = END */
    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 10)
    private SessionType sessionType;

    /**
     * Loại xe đang dùng cho lần điểm danh này.
     * Tài xế BOTH sẽ có 2 record: MOTORBIKE + TRUCK.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 10)
    private Driver.VehicleType vehicleType;

    /** Số km ODO (không decimal) */
    @Column(name = "odometer", nullable = false)
    private Integer odometer;

    /** Người nhập (tên NV kho) */
    @Column(name = "recorded_by", length = 100)
    private String recordedBy;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @Column(name = "note", length = 500)
    private String note;

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
