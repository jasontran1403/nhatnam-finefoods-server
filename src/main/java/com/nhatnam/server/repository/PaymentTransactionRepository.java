// src/main/java/com/nhatnam/server/repository/PaymentTransactionRepository.java
package com.nhatnam.server.repository;

import com.nhatnam.server.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    List<PaymentTransaction> findByOrderIdOrderByCreatedAtAsc(Long orderId);

    // ── Backfill utility (11/2026) — dedup khi tạo lại từ order_log ─────────

    /**
     * TRUE nếu đã có PaymentTransaction cho đơn này với createdAt trong khoảng
     * [{@code fromMs}, {@code toMs}]. Dùng khi backfill để tránh ghi trùng:
     * mỗi dòng {@code order_log} có timestamp, mình check ±2s quanh đó.
     */
    @Query("""
            SELECT CASE WHEN COUNT(pt) > 0 THEN TRUE ELSE FALSE END
            FROM PaymentTransaction pt
            WHERE pt.order.id   = :orderId
              AND pt.createdAt >= :fromMs
              AND pt.createdAt <= :toMs
            """)
    boolean existsByOrderIdAndCreatedAtBetween(@Param("orderId") Long orderId,
                                               @Param("fromMs")  long fromMs,
                                               @Param("toMs")    long toMs);

    long countBySource(String source);

    long deleteBySource(String source);

    // ── Tổng hợp doanh thu theo khoảng thời gian ────────────────────────────

    /**
     * Tổng tiền THỰC THU trong 1 khoảng thời gian (epoch ms).
     * Dùng cho KẾ TOÁN: tính tất cả đơn, không lọc theo người tạo.
     *
     * <p>Chỉ tính đơn có status IN (PENDING_PAYMENT, COMPLETED) để loại đơn HỦY.
     */
    @Query("""
            SELECT COALESCE(SUM(pt.amount), 0)
            FROM PaymentTransaction pt
            JOIN pt.order o
            WHERE pt.createdAt >= :startMs
              AND pt.createdAt <  :endMs
              AND o.status IN ('PENDING_PAYMENT', 'COMPLETED')
            """)
    BigDecimal sumRevenueAllOrders(@Param("startMs") long startMs,
                                   @Param("endMs")   long endMs);

    /**
     * Tổng tiền THỰC THU của 1 seller cụ thể trong 1 khoảng thời gian.
     * Dùng cho KINH DOANH: chỉ tính đơn do user đó tạo.
     */
    @Query("""
            SELECT COALESCE(SUM(pt.amount), 0)
            FROM PaymentTransaction pt
            JOIN pt.order o
            WHERE pt.createdAt >= :startMs
              AND pt.createdAt <  :endMs
              AND o.user.id   = :userId
              AND o.status IN ('PENDING_PAYMENT', 'COMPLETED')
            """)
    BigDecimal sumRevenueByUser(@Param("startMs") long startMs,
                                @Param("endMs")   long endMs,
                                @Param("userId")  Long userId);

    @Query("""
            SELECT COUNT(pt)
            FROM PaymentTransaction pt
            JOIN pt.order o
            WHERE pt.createdAt >= :startMs
              AND pt.createdAt <  :endMs
              AND o.status IN ('PENDING_PAYMENT', 'COMPLETED')
            """)
    long countTransactionsAllOrders(@Param("startMs") long startMs,
                                    @Param("endMs")   long endMs);

    @Query("""
            SELECT COUNT(pt)
            FROM PaymentTransaction pt
            JOIN pt.order o
            WHERE pt.createdAt >= :startMs
              AND pt.createdAt <  :endMs
              AND o.user.id   = :userId
              AND o.status IN ('PENDING_PAYMENT', 'COMPLETED')
            """)
    long countTransactionsByUser(@Param("startMs") long startMs,
                                 @Param("endMs")   long endMs,
                                 @Param("userId")  Long userId);
}