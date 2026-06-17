package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_item_ingredient")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderItemIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    // ── Ingredient snapshot — plain columns, KHÔNG có @ManyToOne Ingredient ──
    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;            // chỉ lưu id, không FK

    @Column(name = "ingredient_name", nullable = false)
    private String ingredientName;        // snapshot: ingredient.name

    @Column(name = "ingredient_image_url")
    private String ingredientImageUrl;    // snapshot: ingredient.imageUrl

    @Column(name = "quantity_used", nullable = false, precision = 10, scale = 3)
    private BigDecimal quantityUsed;      // tổng qty đã trừ kho cho item này

    @Column(nullable = false)
    private String unit;                  // snapshot: ingredient.unit

    @Column(name = "qty_per_unit", precision = 10, scale = 3)
    private BigDecimal qtyPerUnit;        // snapshot: productIngredient.qty (công thức: 1 unit cần bao nhiêu ingredient)
}