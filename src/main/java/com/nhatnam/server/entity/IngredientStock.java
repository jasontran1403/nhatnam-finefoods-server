package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * THAY ĐỔI: bỏ @ManyToOne ingredient → dùng ingredientId plain column + snapshot.
 *
 * Lý do: khi ingredient bị xóa (soft hoặc hard), các bản ghi tồn kho vẫn cần tồn tại
 * để OrderStockDeduction → IngredientStock → ingredientId có thể truy ngược lại.
 *
 * Khi soft-delete ingredient: IngredientStock vẫn giữ nguyên, chỉ cần kiểm tra
 * ingredient.isActive khi cần hiển thị (không xóa stock records).
 */
@Entity
@Table(
        name = "ingredient_stock",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ingredient_id", "warehouse_id"})
)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IngredientStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Plain FK — không có @ManyToOne nữa ──────────────────────────
    // Bỏ @ManyToOne để khi ingredient bị xóa, record này vẫn tồn tại
    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    // ── Snapshot khi ingredient bị xóa vẫn đọc được ─────────────────
    @Column(name = "ingredient_name_snapshot", length = 255)
    private String ingredientNameSnapshot;

    @Column(name = "ingredient_unit_snapshot", length = 50)
    private String ingredientUnitSnapshot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Builder.Default
    @Column(name = "stock_quantity", nullable = false, precision = 15, scale = 3)
    private BigDecimal stockQuantity = BigDecimal.ZERO;

    @Column(name = "updated_at")
    private Long updatedAt;

    @Builder.Default
    @Column(name = "total_cost_value", precision = 15, scale = 2)
    private BigDecimal totalCostValue = BigDecimal.ZERO;

    // ── Convenience ──────────────────────────────────────────────────
    public String resolvedName() {
        return ingredientNameSnapshot != null ? ingredientNameSnapshot : "N/A";
    }

    public String resolvedUnit() {
        return ingredientUnitSnapshot != null ? ingredientUnitSnapshot : "";
    }
}
