package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Nhà cung cấp (Supplier) — dùng cho ACCOUNTANT/SUPER_ACCOUNTANT.
 */
@Entity
@Table(name = "supplier")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 20)
    private String phone;

    @Column(length = 300)
    private String address;

    @Column(length = 200)
    private String email;

    @Column(length = 200)
    private String contactPerson; // Người liên hệ

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
