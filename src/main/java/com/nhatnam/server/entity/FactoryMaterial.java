package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Nguyên vật liệu xưởng sản xuất.
 * Tách biệt hoàn toàn với Ingredient (kho hàng).
 */
@Entity
@Table(name = "factory_material")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FactoryMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Đơn vị đo: kg, gram, lít, ml, hộp, cái, ... */
    @Column(nullable = false, length = 50)
    private String unit;

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
