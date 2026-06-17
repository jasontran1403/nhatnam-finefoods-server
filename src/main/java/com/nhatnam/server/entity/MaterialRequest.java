package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Phiếu đặt hàng nguyên liệu — Factory Worker tạo, Super Accountant xử lý.
 *
 * Flow: NEW → ORDERED → RECEIVED → COMPLETED
 */
@Entity
@Table(name = "material_request")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã phiếu: MR-YYYYMMDD-XXXX */
    @Column(name = "request_code", nullable = false, unique = true, length = 50)
    private String requestCode;

    /** Người tạo phiếu (Factory Worker) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", nullable = false, length = 200)
    private String createdByName;

    /**
     * Thời gian cần xử lý (deadline) — nullable.
     * Kế toán trưởng cần đặt hàng trước thời điểm này.
     */
    @Column(name = "required_by")
    private Long requiredBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RequestStatus status = RequestStatus.NEW;

    /** Thời gian kế toán xác nhận đặt hàng */
    @Column(name = "ordered_at")
    private Long orderedAt;

    /** Thời gian giao hàng dự kiến (do kế toán nhập khi xác nhận) */
    @Column(name = "estimated_delivery")
    private Long estimatedDelivery;

    /** Người xử lý phiếu (Super Accountant) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by_id")
    private User handledBy;

    @Column(name = "handled_by_name", length = 200)
    private String handledByName;

    /** Thời gian nhân viên xưởng xác nhận đã nhận hàng */
    @Column(name = "received_at")
    private Long receivedAt;

    /** Ghi chú khi nhận hàng */
    @Column(name = "receive_notes", columnDefinition = "TEXT")
    private String receiveNotes;

    /** Thời gian kế toán bấm Hoàn thành */
    @Column(name = "completed_at")
    private Long completedAt;

    /** Danh sách nhà cung cấp cho phiếu này (kế toán thêm) */
    @Builder.Default
    @OneToMany(mappedBy = "materialRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaterialRequestVendor> vendors = new ArrayList<>();

    /** Danh sách nguyên liệu trong phiếu */
    @Builder.Default
    @OneToMany(mappedBy = "materialRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaterialRequestItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum RequestStatus {
        NEW,       // Mới tạo
        ORDERED,   // Đã đặt hàng (kế toán xác nhận)
        RECEIVED,  // Đã nhận hàng (nv xưởng xác nhận)
        COMPLETED  // Hoàn thành (kế toán đóng phiếu)
    }
}
