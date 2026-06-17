package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Phiếu nhập liệu của Operator.
 * Một phiếu gom nhiều sản phẩm tạo mới hoặc chỉnh sửa.
 * Cần Admin duyệt (APPROVE) thì mới thực sự áp dụng vào DB.
 */
@Entity
@Table(name = "product_batch")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_code", nullable = false, unique = true)
    private String batchCode;   // PB-YYYYMMDD-XXXX

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchType type;     // CREATE | UPDATE

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BatchStatus status = BatchStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", nullable = false)
    private String createdByName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_by_name")
    private String reviewedByName;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "reviewed_at")
    private Long reviewedAt;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Builder.Default
    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductBatchItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum BatchType   { CREATE, UPDATE }
    public enum BatchStatus { PENDING, PARTIALLY_APPROVED, APPROVED, REJECTED }
}
