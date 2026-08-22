package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Kế hoạch sản xuất — Owner tạo.
 * Một kế hoạch có thể có nhiều sản phẩm (ProductionPlanProduct).
 * Giữ factoryProduct (sản phẩm chính) để backward compat.
 */
@Entity
@Table(name = "production_plan")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã kế hoạch: PLAN-YYYYMM-XXXX */
    @Column(name = "plan_code", nullable = false, unique = true, length = 50)
    private String planCode;

    @Column(nullable = false, length = 200)
    private String title;

    /** Sản phẩm chính (backward compat — sản phẩm đầu tiên) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_product_id", nullable = false)
    private FactoryProduct factoryProduct;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** Xưởng xử lý kế hoạch (nullable để tương thích dữ liệu cũ; backfill về Q9). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_factory_id")
    private ProductionFactory productionFactory;

    @Column(name = "production_factory_name", length = 200)
    private String productionFactoryName;

    /** Sản lượng mục tiêu */
    @Column(name = "target_qty", nullable = false, precision = 12, scale = 2)
    private BigDecimal targetQty;

    @Column(name = "output_unit", nullable = false, length = 50)
    private String outputUnit;

    @Column(name = "start_date", nullable = false)
    private Long startDate;

    @Column(name = "end_date", nullable = false)
    private Long endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PlanStatus status = PlanStatus.ACTIVE;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    /** Danh sách sản phẩm trong kế hoạch (multi-product support) */
    @Builder.Default
    @OneToMany(mappedBy = "productionPlan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductionPlanProduct> planProducts = new ArrayList<>();

    /** Các lệnh sản xuất thuộc kế hoạch này */
    @Builder.Default
    @OneToMany(mappedBy = "productionPlan", fetch = FetchType.LAZY)
    private List<WorkOrder> workOrders = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum PlanStatus {
        ACTIVE,
        COMPLETED,
        CANCELLED
    }
}