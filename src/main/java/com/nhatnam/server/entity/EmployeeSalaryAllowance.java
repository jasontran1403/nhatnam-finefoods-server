package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Một khoản PHỤ CẤP cụ thể trong hồ sơ lương của nhân viên
 * (vd: "Phụ cấp cơm trưa" = 450.000, không tính thuế).
 *
 * <p>Mỗi khoản có cờ {@code taxable}: khoản này CÓ tính vào thu nhập chịu thuế
 * TNCN hay không. Bảo hiểm KHÔNG phụ thuộc phụ cấp — bảo hiểm luôn tính cố định
 * trên "lương đóng bảo hiểm" mà SUPER_ACCOUNTANT nhập.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "employee_salary_allowance")
public class EmployeeSalaryAllowance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salary_id", nullable = false)
    private EmployeeSalary salary;

    /** Nhãn phụ cấp (vd "Phụ cấp cơm trưa"). */
    private String label;

    /** Số tiền phụ cấp (VNĐ/tháng). */
    @Builder.Default
    private Long amount = 0L;

    /** Có tính vào thu nhập chịu thuế TNCN hay không. */
    @Builder.Default
    private boolean taxable = false;
}