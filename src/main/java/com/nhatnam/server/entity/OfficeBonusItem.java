package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Kết quả thưởng của 1 nhân viên trong 1 kỳ {@link OfficeBonusResult}.
 *
 * <p><b>KINH DOANH:</b> mỗi seller có doanh thu riêng ({@code revenue})
 * và thưởng riêng ({@code bonusAmount}) tính từ đơn của người đó.
 *
 * <p><b>KẾ TOÁN:</b> doanh thu phòng được chia đều cho tất cả nhân viên
 * có {@code receiveBonus = true}; mỗi người hưởng phần đều ({@code bonusAmount}).
 * {@code revenue} = tổng của cả phòng (giống nhau cho mọi kế toán viên).
 */
@Entity
@Table(
        name = "office_bonus_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_office_bonus_item_result_user",
                columnNames = {"bonus_result_id", "user_id"}
        ),
        indexes = @Index(name = "idx_office_bonus_item_user", columnList = "user_id")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfficeBonusItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bonus_result_id", nullable = false)
    private OfficeBonusResult bonusResult;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "user_full_name", length = 200)
    private String userFullName;

    @Column(name = "role_label", length = 100)
    private String roleLabel;

    /**
     * Doanh thu được dùng để tính thưởng (VNĐ).
     * <ul>
     *   <li>SALES: chỉ đơn do người này tạo.</li>
     *   <li>ACCOUNTING: tổng doanh thu phòng (bằng nhau cho mọi kế toán).</li>
     * </ul>
     */
    @Column(name = "revenue", precision = 20, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;

    /** Số tiền thưởng thực nhận (VNĐ). */
    @Column(name = "bonus_amount", nullable = false)
    @Builder.Default
    private Long bonusAmount = 0L;

    /** % KPI (0–100) áp dụng trong kỳ này (snapshot). Hiện mặc định 100%. */
    @Column(name = "kpi_percent")
    @Builder.Default
    private Double kpiPercent = 100.0;

    /** Số phiếu thanh toán được đếm vào doanh thu (chỉ có ý nghĩa với SALES). */
    @Column(name = "transaction_count")
    @Builder.Default
    private Integer transactionCount = 0;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}