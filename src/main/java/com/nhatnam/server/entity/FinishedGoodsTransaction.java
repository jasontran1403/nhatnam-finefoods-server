package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Lịch sử xuất kho / chuyển kho thành phẩm.
 * EXPORT  = xuất kho (bán/tiêu hao) — cần lý do (reason)
 * TRANSFER = chuyển kho — cần kho đích (targetWarehouseId), khi kho đích là kho
 *            bán hàng (Warehouse) thì hệ thống tự tạo/bổ sung Ingredient + IngredientStock
 *            + IngredientExpiry tương ứng (kèm ngày sản xuất + hạn sử dụng).
 */
@Entity
@Table(name = "finished_goods_transaction")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FinishedGoodsTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(nullable = false, length = 50)
    private String unit;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Lý do — bắt buộc khi type = EXPORT */
    @Column(columnDefinition = "TEXT")
    private String reason;

    /**
     * Chứng từ đính kèm khi xuất kho — JSON array các URL ảnh (optional).
     * VD: ["/uploads/abc.jpg","/uploads/def.jpg"]. Mặc định "[]".
     */
    @Column(name = "document_images", columnDefinition = "TEXT")
    @Builder.Default
    private String documentImages = "[]";

    /** Xưởng nguồn của giao dịch (kho thành phẩm của xưởng nào) */
    @Column(name = "factory_id")
    private Long factoryId;

    @Column(name = "factory_name", length = 200)
    private String factoryName;

    /** Kho đích — bắt buộc khi type = TRANSFER (Warehouse bán hàng / trung chuyển) */
    @Column(name = "target_warehouse_id")
    private Long targetWarehouseId;

    @Column(name = "target_warehouse_name", length = 200)
    private String targetWarehouseName;

    /** Ingredient tương ứng đã tạo/dùng ở kho đích (nếu transfer) */
    @Column(name = "target_ingredient_id")
    private Long targetIngredientId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by_id")
    private User performedBy;

    @Column(name = "performed_by_name", length = 200)
    private String performedByName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist void onCreate() { createdAt = System.currentTimeMillis(); }

    public enum TransactionType {
        EXPORT, TRANSFER
    }
}
