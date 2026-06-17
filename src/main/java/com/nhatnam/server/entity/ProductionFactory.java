package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Xưởng sản xuất — Owner tạo, FACTORY_WORKER được gán vào quản lý.
 * 1 worker có thể quản lý nhiều xưởng.
 * WorkOrder được gán cho 1 xưởng cụ thể.
 */
@Entity
@Table(name = "production_factory")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductionFactory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FactoryStatus status = FactoryStatus.ACTIVE;

    /** Danh sách manager của xưởng này (FACTORY_WORKER role) */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "production_factory_managers",
            joinColumns = @JoinColumn(name = "factory_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @Builder.Default
    private List<User> managers = new ArrayList<>();

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum FactoryStatus { ACTIVE, INACTIVE }
}