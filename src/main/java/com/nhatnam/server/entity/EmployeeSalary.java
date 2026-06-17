package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "employee_salary")
public class EmployeeSalary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nhân viên được áp dụng lương */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Lương cơ bản (VNĐ/tháng) */
    private Long baseSalary;

    /** Tỷ lệ BHXH người lao động đóng (%, VD: 8.0) */
    private Double socialInsuranceRate;

    /** Mức lương đóng BHXH (VNĐ/tháng) */
    private Long socialInsuranceSalary;

    /** Bonus tháng */
    private Long bonus;

    /** Phụ cấp cơm (VNĐ/ngày) */
    private Long mealAllowance;

    /** Phụ cấp xăng (VNĐ/tháng) */
    private Long transportAllowance;

    /**
     * Trạng thái duyệt: PENDING | APPROVED | REJECTED
     */
    private String status;

    /** Lý do từ chối (khi status = REJECTED) */
    @Column(columnDefinition = "TEXT")
    private String rejectReason;

    /** HR đã tạo/chỉnh sửa */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /** Owner đã duyệt/từ chối */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    private Long createdAt;
    private Long updatedAt;
}
