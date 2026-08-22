package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

/**
 * VOUCHER TẶNG KHÁCH HÀNG.
 *
 * <p>Sinh ra từ các dịp chăm sóc khách: sinh nhật khách lẻ, khai trương cửa hàng mới
 * của khách công ty, hoặc khuyến mãi thường. Voucher luôn GẮN VỚI MỘT KHÁCH HÀNG
 * (đổi được sang khách khác — xem {@code VoucherService#update}), có HẠN MỨC tiền,
 * HẠN SỬ DỤNG, và ĐIỀU KIỆN áp dụng theo danh mục hoặc sản phẩm cụ thể.
 *
 * <p><b>Phần THANH TOÁN bằng voucher chưa triển khai.</b> Entity đã có sẵn
 * {@link #usedAmount} và {@link VoucherStatus#USED} để khi làm tính năng trừ tiền
 * chỉ cần cộng dồn vào đây, không phải đổi schema. Hiện tại các trường đó luôn = 0 /
 * không tự chuyển trạng thái.
 */
@Entity
@Table(name = "vouchers", indexes = {
        @Index(name = "idx_voucher_customer", columnList = "customer_id"),
        @Index(name = "idx_voucher_status",   columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Voucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã voucher in trên phiếu, duy nhất. VD: {@code VC-2508-A3F91K}. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    /** Tiêu đề hiển thị trên phiếu in. VD: "Quà sinh nhật 2026". */
    @Column(name = "title", length = 200)
    private String title;

    /**
     * KHÁCH ĐƯỢC TẶNG. Bắt buộc — voucher luôn thuộc về một tài khoản khách hàng,
     * nhưng OWNER/ADMIN được đổi sang khách khác nếu tặng nhầm.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** HẠN MỨC — số tiền tối đa voucher này trừ được (đồng). */
    @Column(name = "amount", nullable = false)
    private Long amount;

    /**
     * ĐÃ SỬ DỤNG bao nhiêu (đồng). Dành cho tính năng thanh toán sau này;
     * hiện luôn = 0.
     */
    @Column(name = "used_amount", nullable = false)
    @Builder.Default
    private Long usedAmount = 0L;

    /** HẠN SỬ DỤNG — từ ngày (epoch millis, 00:00 giờ VN). */
    @Column(name = "valid_from", nullable = false)
    private Long validFrom;

    /** HẠN SỬ DỤNG — đến hết ngày (epoch millis, 23:59:59 giờ VN). */
    @Column(name = "valid_to", nullable = false)
    private Long validTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private VoucherStatus status = VoucherStatus.ACTIVE;

    /** Lý do tặng — để lọc/thống kê và chọn mẫu phiếu in phù hợp. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 30, nullable = false)
    @Builder.Default
    private VoucherReason reason = VoucherReason.OTHER;

    /**
     * PHẠM VI ÁP DỤNG.
     *
     * <p>{@link ApplyScope#ALL} = dùng cho mọi mặt hàng ⇒ hai tập id bên dưới bỏ trống.
     * {@link ApplyScope#CATEGORY} ⇒ chỉ {@link #categoryIds} có nghĩa.
     * {@link ApplyScope#PRODUCT}  ⇒ chỉ {@link #productIds} có nghĩa.
     *
     * <p>Cố ý KHÔNG cho chọn đồng thời danh mục + sản phẩm: người bán đứng ở quầy
     * không có cách nào hiểu nhanh "hoặc thuộc danh mục A hoặc là sản phẩm B", và
     * điều kiện lai kiểu đó rất hay bị cãi nhau lúc thanh toán.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "apply_scope", length = 20, nullable = false)
    @Builder.Default
    private ApplyScope applyScope = ApplyScope.ALL;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "voucher_categories", joinColumns = @JoinColumn(name = "voucher_id"))
    @Column(name = "category_id")
    @Builder.Default
    private Set<Long> categoryIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "voucher_products", joinColumns = @JoinColumn(name = "voucher_id"))
    @Column(name = "product_id")
    @Builder.Default
    private Set<Long> productIds = new HashSet<>();

    /** Ghi chú nội bộ (không in lên phiếu). */
    @Column(name = "note", length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    /** Snapshot tên người tạo — giữ lại kể cả khi nhân viên nghỉ việc / bị xoá mềm. */
    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }

    /** Số tiền còn lại có thể dùng. */
    public long remaining() {
        long used = usedAmount != null ? usedAmount : 0L;
        return Math.max(0L, (amount != null ? amount : 0L) - used);
    }

    /**
     * TRẠNG THÁI HIỆU LỰC TẠI THỜI ĐIỂM {@code now}.
     *
     * <p>Tính động thay vì lưu cứng: voucher hết hạn theo thời gian trôi, không có
     * sự kiện nào để trigger cập nhật cột {@code status}. Chạy job quét hàng đêm chỉ
     * để đổi một cột hiển thị là thừa — so sánh ngay lúc đọc vừa đúng vừa rẻ.
     */
    public VoucherStatus effectiveStatus(long now) {
        if (status == VoucherStatus.CANCELLED) return VoucherStatus.CANCELLED;
        if (remaining() <= 0)                  return VoucherStatus.USED;
        if (validTo != null && now > validTo)  return VoucherStatus.EXPIRED;
        return VoucherStatus.ACTIVE;
    }

    public enum VoucherStatus {
        /** Còn hạn, còn tiền, dùng được. */
        ACTIVE,
        /** Đã dùng hết hạn mức. */
        USED,
        /** Quá hạn sử dụng. */
        EXPIRED,
        /** Bị thu hồi thủ công. */
        CANCELLED
    }

    public enum VoucherReason {
        /** Sinh nhật khách lẻ. */
        BIRTHDAY,
        /** Khai trương cửa hàng mới của khách công ty. */
        STORE_OPENING,
        /** Khuyến mãi / tri ân thường. */
        PROMOTION,
        OTHER
    }

    public enum ApplyScope {
        ALL, CATEGORY, PRODUCT
    }
}
