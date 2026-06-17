package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Phiếu chi phí — do SUPER_ACCOUNTANT hoặc SUPER_WAREHOUSE tạo.
 * Cần ADMIN hoặc OWNER duyệt trước khi tính vào chi phí.
 */
@Entity
@Table(name = "expense_voucher")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpenseVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String voucherCode;   // EV-YYYYMMDD-XXXX

    /** Tên đơn vị thi công / nhà cung cấp — có thể rỗng */
    @Column(length = 300)
    private String vendorName;

    /** Lý do chi — bắt buộc */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** Người lập phiếu (snapshot tên) */
    @Column(nullable = false, length = 200)
    private String createdByName;

    /** Người yêu cầu (snapshot tên) — có thể là người khác */
    @Column(length = 200)
    private String requestedByName;

    /** Người yêu cầu (user entity) — nullable */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_id")
    private User requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VoucherStatus status = VoucherStatus.PENDING;

    /** Người duyệt (ADMIN / OWNER) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @Column(length = 200)
    private String approvedByName;

    @Column(name = "approved_at")
    private Long approvedAt;

    /** Lý do từ chối */
    @Column(columnDefinition = "TEXT")
    private String rejectReason;

    /** Danh sách khoản chi */
    @Builder.Default
    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExpenseItem> items = new ArrayList<>();

    /** Ảnh chứng từ — JSON array ["url1","url2",...] */
    @Column(name = "image_urls", columnDefinition = "TEXT")
    private String imageUrls;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum VoucherStatus {
        PENDING,   // chờ duyệt
        APPROVED,  // đã duyệt
        REJECTED   // từ chối
    }
}
