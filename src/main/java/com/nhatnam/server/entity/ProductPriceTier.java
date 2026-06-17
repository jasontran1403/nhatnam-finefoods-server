package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "product_price_tier",
        indexes = @Index(name = "idx_tier_product", columnList = "product_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductPriceTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "tier_name", nullable = false)
    private String tierName;

    @Builder.Default
    @Column(name = "min_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal minQuantity = BigDecimal.ZERO;

    @Column(name = "max_quantity", precision = 10, scale = 2)
    private BigDecimal maxQuantity;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}