package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Chi tiết phân bổ 1 phiếu chi trả công nợ NCC vào 1 {@link MaterialRequestVendor}
 * cụ thể (= phần công nợ của NCC đó trong 1 phiếu đặt hàng nguyên liệu).
 *
 * Một {@link VendorExpenseVoucher} có thể trừ vào nhiều phiếu đặt hàng cũ
 * (FIFO theo debtSince) nếu số tiền chi lớn hơn công nợ còn lại của 1 phiếu.
 */
@Entity
@Table(name = "vendor_expense_allocation")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class VendorExpenseAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id", nullable = false)
    private VendorExpenseVoucher voucher;

    /** Phiếu đặt hàng (qua MaterialRequestVendor) được trừ nợ */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_vendor_id", nullable = false)
    private MaterialRequestVendor materialRequestVendor;

    /** Mã phiếu đặt hàng (snapshot, để hiển thị nhanh không cần join) */
    @Column(name = "request_code", length = 50)
    private String requestCode;

    /** Số tiền được trừ vào phiếu này trong lần chi này */
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** Công nợ còn lại của phiếu này SAU khi trừ (snapshot để tra cứu lịch sử) */
    @Column(name = "remaining_after", precision = 14, scale = 2)
    private BigDecimal remainingAfter;
}
