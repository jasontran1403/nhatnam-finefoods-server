package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Một dòng NVL trong công thức định lượng chuẩn.
 * Ví dụ: 30kg thịt giò, 1kg ruột, 3kg đá lạnh, ...
 */
@Entity
@Table(name = "production_recipe_item",
       uniqueConstraints = @UniqueConstraint(columnNames = {"recipe_id", "factory_material_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductionRecipeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private ProductionRecipe recipe;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_material_id", nullable = false)
    private FactoryMaterial factoryMaterial;

    /** Định lượng chuẩn (VD: 30) */
    @Column(name = "standard_qty", nullable = false, precision = 10, scale = 3)
    private BigDecimal standardQty;

    /** Đơn vị — snapshot từ FactoryMaterial.unit, có thể override */
    @Column(nullable = false, length = 50)
    private String unit;

    /** Thứ tự hiển thị */
    @Builder.Default
    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}
