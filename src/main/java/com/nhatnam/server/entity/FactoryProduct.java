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
