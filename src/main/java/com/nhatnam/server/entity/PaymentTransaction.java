package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Một lần thanh toán (partial hoặc full).
 * Mỗi đơn hàng có thể có nhiều PaymentTransaction.
 */
@Entity
@Table(name = "payment_transaction",
       indexes = @Index(columnList = "order_id, created_at"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Số tiền của lần thanh toán này */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** CASH | BANK_TRANSFER | DEBT | OTHER */
    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod;

    /** Tên ngân hàng — chỉ có khi BANK_TRANSFER */
    @Column(name = "bank_name", length = 100)
    private String bankName;

    /** Mã giao dịch ngân hàng */
    @Column(name = "transaction_ref", length = 100)
    private String transactionRef;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Người thu tiền (snapshot name) */
    @Column(name = "collected_by", length = 100)
    private String collectedBy;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
