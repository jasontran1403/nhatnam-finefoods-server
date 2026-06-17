package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.VatMode;
import com.nhatnam.server.enumtype.VatRate;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "product")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)          private String name;
    @Column(columnDefinition = "TEXT") private String unit;
    @Column(columnDefinition = "TEXT") private String category;
    @Column(name = "image_url")        private String imageUrl;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Builder.Default
    @Column(name = "base_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal basePrice = BigDecimal.ZERO;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "vat_rate", nullable = false)
    private VatRate vatRate = VatRate.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "vat_mode", length = 20, nullable = false)
    @Builder.Default
    private VatMode vatMode = VatMode.INCLUSIVE;

    @Builder.Default
    @Column(name = "max_discount_rate", nullable = false)
    private Integer maxDiscountRate = 0;

    @Column(name = "units_per_box")
    private Integer unitsPerBox;

    @Column(name = "created_at", nullable = false) private Long createdAt;
    @Column(name = "updated_at", nullable = false) private Long updatedAt;

    @Column(name = "item_code", length = 100)
    private String itemCode;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "sub_category_id")
    private Long subCategoryId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "packaging_description", length = 255)
    private String packagingDescription;

    @Column(name = "storage_instruction", length = 500)
    private String storageInstruction;

    // ProductPriceTier vẫn có @ManyToOne Product → cascade ALL an toàn
    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("sortOrder ASC, minQuantity ASC")
    private List<ProductPriceTier> priceTiers = new ArrayList<>();

    // ProductIngredient KHÔNG còn @ManyToOne Product → không dùng mappedBy/cascade qua JPA
    // Service sẽ tự xóa qua ProductIngredientRepository.deleteByProductId khi cần
    // Giữ list này chỉ để các code cũ không bị NPE — luôn empty, load qua repository
    @Transient
    private List<ProductIngredient> productIngredients = new ArrayList<>();
}
