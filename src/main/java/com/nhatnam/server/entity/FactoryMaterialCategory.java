package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Danh mục chung của nguyên liệu xưởng.
 * 1 danh mục chung có nhiều danh mục riêng ({@link FactoryMaterialSubCategory}).
 */
@Entity
@Table(name = "factory_material_category")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FactoryMaterialCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

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
