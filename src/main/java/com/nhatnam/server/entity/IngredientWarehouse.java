package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Ánh xạ nguyên liệu ↔ kho chứa.
 *
 * THAY ĐỔI: bỏ @ManyToOne ingredient → dùng ingredientId plain column.
 * Lý do: khi ingredient bị soft-delete, record này vẫn có thể tồn tại
 * (hoặc bị xóa theo service logic) mà không gây FK constraint error.
 *
 * NOTE: Service deleteIngredient nên xóa các IngredientWarehouse records liên quan.
 */
@Entity
@Table(
        name = "ingredient_warehouse",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ingredient_id", "warehouse_id"})
)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IngredientWarehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Plain FK — không @ManyToOne Ingredient ──────────────────────
    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
