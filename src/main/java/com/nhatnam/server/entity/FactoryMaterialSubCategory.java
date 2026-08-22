package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Danh mục riêng của nguyên liệu xưởng — trực thuộc 1 danh mục chung
 * ({@link FactoryMaterialCategory}). Nguyên liệu xưởng trực thuộc danh mục riêng.
 */
@Entity
@Table(name = "factory_material_sub_category")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FactoryMaterialSubCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Danh mục chung cha */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private FactoryMaterialCategory category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
