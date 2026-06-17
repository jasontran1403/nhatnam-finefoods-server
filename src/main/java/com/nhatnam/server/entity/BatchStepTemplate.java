package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Mẫu bước sản xuất (preset) — lưu để tái sử dụng khi lập phương án.
 * Mặc định requiresQC = false; user toggle khi lập phương án cụ thể.
 */
@Entity
@Table(name = "batch_step_template")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BatchStepTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200, unique = true)
    private String name;

    /** Mặc định false — user toggle khi lập phương án */
    @Column(name = "requires_qc", nullable = false)
    @Builder.Default
    private boolean requiresQc = false;

    @Column(name = "sort_order")
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() { createdAt = System.currentTimeMillis(); }
}