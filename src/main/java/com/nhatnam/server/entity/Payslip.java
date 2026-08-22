package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Phiếu lương chi tiết của 1 nhân viên trong 1 {@link PayrollBatch}.
 * Lưu toàn bộ breakdown để khi cần in/tải lại không phải tính lại — đảm bảo
 * số liệu ổn định dù sau này có nhân viên đổi lương cơ bản, đổi số người phụ
 * thuộc, v.v.
 */
@Entity
@Table(name = "payslip")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payslip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private PayrollBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // ── Snapshot thông tin nhân viên tại thời điểm tính lương ──────────────────
    @Column(name = "user_full_name")
    private String userFullName;
    private String department;
    private String division;
    private String position;

    // ── Input từ file import ───────────────────────────────────────────────────
    /** Lương trước thuế / GROSS cơ bản theo hồ sơ đã duyệt (VNĐ/tháng) */
    private Long baseSalary;

    /** Số người phụ thuộc (giảm trừ gia cảnh) */
    @Builder.Default
    private Integer dependents = 0;

    /** Số ngày công chuẩn trong tháng (T2-T6 = 1 công, T7 = 0.5 công) */
    private Double standardWorkdays;

    /** Số ngày công thực tế đi làm (nhập tay, đã trừ nghỉ không phép) */
    private Double actualWorkdays;

    /** Thưởng / bonus tháng này (VNĐ) */
    @Builder.Default
    private Long bonus = 0L;

    /** Phụ cấp (ăn trưa, đi lại, …) tháng này (VNĐ) */
    @Builder.Default
    private Long allowance = 0L;

    /** Lương dùng làm căn cứ đóng BHXH/BHYT/BHTN (VNĐ/tháng, nhập tay — có thể khác lương cơ bản) */
    private Long insuranceSalary;

    // ── Kết quả tính toán ───────────────────────────────────────────────────────
    /** Lương theo ngày công thực tế = baseSalary / standardWorkdays * actualWorkdays */
    private Long actualSalary;

    /** Lương GROSS tháng này = actualSalary + bonus + allowance */
    private Long grossSalary;

    /** Bảo hiểm xã hội NLĐ đóng (8% insuranceSalary) */
    private Long socialInsuranceAmount;

    /** Bảo hiểm y tế NLĐ đóng (1.5% insuranceSalary) */
    private Long healthInsuranceAmount;

    /** Bảo hiểm thất nghiệp NLĐ đóng (1% insuranceSalary) */
    private Long unemploymentInsuranceAmount;

    /** Tổng 3 khoản bảo hiểm NLĐ đóng */
    private Long totalInsuranceAmount;

    /** Thu nhập trước thuế = grossSalary - totalInsuranceAmount */
    private Long preTaxIncome;

    /** Giảm trừ gia cảnh bản thân (15.500.000đ/tháng theo luật 2026) */
    private Long personalDeduction;

    /** Giảm trừ gia cảnh người phụ thuộc = dependents x 6.200.000đ/tháng */
    private Long dependentDeduction;

    /** Thu nhập tính thuế = preTaxIncome - personalDeduction - dependentDeduction (tối thiểu 0) */
    private Long taxableIncome;

    /** Thuế TNCN phải nộp (theo biểu thuế lũy tiến 5 bậc 2026) */
    private Long personalIncomeTax;

    /** Lương thực nhận (NET) = preTaxIncome - personalIncomeTax */
    private Long netSalary;

    private Long createdAt;
    private Long updatedAt;
}
