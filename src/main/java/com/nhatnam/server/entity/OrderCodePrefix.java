package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "order_code_prefix")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderCodePrefix {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2, unique = true)
    private String prefix;        // "AB", "XK", ...

    @Column(nullable = false)
    private Integer year;         // năm đang dùng prefix này

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;   // prefix đang dùng cho năm hiện tại

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}