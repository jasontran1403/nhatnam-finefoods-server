package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Công thức nguyên liệu của sản phẩm.
 *
 * THAY ĐỔI:
 * - Bỏ @ManyToOne product → dùng productId plain column
 * - Bỏ @ManyToOne ingredient → dùng ingredientId plain column + snapshot
 *
 * Lý do:
 * - Khi product bị soft-delete: record này vẫn giữ lại để phục hồi nếu cần
 * - Khi ingredient bị soft-delete: record này vẫn tham chiếu được qua ingredientId,
 *   service sẽ bỏ qua ingredient inactive khi tính stock
 *
 * NOTE: Product.productIngredients vẫn dùng cascade ALL, nhưng @ManyToOne bị bỏ.
 * Khi service xóa product, nên xóa các ProductIngredient liên quan qua repository.
 */
@Entity
@Table(name = "product_ingredient",
        uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "ingredient_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Plain FK — không @ManyToOne Product nữa ─────────────────────
    @Column(name = "product_id", nullable = false)
    private Long productId;

    // ── Plain FK — không @ManyToOne Ingredient nữa ──────────────────
    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    // ── Snapshot ingredient — fill khi tạo/update ──────────────────
    @Column(name = "ingredient_name_snapshot", length = 255)
    private String ingredientNameSnapshot;

    @Column(name = "ingredient_unit_snapshot", length = 50)
    private String ingredientUnitSnapshot;

    @Column(name = "ingredient_image_url_snapshot", length = 500)
    private String ingredientImageUrlSnapshot;

    @Column(name = "qty", nullable = false, precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal qty = BigDecimal.ONE;

    @Column(name = "can_override", nullable = false)
    @Builder.Default
    private Boolean canOverride = false;
}
