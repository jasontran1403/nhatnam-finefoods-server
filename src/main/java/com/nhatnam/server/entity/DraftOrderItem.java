package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "draft_order_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "draft_order_id", nullable = false)
    private DraftOrder draftOrder;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "product_image_url")
    private String productImageUrl;

    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "unit", length = 30)
    private String unit;

    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "base_price", precision = 15, scale = 2)
    private BigDecimal basePrice;

    /** "BASE" | "TIER" | "DISCOUNT_PERCENT" */
    @Builder.Default
    @Column(name = "price_mode", length = 20)
    private String priceMode = "BASE";

    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "tier_name", length = 100)
    private String tierName;

    @Column(name = "discount_percent")
    private Integer discountPercent;

    @Builder.Default
    @Column(name = "is_manual_price")
    private Boolean isManualPrice = false;

    /** "BOX" | "RETAIL" */
    @Builder.Default
    @Column(name = "sale_type", length = 10)
    private String saleType = "RETAIL";

    @Column(name = "units_per_box")
    private Integer unitsPerBox;

    @Column(name = "is_promo")
    @Builder.Default
    private Boolean isPromo = false;

    @Column(name = "promo_note", columnDefinition = "TEXT")
    private String promoNote;

    @Column(name = "item_discount_rate")
    @Builder.Default
    private Integer itemDiscountRate = 0;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "subtotal", precision = 15, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "vat_rate")
    @Builder.Default
    private Integer vatRate = 0;

    @Column(name = "vat_mode", length = 20)
    @Builder.Default
    private String vatMode = "INCLUSIVE";
}
