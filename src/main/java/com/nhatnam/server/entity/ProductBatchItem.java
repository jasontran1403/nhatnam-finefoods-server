package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Một dòng trong phiếu nhập liệu.
 *
 * THAY ĐỔI: thay @ManyToOne existingProduct → existingProductId plain column.
 * Lý do: khi product bị xóa, phiếu batch vẫn giữ lịch sử.
 * Snapshot tên/info đã có qua productName, tiersJson, ingredientsJson.
 */
@Entity
@Table(name = "product_batch_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private ProductBatch batch;

    /**
     * null = tạo mới, non-null = chỉnh sửa sản phẩm đã có.
     * Plain column — không FK constraint, giữ lịch sử khi product bị xóa.
     */
    @Column(name = "product_id")
    private Long existingProductId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "category_name")
    private String categoryName;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "unit")
    private String unit;

    @Column(name = "base_price", precision = 15, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "max_discount_rate")
    private Integer maxDiscountRate;

    @Column(name = "vat_rate")
    private Integer vatRate;

    @Column(name = "vat_mode", length = 20)
    private String vatMode;

    @Column(name = "units_per_box")
    private Integer unitsPerBox;

    /**
     * Đơn vị quy đổi (VD: "kg") — dùng khi tạo đơn Misa.
     * Null = không có quy đổi.
     */
    @Column(name = "conversion_unit", length = 50)
    private String conversionUnit;

    /**
     * Quy cách quy đổi: 1 đơn vị gốc = bao nhiêu đơn vị quy đổi.
     * VD: 1 hộp = 0.454 kg → conversionFactor = 0.454
     */
    @Column(name = "conversion_factor", precision = 10, scale = 3)
    private BigDecimal conversionFactor;

    /** SKU — mã hàng ngắn */
    @Column(name = "sku", length = 100)
    private String sku;

    /** Quy cách (gr/đơn vị): 500, 424, 410, 210, 1000 */
    @Column(name = "specification")
    private Integer specification;

    /** Danh mục MISA: Kem, Xúc xích bò, Xúc xích heo, Xúc xích gà */
    @Column(name = "misa_category", length = 100)
    private String misaCategory;

    /** Toàn bộ tiers, ingredients dạng JSON */
    @Column(name = "tiers_json", columnDefinition = "TEXT")
    private String tiersJson;

    @Column(name = "ingredients_json", columnDefinition = "TEXT")
    private String ingredientsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ItemStatus status = ItemStatus.PENDING;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() { createdAt = System.currentTimeMillis(); }

    public enum ItemStatus { PENDING, APPROVED, REJECTED }
}