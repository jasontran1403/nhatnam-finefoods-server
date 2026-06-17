package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Lịch làm việc của máy (Mon-Sat).
 * Mỗi máy có 1 schedule: activeWeekdays (JSON "1,2,3,4,5,6"), startHour, endHour.
 */
@Entity
@Table(name = "machine_work_schedule")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MachineWorkSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id", nullable = false, unique = true)
    private Machine machine;

    /** Các ngày làm việc trong tuần (1=Mon..7=Sun), JSON array e.g. "[1,2,3,4,5,6]" */
    @Column(name = "active_weekdays", length = 50)
    @Builder.Default
    private String activeWeekdays = "[1,2,3,4,5,6]";

    /** Giờ bắt đầu ca (0-23) */
    @Column(name = "start_hour")
    @Builder.Default
    private int startHour = 7;

    /** Giờ kết thúc ca (0-23) */
    @Column(name = "end_hour")
    @Builder.Default
    private int endHour = 17;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist @PreUpdate void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
