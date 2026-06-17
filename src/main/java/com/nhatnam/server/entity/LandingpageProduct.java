package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "landingpage_products")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LandingpageProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "name_en", length = 200)
    private String nameEn;

    @Column(name = "image_path", length = 500)
    private String imagePath;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "description_en", columnDefinition = "TEXT")
    private String descriptionEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private LandingpageCategory category;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;
}