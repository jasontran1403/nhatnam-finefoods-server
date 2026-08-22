package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Nhà cung cấp được gán cho một phiếu đặt hàng nguyên liệu.
 * Kế toán trưởng thêm khi xử lý phiếu.
 * Một phiếu có thể có nhiều NCC (đặt từ nhiều nơi).
 */
@Entity
@Table(name = "material_request_vendor")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialRequestVendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_id", nullable = false)
    private MaterialRequest materialRequest;

    /**
     * FK tới MaterialVendor (entity đã có sẵn).
     * Nullable vì kế toán có thể chọn NCC từ danh sách hoặc nhập mới.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private MaterialVendor vendor;

    /** Tên NCC (snapshot tại thời điểm thêm, để không phụ thuộc vendor bị xoá) */
    @Column(name = "vendor_name", nullable = false, length = 200)
    private String vendorName;

    @Column(name = "contact_person", length = 200)
    private String contactPerson;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    // ── Thanh toán / Công nợ (kế toán xử lý lúc Hoàn thành phiếu) ────────────

    /**
     * Trạng thái thanh toán cho NCC này trong phiếu này.
     * UNSET: phiếu chưa hoàn thành / chưa xử lý thanh toán.
     * PAID: đã thanh toán luôn lúc hoàn thành (bắt buộc có ảnh chứng từ).
     * DEBT: ghi nhận công nợ — cộng vào công nợ tổng của NCC, có thể trả dần sau
     *       bằng phiếu chi (xem PartialPaid/Settled bên dưới).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 20)
    @Builder.Default
    private VendorPaymentStatus paymentStatus = VendorPaymentStatus.UNSET;

    /** Tổng tiền NCC này cung cấp trong phiếu (tổng lineAmount các item gán cho NCC này) */
    @Column(name = "total_amount", precision = 14, scale = 2)
    private BigDecimal totalAmount;

    /** Số tiền đã trả (qua phiếu chi trả công nợ, hoặc = totalAmount nếu PAID ngay) */
    @Column(name = "paid_amount", precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /**
     * Trạng thái trả nợ chi tiết (chỉ áp dụng khi paymentStatus = DEBT):
     * NONE: chưa trả gì; PARTIAL: đã trả một phần; SETTLED: đã trả hết.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "debt_settlement_status", length = 20)
    @Builder.Default
    private DebtSettlementStatus debtSettlementStatus = DebtSettlementStatus.NONE;

    /**
     * Thời điểm bắt đầu tính công nợ = thời điểm kế toán hoàn thành phiếu (chọn Công nợ).
     * Dùng để filter "công nợ lâu nhất" và để xác định thứ tự trừ FIFO khi tạo phiếu chi.
     */
    @Column(name = "debt_since")
    private Long debtSince;

    /** Hình thức thanh toán: BANK (chuyển khoản) hoặc CASH (tiền mặt) — bắt buộc nếu PAID */
    @Column(name = "payment_method", length = 20)
    private String paymentMethod;

    /** Thông tin thanh toán: nội dung CK / số TK hoặc ghi chú tiền mặt */
    @Column(name = "payment_info", columnDefinition = "TEXT")
    private String paymentInfo;

    /** Ảnh chứng từ thanh toán — JSON array url. Bắt buộc nếu PAID, rỗng nếu DEBT. */
    @Column(name = "payment_proof_images", columnDefinition = "TEXT")
    private String paymentProofImages;

    /**
     * Mục 7 — 2 field bắt buộc nhập ở bước SUPER_ACCOUNTANT làm giá (COMPLETE).
     * Nhập theo TỪNG nhà cung cấp trong phiếu.
     */
    // Thông tin phiếu nhập từ NCC (mã phiếu nhập, mã batch của NCC...) — dạng text
    @Column(name = "import_receipt_info", columnDefinition = "TEXT")
    private String importReceiptInfo;

    // Serial / IMEI — text hoặc dãy số từ NCC
    @Column(name = "serial_imei", columnDefinition = "TEXT")
    private String serialImei;

    public enum VendorPaymentStatus { UNSET, PAID, DEBT }
    public enum DebtSettlementStatus { NONE, PARTIAL, SETTLED }
}
