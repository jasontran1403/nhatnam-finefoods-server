package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * SubCategory — danh mục con của Category.
 * Bảng riêng (sub_categories) để không ảnh hưởng code Category đang hoạt động.
 * Quan hệ: nhiều SubCategory thuộc 1 Category (qua categoryId).
 */
@Entity
@Table(name = "sub_categories")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** FK tới bảng categories.id */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;
}