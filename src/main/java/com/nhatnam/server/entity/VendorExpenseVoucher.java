package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Phiếu chi trả công nợ nhà cung cấp (NGUYÊN LIỆU, không phải chi phí tự do).
 * Khác với {@link ExpenseVoucher}: phiếu này KHÔNG cần duyệt, tạo xong là trừ
 * công nợ ngay (kế toán là người quyết định cuối cùng).
 *
 * Kế toán chọn 1 NCC ({@link MaterialVendor}) + số tiền cần chi (hoặc "thanh toán hết"
 * = lấy đúng tổng công nợ hiện tại). Số tiền được trừ dần vào các
 * {@link MaterialRequestVendor} (phần công nợ của NCC này trong từng phiếu đặt hàng),
 * theo thứ tự phiếu có công nợ LÂU NHẤT (debtSince nhỏ nhất) trước.
 */
@Entity
@Table(name = "vendor_expense_voucher")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class VendorExpenseVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã phiếu: VEV-YYYYMMDD-XXXX */
    @Column(name = "voucher_code", nullable = false, unique = true, length = 50)
    private String voucherCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private MaterialVendor vendor;

    /** Snapshot tên NCC tại thời điểm chi */
    @Column(name = "vendor_name", nullable = false, length = 300)
    private String vendorName;

    /** Tổng số tiền của phiếu chi này */
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    /** true nếu kế toán chọn "Thanh toán hết" (số tiền = tổng công nợ hiện tại lúc tạo) */
    @Column(name = "is_full_settlement", nullable = false)
    @Builder.Default
    private boolean fullSettlement = false;

    /** Ghi chú (tuỳ chọn) */
    @Column(columnDefinition = "TEXT")
    private String note;

    /** Ảnh chứng từ thanh toán — bắt buộc, JSON array url ["url1","url2",...] */
    @Column(name = "proof_images", columnDefinition = "TEXT")
    private String proofImages;

    @Column(name = "created_by_name", nullable = false, length = 200)
    private String createdByName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    /** Chi tiết phân bổ số tiền vào từng phiếu đặt hàng (FIFO theo debtSince) */
    @Builder.Default
    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VendorExpenseAllocation> allocations = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
