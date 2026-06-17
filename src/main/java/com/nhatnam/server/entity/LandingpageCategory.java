package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "landingpage_categories")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LandingpageCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;        // Tên tiếng Anh (chính)

    @Column(name = "name_vi", length = 100)
    private String nameVi;      // Tên tiếng Việt

    @Column(name = "sort_order")
    private Integer sortOrder;
}