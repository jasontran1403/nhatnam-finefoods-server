package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * SNAPSHOT THÂM NIÊN của 1 nhân viên trong 1 KỲ LƯƠNG (tháng/năm).
 *
 * <h3>Vì sao phải lưu chứ không tính lại mỗi lần đọc</h3>
 * Thâm niên phụ thuộc vào MỐC THỜI GIAN chốt sổ. Nếu mỗi lần mở phiếu lương lại
 * lấy "hôm nay" làm mốc thì phiếu lương tháng 3 xem vào tháng 9 sẽ hiện số năm
 * khác — phiếu lương đã chốt mà con số tự đổi là không chấp nhận được.
 *
 * <p>Vì vậy khi OWNER bấm <b>Hoàn tất</b> phiếu lương của tháng, hệ thống chốt
 * lại toàn bộ: ngày vào làm, ngày chốt, số năm, % và số tiền. Về sau đọc lại là
 * đọc đúng bản chốt đó, bất kể xem lúc nào.
 *
 * <p>Mở lại tháng ({@code reopen}) rồi Hoàn tất lần nữa thì bản ghi được GHI ĐÈ
 * theo mốc chốt mới — đúng ý nghĩa "chốt lại sổ".
 *
 * @see com.nhatnam.server.utils.SeniorityCalculator
 */
@Entity
@Table(
        name = "employee_seniority",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_employee_seniority_user_period",
                columnNames = {"user_id", "month", "year"}
        ),
        indexes = @Index(name = "idx_employee_seniority_period", columnList = "month,year")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeSeniority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    /** Ngày vào làm tại thời điểm chốt (snapshot — hồ sơ sửa sau không làm lệch sổ cũ). */
    @Column(name = "work_start_date")
    private Long workStartDate;

    /**
     * NGÀY CHỐT dùng làm mốc đếm năm — là ngày OWNER bấm Hoàn tất chấm công
     * của tháng ({@code AttendanceSheet.finalizedAt}).
     */
    @Column(name = "reference_date")
    private Long referenceDate;

    /** Số năm thâm niên TRÒN (0, 1, 2, …) — không lấy phần lẻ. */
    @Column(name = "years", nullable = false)
    @Builder.Default
    private Integer years = 0;

    /** % phụ cấp tương ứng (0, hoặc 2..10). */
    @Column(name = "percent", nullable = false)
    @Builder.Default
    private Integer percent = 0;

    /** Lương cơ bản CHUẨN dùng làm gốc nhân % (snapshot tại lúc chốt). */
    @Column(name = "base_salary")
    @Builder.Default
    private Long baseSalary = 0L;

    /** Số tiền phụ cấp thâm niên = baseSalary × percent / 100. */
    @Column(name = "amount", nullable = false)
    @Builder.Default
    private Long amount = 0L;

    @Column(name = "computed_at")
    private Long computedAt;

    @Column(name = "computed_by_name", length = 200)
    private String computedByName;

    @PrePersist
    @PreUpdate
    void touch() {
        if (computedAt == null) computedAt = System.currentTimeMillis();
        if (years   == null) years   = 0;
        if (percent == null) percent = 0;
        if (amount  == null) amount  = 0L;
    }
}
