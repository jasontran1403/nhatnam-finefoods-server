package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Liên kết giữa ProductionPlan và FactoryProduct.
 * Một kế hoạch có thể có nhiều sản phẩm.
 */
@Entity
@Table(name = "production_plan_product",
        uniqueConstraints = @UniqueConstraint(columnNames = {"plan_id", "factory_product_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductionPlanProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private ProductionPlan productionPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_product_id", nullable = false)
    private FactoryProduct factoryProduct;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;
}