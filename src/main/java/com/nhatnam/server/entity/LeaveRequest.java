package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "leave_request")
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nhân viên xin nghỉ */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** PAID | UNPAID */
    private String leaveType;

    /** Ngày bắt đầu nghỉ (epoch ms, 00:00 của ngày) */
    private Long leaveDate;

    /** Ngày kết thúc nghỉ (epoch ms, 23:59 của ngày) */
    private Long leaveEndDate;

    /** Số ngày nghỉ */
    private Double leaveDays;

    /** Bàn giao công việc cho ai (tên tự do) */
    private String handoverTo;

    /** SĐT liên lạc khi cần */
    private String contactPhone;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** HR tạo đơn */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    private Long createdAt;
}
