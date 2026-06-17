package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Công thức định lượng chuẩn cho một thành phẩm.
 * Chỉ OWNER được tạo / sửa.
 *
 * Ví dụ: "Xúc xích chuẩn A" → output 30kg xúc xích
 *         từ: 30kg thịt giò, 1kg ruột, 3kg đá lạnh, 2kg muối, 1kg đường, 1L nước mắm
 */
@Entity
@Table(name = "production_recipe")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductionRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_product_id", nullable = false)
    private FactoryProduct factoryProduct;

    /** Tên công thức (có thể có nhiều công thức cho 1 thành phẩm) */
    @Column(nullable = false, length = 200)
    private String name;

    /** Sản lượng chuẩn đầu ra (VD: 30) */
    @Column(name = "standard_output_qty", nullable = false, precision = 10, scale = 3)
    private BigDecimal standardOutputQty;

    /** Đơn vị đầu ra (lấy từ FactoryProduct nhưng lưu snapshot) */
    @Column(name = "output_unit", nullable = false, length = 50)
    private String outputUnit;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Builder.Default
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductionRecipeItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
