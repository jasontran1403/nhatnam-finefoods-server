package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Theo dõi từng LÔ nguyên liệu tại kho (FIFO).
 *
 * THAY ĐỔI: bỏ @ManyToOne ingredient → dùng ingredientId plain column.
 * Lý do: khi ingredient bị soft-delete, các lô HSD vẫn cần tồn tại
 * để FifoDeductService có thể hoàn kho (cancelOrder).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ingredient_expiry",
        indexes = {
                @Index(columnList = "warehouse_id, ingredient_id"),
                @Index(columnList = "expiry_date")
        })
public class IngredientExpiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    // ── Plain FK — không có @ManyToOne Ingredient nữa ───────────────
    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** Số lượng còn lại của lô này */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "cost_price", precision = 15, scale = 2)
    private BigDecimal costPrice;

    private Long createdAt;
    private Long updatedAt;
}
