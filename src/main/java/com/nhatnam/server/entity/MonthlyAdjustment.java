package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * KHOẢN THƯỞNG / PHỤ CẤP THEO THÁNG — do OWNER import bằng Excel mỗi kỳ.
 *
 * <h3>Vì sao tách khỏi hồ sơ lương</h3>
 * {@link EmployeeSalary} chứa các khoản CỐ ĐỊNH, tháng nào cũng như tháng nào.
 * Nhưng lì xì Tết, thưởng theo quy định, tiền xăng xe, điện thoại… thay đổi từng
 * tháng và từng người. Nhét chúng vào hồ sơ lương sẽ khiến kế toán phải sửa tay
 * hồ sơ mỗi tháng rồi lại quên trả về mức cũ.
 *
 * <p>Mỗi bản ghi ở đây gắn chặt với MỘT kỳ {@code (month, year)}. Import lại cùng
 * kỳ + cùng loại sẽ XOÁ SẠCH rồi ghi mới, nên file Excel luôn là nguồn sự thật
 * duy nhất cho tháng đó.
 *
 * <h3>Phân biệt với thưởng KPI xưởng</h3>
 * Thưởng KPI ({@link FactoryKpiBonus}) do hệ thống TỰ TÍNH từ sản lượng, không
 * nằm ở bảng này. Khoản {@code BONUS} ở đây là khoản CỘNG THÊM, độc lập hoàn toàn.
 */
@Entity
@Table(
        name = "monthly_adjustment",
        indexes = {
                @Index(name = "idx_madj_period", columnList = "month,year,type"),
                @Index(name = "idx_madj_user_period", columnList = "user_id,month,year")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MonthlyAdjustment {

    /** Loại khoản — quyết định file Excel nào import ra nó. */
    public enum Type {
        /** Thưởng thêm: lì xì Tết, thưởng theo quy định… Nhãn dùng CHUNG cả file. */
        BONUS,
        /** Phụ cấp: xăng xe, điện thoại… Nhãn chọn từ {@link AllowanceLabel}. */
        ALLOWANCE
    }

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

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private Type type;

    /**
     * Bộ phận chịu khoản này — được set từ tab tải lên (FACTORY / DRIVER /
     * SALES / WAREHOUSE / ACCOUNTING). Không có filter theo bộ phận thì khi
     * import cho tài xế nhưng file chứa user xưởng, khoản đó sẽ bị áp cả sang
     * xưởng và ngược lại. Nullable để tương thích với dữ liệu cũ (đọc = FACTORY).
     */
    @Column(name = "department", length = 30)
    private String department;

    /**
     * Nhãn hiển thị trên phiếu lương.
     * <ul>
     *   <li>{@code BONUS} — nhãn tự do, giống nhau cho mọi dòng trong file.</li>
     *   <li>{@code ALLOWANCE} — phải khớp một tên trong {@link AllowanceLabel}.</li>
     * </ul>
     */
    @Column(name = "label", nullable = false, length = 120)
    private String label;

    /** Số tiền (VNĐ). Luôn ≥ 0 — muốn trừ tiền thì dùng nghiệp vụ khác. */
    @Column(name = "amount", nullable = false)
    @Builder.Default
    private Long amount = 0L;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
    }
}