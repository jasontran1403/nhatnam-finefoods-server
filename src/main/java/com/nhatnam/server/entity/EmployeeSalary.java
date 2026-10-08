package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Hồ sơ LƯƠNG của nhân viên — do SUPER_ACCOUNTANT/HR nhập, qua duyệt Owner.
 *
 * <p><b>QUAN TRỌNG:</b> {@code baseSalary} là LƯƠNG NET THỰC NHẬN (chưa gồm
 * phụ cấp, thưởng) — KHÔNG phải lương GROSS. Lương GROSS + breakdown bảo
 * hiểm/thuế được tính NGƯỢC từ baseSalary qua
 * {@link com.nhatnam.server.utils.PayrollTaxCalculator#calcGrossFromNet}.
 * Phụ cấp và thưởng KHÔNG đi qua bảo hiểm/thuế — cộng thẳng vào lương GROSS
 * suy ngược được để ra tổng lương cuối cùng nhân viên nhận.
 *
 * Khác với {@link Payslip} (phiếu lương theo THÁNG, có ngày công thực tế...)
 * — hồ sơ này chỉ lưu MỘT mức lương duy nhất hiện hành cho mỗi nhân viên, mỗi
 * lần cập nhật sẽ tạo bản ghi PENDING mới chờ Owner duyệt.
 */
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

    /** Lương NET thực nhận (VNĐ/tháng) — chưa gồm phụ cấp/thưởng. GROSS được tính ngược ra từ số này. */
    private Long baseSalary;

    /**
     * Mức lương đóng BHXH/BHYT/BHTN & làm căn cứ tính bảo hiểm (VNĐ/tháng).
     * Bảo hiểm (cả phần NLĐ 10.5% lẫn phần DN 21.5%) tính CỐ ĐỊNH trên mức này,
     * KHÔNG theo lương GROSS. Nếu để trống (null/0) sẽ mặc định lấy = baseSalary.
     * Thuế TNCN của người lao động vẫn tính trên lương thực (GROSS suy ngược).
     */
    @Builder.Default
    private Long insuranceSalary = 0L;

    /** Phụ cấp (ăn trưa, đi lại, …) — VNĐ/tháng. TỔNG các khoản phụ cấp (để tương thích cũ / export). */
    @Builder.Default
    private Long allowance = 0L;

    /**
     * Chi tiết từng khoản phụ cấp (nhãn + số tiền + có tính thuế TNCN không).
     * Bảo hiểm KHÔNG phụ thuộc phụ cấp (bảo hiểm tính cố định trên insuranceSalary).
     */
    @OneToMany(mappedBy = "salary", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private java.util.List<EmployeeSalaryAllowance> allowanceItems = new java.util.ArrayList<>();

    /** Thưởng cố định hàng tháng — VNĐ/tháng. Cộng thẳng vào lương cuối cùng (được nhân theo KPI khi tính). */
    @Builder.Default
    private Long bonus = 0L;

    /** Thưởng CÓ tính vào thu nhập chịu thuế TNCN hay không. */
    @Builder.Default
    private Boolean bonusTaxable = false;

    /**
     * Số người phụ thuộc — dùng để tính giảm trừ gia cảnh khi tính ngược lương
     * GROSS từ baseSalary (NET). Mỗi người phụ thuộc giảm trừ thêm 6.200.000đ/tháng
     * (xem {@link com.nhatnam.server.utils.PayrollTaxCalculator#DEPENDENT_DEDUCTION}).
     */
    @Builder.Default
    private Integer dependents = 0;

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

    /**
     * Nhân viên part-time (bán thời gian) — làm nửa buổi (4h/ngày).
     * <p>Khi tính công: part-time mỗi ngày đi làm = 0.5 công thay vì 1.0.
     * <p>Giá trị này được snapshot vào {@link AttendanceEntry#partTime} khi tính
     * lương mỗi tháng, nên khi tính lại tháng cũ vẫn giữ đúng trạng thái.
     */
    @Builder.Default
    private Boolean partTime = false;
}