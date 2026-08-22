package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * PHIẾU TẶNG QUÀ BẰNG SẢN PHẨM.
 *
 * <p>Seller tạo phiếu khi khách tới sinh nhật (khách lẻ) hoặc khai trương cửa hàng mới
 * (khách công ty) → OWNER/ADMIN duyệt → kho nhận và đi giao.
 *
 * <h3>Vì sao KHÔNG trừ kho lúc tạo phiếu</h3>
 * Phiếu tặng quà là đề xuất, không phải cam kết: seller có thể tạo vài phiếu rồi bị
 * từ chối hết. Nếu trừ kho ngay lúc tạo thì hàng bị "giam" cho những phiếu chưa chắc
 * được duyệt, và đơn bán thật đứng sau lại báo hết hàng. Vì vậy lúc tạo KHÔNG kiểm tra
 * và KHÔNG trừ tồn; toàn bộ việc kiểm tra + trừ dồn vào bước duyệt.
 *
 * <p>Hệ quả phải chấp nhận: giữa lúc tạo và lúc duyệt, tồn kho có thể đã bị đơn khác
 * lấy mất. Đó là lý do bước duyệt trả về danh sách cảnh báo thiếu tồn thay vì im lặng
 * cho qua — OWNER thấy thiếu thì báo nhập kho rồi duyệt lại.
 *
 * <p>Voucher quà tặng KHÔNG dùng entity này — xem {@link Voucher}. Hai loại quà đi hai
 * đường vì voucher không đụng tới tồn kho và không cần kho xử lý giao hàng.
 */
@Entity
@Table(name = "gift_orders", indexes = {
        @Index(name = "idx_gift_customer",  columnList = "customer_id"),
        @Index(name = "idx_gift_status",    columnList = "status"),
        @Index(name = "idx_gift_warehouse", columnList = "warehouse_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GiftOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã phiếu in trên chứng từ. VD: {@code QT-2608-A3F9}. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** Snapshot tên khách — dùng cho lý do phiếu xuất kho và báo cáo về sau. */
    @Column(name = "customer_name", length = 250)
    private String customerName;

    /**
     * KHO XUẤT HÀNG. Chọn lúc tạo phiếu vì danh sách sản phẩm hiển thị cho seller
     * phụ thuộc kho — không thể chọn hàng trước rồi mới chọn kho.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(name = "occasion", length = 30, nullable = false)
    @Builder.Default
    private GiftOccasion occasion = GiftOccasion.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private GiftOrderStatus status = GiftOrderStatus.PENDING;

    @Column(name = "note", length = 500)
    private String note;

    // ── Người tạo (seller) ───────────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    /**
     * Snapshot tên seller. Phiếu xuất kho sinh ra khi duyệt phải ghi TÊN SELLER TẠO
     * (không phải tên người duyệt), nên tên này phải sống lâu hơn tài khoản seller.
     */
    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    // ── Duyệt ────────────────────────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @Column(name = "approved_by_name", length = 150)
    private String approvedByName;

    @Column(name = "approved_at")
    private Long approvedAt;

    /** Lý do từ chối — bắt buộc khi status = REJECTED để seller biết đường sửa. */
    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    // ── Kho xử lý ────────────────────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by_id")
    private User handledBy;

    @Column(name = "handled_by_name", length = 150)
    private String handledByName;

    @Column(name = "handled_at")
    private Long handledAt;

    /** Phiếu xuất kho sinh ra khi duyệt — để tra ngược chứng từ. */
    @Column(name = "warehouse_receipt_id")
    private Long warehouseReceiptId;

    @OneToMany(mappedBy = "giftOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<GiftOrderItem> items = new ArrayList<>();

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public void addItem(GiftOrderItem item) {
        item.setGiftOrder(this);
        items.add(item);
    }

    /** Tổng số lượng sản phẩm trong phiếu — hiển thị nhanh ở danh sách. */
    public BigDecimal totalQuantity() {
        return items.stream()
                .map(i -> i.getQuantity() != null ? i.getQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public enum GiftOccasion {
        /** Sinh nhật khách lẻ. */
        BIRTHDAY,
        /** Khai trương cửa hàng mới của khách công ty. */
        STORE_OPENING,
        OTHER
    }

    public enum GiftOrderStatus {
        /** Seller vừa tạo, chờ OWNER/ADMIN duyệt. Chưa trừ kho. */
        PENDING,
        /** Đã duyệt — ĐÃ trừ kho, đã sinh phiếu xuất, kho đang chờ xử lý giao. */
        APPROVED,
        /** Nhân viên kho đã xác nhận, đang giao cho khách. */
        DELIVERING,
        /** Đã giao xong. */
        COMPLETED,
        /** OWNER/ADMIN từ chối. Không trừ kho. */
        REJECTED,
        /** Seller tự huỷ khi còn PENDING. */
        CANCELLED
    }
}
