package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "order_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // ── Product snapshot — plain columns, KHÔNG có @ManyToOne Product ──
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "product_image_url")
    private String productImageUrl;

    @Column(name = "unit")
    private String unit;

    @Column(name = "category_snapshot", length = 255)
    private String categorySnapshot;       // snapshot: product.category

    @Column(name = "sku_snapshot", length = 100)
    private String skuSnapshot;            // snapshot: product.sku

    @Column(name = "packaging_description_snapshot", length = 255)
    private String packagingDescriptionSnapshot; // snapshot: product.packagingDescription

    @Column(name = "max_discount_rate_snapshot")
    private Integer maxDiscountRateSnapshot; // snapshot: product.maxDiscountRate

    @Column(name = "specification_snapshot")
    private Integer specificationSnapshot; // snapshot: product.specification

    @Column(name = "misa_category_snapshot", length = 100)
    private String misaCategorySnapshot; // snapshot: product.misaCategory

    // ── Quy cách bán ──
    @Builder.Default
    @Column(name = "sale_type", length = 10)
    private String saleType = "RETAIL";

    @Column(name = "units_per_box")
    private Integer unitsPerBox;           // snapshot: product.unitsPerBox tại thời điểm bán

    // ── Giá ──
    @Column(name = "base_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal basePrice;          // snapshot: product.basePrice

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal unitPrice;          // giá thực tế bán

    @Builder.Default
    @Column(name = "price_mode", nullable = false, length = 20)
    private String priceMode = "BASE";

    // ── Tier snapshot — plain columns, KHÔNG có @ManyToOne ProductPriceTier ──
    @Column(name = "tier_id")
    private Long tierId;                   // chỉ lưu id để tham chiếu, không FK

    @Column(name = "tier_name")
    private String tierName;              // snapshot: tier.tierName

    @Column(name = "tier_price_snapshot", precision = 15, scale = 4)
    private BigDecimal tierPriceSnapshot; // snapshot: tier.price tại thời điểm bán

    @Column(name = "discount_percent")
    private Integer discountPercent;

    // ── VAT snapshot ──
    @Builder.Default
    @Column(name = "vat_rate", nullable = false)
    private Integer vatRate = 0;          // snapshot: product.vatRate

    @Builder.Default
    @Column(name = "vat_mode", length = 20, nullable = false)
    private String vatMode = "INCLUSIVE"; // snapshot: product.vatMode

    @Builder.Default
    @Column(name = "vat_amount", precision = 15, scale = 4)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    // ── Số lượng & tổng ──
    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal subtotal;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "cost_price", precision = 15, scale = 4)
    private BigDecimal costPrice;

    // ── Fields cũ không dùng nữa, giữ lại để không break DB ──
    @Column(name = "variant_id")   private Long variantId;
    @Column(name = "variant_name") private String variantName;

    /**
     * Số lượng đã hoàn/đổi (tính theo đơn vị của OrderItem — thùng với BOX, đơn vị thường với RETAIL).
     * null = chưa hoàn/đổi; > 0 = đã hoàn/đổi một phần hoặc toàn bộ.
     * Không dùng để tính tiền, chỉ dùng để hiển thị trạng thái và lọc báo cáo.
     */
    @Column(name = "returned_qty", precision = 10, scale = 3)
    private BigDecimal returnedQty;

    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderItemIngredient> orderItemIngredients;
}