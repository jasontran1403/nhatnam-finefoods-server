// src/main/java/com/nhatnam/server/entity/PaymentTransaction.java
package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Một lần thanh toán (partial hoặc full).
 * Mỗi đơn hàng có thể có nhiều PaymentTransaction.
 *
 * <h3>Index</h3>
 * <ul>
 *   <li>{@code idx_pt_order_created} — composite (order_id, created_at): tra lịch sử
 *       của 1 đơn (findByOrderIdOrderByCreatedAtAsc) và dùng cho dedup khi backfill
 *       (existsByOrderIdAndCreatedAtBetween).</li>
 *   <li>{@code idx_pt_created_at} — standalone (created_at): dùng cho các query
 *       SUM/COUNT theo khoảng thời gian (sumRevenueAllOrders, sumRevenueByUser,...)
 *       — chạy mỗi lần tính hoa hồng/thưởng. Thiếu index này thì khi bảng lớn
 *       sẽ full-scan hàng triệu row.</li>
 * </ul>
 */
@Entity
@Table(name = "payment_transaction",
        indexes = {
                @Index(name = "idx_pt_order_created", columnList = "order_id, created_at"),
                @Index(name = "idx_pt_created_at",   columnList = "created_at")
        })
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

    /**
     * Epoch milliseconds (UTC). Cùng chuẩn với Order.createdAt.
     * Khi tính "trong tháng N" phải map sang Asia/Ho_Chi_Minh — xem FactoryPayrollService.VN.
     */
    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    /**
     * Nguồn gốc dòng này: null = tạo trực tiếp từ nghiệp vụ, BACKFILL_LOG = tạo lại
     * từ order_log bằng PaymentTransactionBackfillService. Dùng để phân biệt khi
     * cần rollback / re-backfill mà không xoá nhầm dữ liệu gốc.
     */
    @Column(name = "source", length = 20)
    private String source;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}