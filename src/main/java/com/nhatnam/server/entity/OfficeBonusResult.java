// src/main/java/com/nhatnam/server/entity/OfficeBonusResult.java
package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.PayrollDepartment;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * KẾT QUẢ TÍNH THƯỞNG DOANH THU cho PHÒNG KẾ TOÁN / KINH DOANH theo THÁNG.
 *
 * <h3>Nguồn dữ liệu</h3>
 * Doanh thu = tổng số tiền thực thu (qua {@link PaymentTransaction}) trong tháng,
 * KHÔNG phải tổng giá trị đơn hàng:
 * <ul>
 *   <li>KẾ TOÁN — tính tất cả đơn, bất kể ai tạo.</li>
 *   <li>KINH DOANH — mỗi nhân viên chỉ được tính đơn do chính họ tạo
 *       ({@code order.user_id = user.id}).</li>
 * </ul>
 *
 * <h3>Công thức (11/2026 refactor — unit-price driven)</h3>
 * <pre>
 *   rawPool = collectedRevenue × commissionUnitPrice / 100.000.000
 *
 *   SALES  (per seller):
 *     bonusAmount = CEIL(rawPool / 5000) × 5000            // làm tròn LÊN 5.000
 *
 *   ACCOUNTING (per department, pool chia theo trọng số):
 *     unitShare = FLOOR(rawPool / sumWeight / 5000) × 5000  // làm tròn XUỐNG 5.000
 *     mỗi người = weight × unitShare
 *     (KẾ TOÁN TRƯỞNG = 2, chuyên viên = 1, phần dư bỏ)
 * </pre>
 * {@code commissionUnitPrice} được OWNER nhập tay mỗi tháng (không kế thừa từ
 * tháng trước — xem {@code OfficeBonusPreviewService#findLastCommissionUnitPrice}).
 *
 * <h3>Vòng đời</h3>
 * Mỗi lần OWNER bấm "Tính hoa hồng" thì bản ghi này bị XOÁ VÀ GHI LẠI hoàn toàn
 * (delete + insert) để đảm bảo số thưởng luôn khớp với phiếu thanh toán thực tế.
 */
@Entity
@Table(
        name = "office_bonus_result",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_office_bonus_month_year_dept",
                columnNames = {"month", "year", "department"}
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfficeBonusResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Enumerated(EnumType.STRING)
    @Column(name = "department", nullable = false, length = 20)
    private PayrollDepartment department;

    /** Tổng doanh thu (tiền THỰC THU) của bộ phận trong tháng (VNĐ). */
    @Column(name = "total_revenue", precision = 20, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    /** Tổng quỹ thưởng được chia cho cả bộ phận (VNĐ). */
    @Column(name = "total_bonus_pool", nullable = false)
    @Builder.Default
    private Long totalBonusPool = 0L;

    /**
     * Đơn giá hoa hồng (VNĐ cho mỗi 100tr doanh thu) do OWNER nhập khi "Tính hoa hồng".
     * Không có default — mỗi tháng OWNER nhập lại, không kế thừa.
     * {@code null} = kết quả cũ (tier-based, trước 11/2026 refactor).
     */
    @Column(name = "commission_unit_price")
    private Long commissionUnitPrice;

    /** Tổng số phiếu thanh toán được tính trong kỳ. */
    @Column(name = "transaction_count", nullable = false)
    @Builder.Default
    private Integer transactionCount = 0;

    /** Thời điểm OWNER bấm "Tính hoa hồng" (epoch ms). */
    @Column(name = "computed_at")
    private Long computedAt;

    @Column(name = "computed_by_name", length = 200)
    private String computedByName;

    @Builder.Default
    @OneToMany(mappedBy = "bonusResult", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OfficeBonusItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        if (computedAt == null) computedAt = now;
    }
}