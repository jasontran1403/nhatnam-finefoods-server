package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Thành phẩm sản xuất (VD: Xúc xích, Chả lụa, ...).
 * Mỗi thành phẩm có thể có nhiều công thức định lượng.
 */
@Entity
@Table(name = "factory_product")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FactoryProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Đơn vị của thành phẩm: kg, cái, hộp, ... */
    @Column(nullable = false, length = 50)
    private String unit;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Cầu nối tới Ingredient (mặt hàng gốc của hệ thống bán hàng/kho hàng).
     * Khi chuyển thành phẩm từ kho xưởng sang kho bán hàng (FinishedGoodsService.
     * transferGoods), hệ thống dùng ID này để xác định đúng Ingredient đích —
     * KHÔNG còn match theo tên (chuỗi) như trước, tránh tạo trùng/lệch dữ liệu
     * khi tên không khớp tuyệt đối (sai chính tả, hoa/thường, khoảng trắng dư...).
     *
     * Nullable để tương thích các FactoryProduct cũ tạo trước khi có tính năng
     * này (chưa liên kết) — các sản phẩm đó vẫn hoạt động bình thường trong sản
     * xuất, chỉ riêng việc chuyển kho sang bán hàng sẽ cần được gán liên kết
     * trước (FE nên nhắc người dùng bổ sung).
     *
     * name/unit của FactoryProduct được đồng bộ 1 chiều từ Ingredient mỗi khi
     * Ingredient đổi tên/đơn vị (xem IngredientServiceImpl.updateIngredient) —
     * Ingredient là nguồn sự thật (source of truth), FactoryProduct chỉ snapshot.
     */
    @Column(name = "ingredient_id")
    private Long ingredientId;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Builder.Default
    @OneToMany(mappedBy = "factoryProduct", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductionRecipe> recipes = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}