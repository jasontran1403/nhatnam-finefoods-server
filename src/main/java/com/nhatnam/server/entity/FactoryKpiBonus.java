package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * QUỸ THƯỞNG KPI CỦA PHÒNG SẢN XUẤT theo THÁNG.
 *
 * <h3>Công thức</h3>
 * <pre>
 * totalOutputKg  = Σ accumulatedQty của các LỆNH SẢN XUẤT có status = COMPLETED
 *                  và actualEndDate rơi vào trong tháng
 *                  (lệnh bắt đầu 30/5 nhưng xong 1/6 → tính cho THÁNG 6)
 * totalOutputTon = totalOutputKg / 1000
 * bonusPool      = round(totalOutputTon × 1.400.000) về hàng NGHÌN
 * </pre>
 *
 * <h3>Phân bổ</h3>
 * <pre>
 * 1. Bảo vệ xưởng (FACTORY_SECURITY): CỐ ĐỊNH 300.000đ/người.
 * 2. Phần còn lại (+ quỹ dư tháng trước = carryOverIn) chia theo TRỌNG SỐ:
 *      FACTORY_PRODUCTION_WORKER / FACTORY_WORKER : 1.00  (chuẩn)
 *      FACTORY_STAFF (trợ lý kho)                 : 1.10  (+10%)
 *      SUPER_FACTORY_WORKER (trưởng xưởng)        : 1.25  (+25%)
 *      FACTORY_MANAGER (quản lý xưởng)            : 1.25  (+25%)
 * 3. Tiền mỗi người LÀM TRÒN XUỐNG hàng TRĂM NGHÌN.
 * 4. Phần lẻ chưa chia hết → carryOverOut, cộng vào quỹ THÁNG SAU.
 * </pre>
 */
@Entity
@Table(
        name = "factory_kpi_bonus",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_factory_kpi_bonus_month_year",
                columnNames = {"month", "year"}
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryKpiBonus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    /** Tổng sản lượng hoàn thành trong tháng (kg) */
    @Column(name = "total_output_kg", precision = 16, scale = 2)
    @Builder.Default
    private BigDecimal totalOutputKg = BigDecimal.ZERO;

    /** Quy đổi ra tấn = kg / 1000 */
    @Column(name = "total_output_ton", precision = 16, scale = 4)
    @Builder.Default
    private BigDecimal totalOutputTon = BigDecimal.ZERO;

    /** Đơn giá thưởng trên 1 tấn (VNĐ) — snapshot tại thời điểm tính */
    @Column(name = "rate_per_ton")
    @Builder.Default
    private Long ratePerTon = 1_400_000L;

    /** Quỹ thưởng của cả phòng SX tháng này (đã làm tròn hàng nghìn) */
    @Column(name = "bonus_pool")
    @Builder.Default
    private Long bonusPool = 0L;

    /** Quỹ dư chuyển sang TỪ tháng trước */
    @Column(name = "carry_over_in")
    @Builder.Default
    private Long carryOverIn = 0L;

    /** Quỹ dư chuyển SANG tháng sau (phần lẻ sau khi làm tròn trăm nghìn) */
    @Column(name = "carry_over_out")
    @Builder.Default
    private Long carryOverOut = 0L;

    /**
     * SỔ QUỸ DƯ mang sang tháng sau — JSON: {@code [{"month":6,"year":2026,"amount":513000}, …]}
     *
     * <p><b>Vì sao cần chi tiết thay vì một con số:</b> quỹ dư tích tụ qua nhiều
     * tháng. Chỉ lưu tổng thì không thể nói cho nhân viên biết "513.000đ gồm
     * 113.000đ của T6 và 400.000đ của T7". Sổ này giữ NGUỒN GỐC từng khoản; khi
     * chia thì tiêu khoản CŨ NHẤT trước (FIFO) để tiền không treo mãi ở một tháng.
     *
     * <p>Đây là sổ ĐẦU RA (phần còn lại sau khi đã chia của tháng này). Sổ đầu vào
     * của tháng N chính là sổ đầu ra của tháng N-1.
     */
    @Column(name = "carry_over_detail", columnDefinition = "TEXT")
    private String carryOverDetail;

    /**
     * MỨC THƯỞNG CỐ ĐỊNH CHO MỘT BẢO VỆ XƯỞNG — snapshot của tháng.
     *
     * <p>Trước đây là hằng số biên dịch cứng trong code. Nay OWNER nhập được khi
     * bấm "Tính thưởng KPI", nên phải lưu lại theo từng tháng: sửa mức cho tháng
     * này không được làm đổi số của các tháng đã chốt.
     *
     * <p>Tính lại tháng cũ (VD sau khi tải lại bảng chấm công) sẽ DÙNG LẠI mức đã
     * lưu ở đây chứ không quay về mặc định — nếu không, mỗi lần import lại bảng
     * chấm công sẽ âm thầm xoá mức mà OWNER đã chỉnh.
     */
    @Column(name = "security_rate")
    @Builder.Default
    private Long securityRate = 300_000L;

    /** Tổng tiền cố định trả cho bảo vệ */
    @Column(name = "security_total")
    @Builder.Default
    private Long securityTotal = 0L;

    /** Tổng tiền thực chia cho nhân viên (không gồm bảo vệ) */
    @Column(name = "distributed_total")
    @Builder.Default
    private Long distributedTotal = 0L;

    /** Tổng trọng số của tất cả nhân viên tham gia chia (không gồm bảo vệ) */
    @Column(name = "total_weight")
    @Builder.Default
    private Double totalWeight = 0.0;

    @Builder.Default
    @OneToMany(mappedBy = "kpiBonus", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<FactoryKpiBonusItem> items = new ArrayList<>();

    @Column(name = "computed_at")
    private Long computedAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        if (computedAt == null) computedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }
}