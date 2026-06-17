package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "order_stock_deduction",
        indexes = @Index(columnList = "order_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderStockDeduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_stock_id", nullable = false)
    private IngredientStock ingredientStock;

    /** null nếu kho không theo dõi lô, hoặc lô đã bị xóa sau khi xuất */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_expiry_id")
    private IngredientExpiry ingredientExpiry;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Snapshot expiryDate tại thời điểm xuất — dùng để tạo lại lô khi hoàn kho */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** Snapshot costPrice tại thời điểm xuất — dùng để tạo lại lô khi hoàn kho */
    @Column(name = "cost_price", precision = 15, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}