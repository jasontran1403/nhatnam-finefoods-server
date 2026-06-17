package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Kế hoạch sản xuất chủ đạo hàng năm (Annual MPS).
 * Lên mục tiêu sản xuất theo tháng cho từng thành phẩm.
 */
@Entity
@Table(name = "annual_mps",
       uniqueConstraints = @UniqueConstraint(columnNames = {"year", "month", "factory_product_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AnnualMPS {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer year;

    @Column(nullable = false)
    private Integer month;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_product_id", nullable = false)
    private FactoryProduct factoryProduct;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id")
    private Machine machine;

    /** Nhu cầu dự báo */
    @Column(name = "forecast_demand", precision = 12, scale = 2)
    private BigDecimal forecastDemand;

    /** Sản lượng kế hoạch */
    @Column(name = "planned_production_qty", nullable = false, precision = 12, scale = 2)
    private BigDecimal plannedProductionQty;

    /** Giờ máy cần thiết */
    @Column(name = "machine_hours_required", precision = 10, scale = 2)
    private BigDecimal machineHoursRequired;

    /** Giờ máy sẵn có (sau khi trừ bảo trì) */
    @Column(name = "net_available_machine_hours", precision = 10, scale = 2)
    private BigDecimal netAvailableMachineHours;

    /** % Utilization */
    @Column(name = "utilization_percent", precision = 5, scale = 2)
    private BigDecimal utilizationPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MpsStatus status = MpsStatus.DRAFT;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum MpsStatus { DRAFT, APPROVED, RELEASED }
}
