package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/** Một khoản chi trong phiếu chi phí */
@Entity
@Table(name = "expense_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpenseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id", nullable = false)
    private ExpenseVoucher voucher;

    /** Tên khoản chi — bắt buộc */
    @Column(nullable = false, length = 300)
    private String itemName;

    /**
     * FK tới {@link VendorExpenseCategory} — nhãn khoản chi được CHỌN từ danh mục
     * của NCC (do Owner quản lý). Dùng để tổng hợp chi phí theo mục (VD tiền điện
     * theo từng tháng) không phụ thuộc chữ hiển thị. Null với phiếu cũ (gõ tự do).
     */
    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, precision = 15, scale = 0)
    private BigDecimal amount;

    @Column(columnDefinition = "TEXT")
    private String note;
}
