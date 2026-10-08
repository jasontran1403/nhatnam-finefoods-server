package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Phiếu thu — do ACCOUNTANT hoặc SUPER_ACCOUNTANT tạo.
 * Không cần duyệt: tạo xong status = CONFIRMED ngay lập tức.
 */
@Entity
@Table(name = "income_voucher")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IncomeVoucher {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String voucherCode;

    @Column(name = "customer_name")
    private String customerName;

    /** Tên người nộp tiền / đơn vị — có thể rỗng */
    @Column(length = 300)
    private String payerName;

    /**
     * Số phiếu thu — do người dùng nhập (hoặc dùng số gợi ý). KHÔNG unique vì số
     * phiếu chạy tới 15000 sẽ quay vòng về 1, nên các phiếu ở những vòng khác nhau
     * có thể trùng số. Vẫn bắt buộc phải có.
     */
    @Column(name = "receipt_number", nullable = false, length = 100)
    private String receiptNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, length = 200)
    private String createdByName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VoucherStatus status = VoucherStatus.CONFIRMED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @Column(length = 200)
    private String approvedByName;

    @Column(name = "approved_at")
    private Long approvedAt;

    @Column(columnDefinition = "TEXT")
    private String rejectReason;

    // ── Loại thanh toán ──────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20)
    @Builder.Default
    private PaymentType paymentType = PaymentType.CASH;

    /** Tên ngân hàng — bắt buộc khi paymentType = BANK_TRANSFER */
    @Column(name = "bank_name", length = 100)
    private String bankName;

    /** Mã tham chiếu giao dịch ngân hàng */
    @Column(name = "bank_ref", length = 200)
    private String bankRef;

    // ── Đơn hàng liên quan — JSON array of order codes ───────────────────────
    /** Danh sách mã đơn hàng liên quan (JSON: ["ORD-001","ORD-002"]) */
    @Column(name = "linked_order_codes", columnDefinition = "TEXT")
    private String linkedOrderCodes;

    @Builder.Default
    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IncomeItem> items = new ArrayList<>();

    /**
     * SỐ TIỀN ĐÃ GHI CHO TỪNG ĐƠN. Nguồn sự thật để sửa/hoàn tác chính xác khi
     * một đơn được nhiều phiếu cùng trả. Xem {@link IncomeVoucherOrderAllocation}.
     */
    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<IncomeVoucherOrderAllocation> orderAllocations = new ArrayList<>();

    @Column(name = "image_urls", columnDefinition = "TEXT")
    private String imageUrls;

    // ── CẤN TRỪ (overpay offset) ─────────────────────────────────────────────
    /**
     * ID phiếu thu NGUỒN mà phiếu này được "cấn trừ" từ phần dư. {@code null}
     * nghĩa là phiếu thu bình thường (không phải phiếu con cấn trừ).
     *
     * <p>Khi bấm "Cấn trừ" trên phiếu A có phần dư → BE tạo phiếu B cho 1 đơn
     * cùng khách của A, {@code B.offsetSourceVoucherId = A.id}, đồng thời
     * {@code A.offsetUsedAmount += B.totalAmount} để phần dư của A giảm đi.
     */
    @Column(name = "offset_source_voucher_id")
    private Long offsetSourceVoucherId;

    /**
     * Tổng số tiền ĐÃ CẤN TRỪ từ phiếu này sang các phiếu con. {@code null}/0 =
     * chưa cấn trừ. Dùng khi tính phần dư: {@code over = total − Σalloc − offsetUsed}.
     */
    @Column(name = "offset_used_amount", precision = 15, scale = 2)
    private java.math.BigDecimal offsetUsedAmount;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum VoucherStatus { CONFIRMED, PENDING, APPROVED, REJECTED }

    public enum PaymentType {
        CASH,          // Tiền mặt
        BANK_TRANSFER  // Chuyển khoản
    }
}