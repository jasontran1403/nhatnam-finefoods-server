package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * LỊCH SỬ SỬ DỤNG VOUCHER — mỗi lần voucher trừ vào một đơn hàng là một dòng.
 *
 * <p>Phục vụ hai chiều tra cứu mà cả hai đều cần trong vận hành:
 * <ul>
 *   <li><b>Từ đơn hàng</b>: xem chi tiết đơn thì biết đã trừ voucher mã nào, bao nhiêu.</li>
 *   <li><b>Từ voucher</b>: xem lịch sử voucher thì biết đã tiêu ở những đơn nào.</li>
 * </ul>
 *
 * <p><b>Vì sao cần bảng riêng khi đã có {@code PaymentTransaction}?</b> Bảng đó ghi
 * "thu 1.000.000 đ bằng VOUCHER, ref = VC-2608-XXXX" — đủ cho kế toán nhưng tra ngược từ
 * voucher sang đơn phải quét chuỗi trong cột {@code transactionRef}, không có khoá ngoại,
 * không có index, và sẽ hỏng nếu ai đó sửa ref. Bảng này giữ quan hệ thật giữa hai đầu.
 */
@Entity
@Table(name = "voucher_usages", indexes = {
        @Index(name = "idx_vusage_voucher", columnList = "voucher_id"),
        @Index(name = "idx_vusage_order",   columnList = "order_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    private Voucher voucher;

    /** Snapshot mã voucher — phiếu in đã phát ra vẫn tra được kể cả khi bản ghi voucher bị xoá. */
    @Column(name = "voucher_code", nullable = false, length = 40)
    private String voucherCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "order_code", length = 50)
    private String orderCode;

    /** Số tiền voucher đã trừ cho đơn này. Luôn là số nguyên đồng — xem VoucherRedemptionService. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** Số dư voucher SAU lần trừ này — để đọc lịch sử không phải cộng dồn lại. */
    @Column(name = "remaining_after", nullable = false)
    private Long remainingAfter;

    @Column(name = "used_by_name", length = 150)
    private String usedByName;

    @Column(name = "used_by_id")
    private Long usedById;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
    }
}
