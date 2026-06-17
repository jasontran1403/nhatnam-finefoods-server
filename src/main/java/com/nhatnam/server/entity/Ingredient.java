package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ingredient")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(nullable = false, length = 50)
    private String unit; // "piece", "kg", "gram", "liter"

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Column(name = "is_active")
    private Boolean isActive = true;

    // ── Mã hàng (do operator tạo, dùng chọn lại) ──
    @Column(name = "item_code", length = 100)
    private String itemCode;

    // ── Danh mục (category) — nullable ──
    @Column(name = "category_id")
    private Long categoryId;

    // ── Danh mục con (sub-category) — nullable ──
    @Column(name = "sub_category_id")
    private Long subCategoryId;
}
