package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Change 12: KPI cho seller — tích lũy mỗi khi đơn hàng giao thành công (COMPLETED).
 */
@Entity
@Table(name = "seller_kpi",
       indexes = {
           @Index(columnList = "seller_id, period_key"),
           @Index(columnList = "period_key")
       },
       uniqueConstraints = @UniqueConstraint(columnNames = {"seller_id", "period_key"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SellerKpi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    /** Kỳ KPI: "2025-05" cho tháng, "2025-W20" cho tuần */
    @Column(name = "period_key", nullable = false, length = 20)
    private String periodKey;

    @Builder.Default
    @Column(name = "total_orders", nullable = false)
    private Integer totalOrders = 0;

    @Builder.Default
    @Column(name = "total_revenue", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    @Column(name = "updated_at")
    private Long updatedAt;
}
