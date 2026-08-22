package com.nhatnam.server.repository;

import com.nhatnam.server.dto.dashboard.PaymentMethodStatDto;
import com.nhatnam.server.dto.dashboard.StatusCountDto;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.enumtype.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findAll(Specification<Order> spec, Sort sort);
    @Query("SELECT o FROM Order o JOIN FETCH o.orderItems oi WHERE o.createdAt BETWEEN :from AND :to AND o.user.id = :userId")
    List<Order> findByUserIdAndCreatedAtBetween(
            @Param("userId") Long userId,
            @Param("from") Long from,
            @Param("to") Long to
    );

    @Query("SELECT o FROM Order o WHERE o.visibleToSellerId = :sellerId OR o.visibleToSellerId IS NULL ORDER BY o.createdAt DESC")
    List<Order> findVisibleToSeller(@Param("sellerId") Long sellerId);

    // Thêm query cho non-seller
    List<Order> findAllByOrderByCreatedAtDesc();

    @Query("SELECT COALESCE(SUM(o.paidAmount), 0) FROM Order o " +
            "WHERE o.createdAt BETWEEN :from AND :to")
    BigDecimal sumPaidAmountByRange(@Param("from") long from, @Param("to") long to);

    List<Order> findByCustomerIdAndStatus(Long customerId, OrderStatus status);
    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** Lấy tất cả đơn của một tập khách hàng (dùng để tính công nợ chưa thanh toán theo lô). */
    List<Order> findByCustomerIdIn(java.util.Collection<Long> customerIds);

    // ── Revenue chart ─────────────────────────────────────────────────────────

    @Query(value = """
        SELECT DATE_FORMAT(FROM_UNIXTIME(created_at / 1000), '%Y-%m-%d %H') AS h,
               SUM(final_amount), COUNT(*)
        FROM `order`
        WHERE status = 'COMPLETED' AND created_at BETWEEN :from AND :to
        GROUP BY h ORDER BY h
        """, nativeQuery = true)
    List<Object[]> findRevenueByHourNative(@Param("from") long from, @Param("to") long to);

    @Query(value = """
        SELECT DATE_FORMAT(FROM_UNIXTIME(created_at / 1000), '%Y-%m-%d') AS d,
               SUM(final_amount), COUNT(*)
        FROM `order`
        WHERE status = 'COMPLETED' AND created_at BETWEEN :from AND :to
        GROUP BY d ORDER BY d
        """, nativeQuery = true)
    List<Object[]> findRevenueByDayNative(@Param("from") long from, @Param("to") long to);

    @Query(value = """
        SELECT DATE_FORMAT(FROM_UNIXTIME(created_at / 1000), '%Y-%m') AS m,
               SUM(final_amount), COUNT(*)
        FROM `order`
        WHERE status = 'COMPLETED' AND created_at BETWEEN :from AND :to
        GROUP BY m ORDER BY m
        """, nativeQuery = true)
    List<Object[]> findRevenueByMonthNative(@Param("from") long from, @Param("to") long to);

    // ── Top products ──────────────────────────────────────────────────────────

    @Query(value = """
        SELECT oi.product_id, oi.product_name, oi.product_image_url, oi.unit,
               SUM(oi.quantity) AS total_qty, COUNT(DISTINCT oi.order_id) AS total_orders,
               SUM(oi.subtotal) AS total_revenue
        FROM order_item oi JOIN `order` o ON o.id = oi.order_id
        WHERE o.status = 'COMPLETED' AND o.created_at BETWEEN :from AND :to
        GROUP BY oi.product_id, oi.product_name, oi.product_image_url, oi.unit
        ORDER BY total_revenue DESC LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopProductsNative(
            @Param("from") long from, @Param("to") long to, @Param("limit") int limit);

    @Query(value = """
        SELECT oi.product_id, oi.product_name, oi.product_image_url, oi.unit,
               SUM(oi.quantity) AS total_qty, COUNT(DISTINCT oi.order_id) AS total_orders,
               SUM(oi.subtotal) AS total_revenue
        FROM order_item oi JOIN `order` o ON o.id = oi.order_id
        WHERE o.status = 'COMPLETED' AND o.created_at BETWEEN :from AND :to
        GROUP BY oi.product_id, oi.product_name, oi.product_image_url, oi.unit
        ORDER BY total_qty DESC LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopProductsByQuantityNative(
            @Param("from") long from, @Param("to") long to, @Param("limit") int limit);

    @Query(value = """
        SELECT oi.product_id, oi.product_name, oi.product_image_url, oi.unit,
               SUM(oi.quantity) AS total_qty, COUNT(DISTINCT oi.order_id) AS total_orders,
               SUM(oi.subtotal) AS total_revenue
        FROM order_item oi JOIN `order` o ON o.id = oi.order_id
        WHERE o.status = 'COMPLETED' AND o.created_at BETWEEN :from AND :to
        GROUP BY oi.product_id, oi.product_name, oi.product_image_url, oi.unit
        ORDER BY total_orders DESC LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopProductsByOrdersNative(
            @Param("from") long from, @Param("to") long to, @Param("limit") int limit);

    // ── Top sellers ───────────────────────────────────────────────────────────

    @Query(value = """
        SELECT o.user_id, u.username, u.full_name,
               COUNT(*) AS total_orders, SUM(o.final_amount) AS total_revenue
        FROM `order` o JOIN _user u ON u.id = o.user_id
            WHERE o.status IN ('PREPARING', 'DELIVERING', 'PENDING_PAYMENT', 'COMPLETED')
                                     AND o.created_at BETWEEN :from AND :to
        GROUP BY o.user_id, u.username, u.full_name
        ORDER BY total_revenue DESC LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopSellersNative(
            @Param("from") long from, @Param("to") long to, @Param("limit") int limit);

    @Query(value = """
        SELECT o.user_id, u.username, u.full_name,
               COUNT(*) AS total_orders, SUM(o.final_amount) AS total_revenue
        FROM `order` o JOIN _user u ON u.id = o.user_id
            WHERE o.status IN ('PREPARING', 'DELIVERING', 'PENDING_PAYMENT', 'COMPLETED')
                                     AND o.created_at BETWEEN :from AND :to
        GROUP BY o.user_id, u.username, u.full_name
        ORDER BY total_orders DESC LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopSellersByOrdersNative(
            @Param("from") long from, @Param("to") long to, @Param("limit") int limit);

    // ── Top customers ─────────────────────────────────────────────────────────

    @Query(value = """
        SELECT o.customer_id, o.customer_name,
               COUNT(o.id) AS total_orders,
               SUM(CASE WHEN o.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_orders,
               COALESCE(SUM(CASE WHEN o.status = 'COMPLETED' THEN o.final_amount ELSE 0 END), 0) AS total_spent
        FROM `order` o
        WHERE o.created_at BETWEEN :from AND :to
        GROUP BY o.customer_id, o.customer_name
        ORDER BY total_spent DESC LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopCustomersNative(
            @Param("from") long from, @Param("to") long to, @Param("limit") int limit);

    // ── Counts ────────────────────────────────────────────────────────────────

    long countByCreatedAtBetween(Long from, Long to);
    long countByStatus(OrderStatus status);
    long countByStatusNotIn(List<OrderStatus> statuses);
    long countByStatusAndCreatedAtBetween(OrderStatus status, long from, long to);

    @Query("SELECT COUNT(o) FROM Order o " +
            "WHERE o.status NOT IN :statuses AND o.createdAt BETWEEN :from AND :to")
    long countByStatusNotInAndCreatedAtBetween(
            @Param("statuses") List<OrderStatus> statuses,
            @Param("from") long from,
            @Param("to") long to);

    @Query("SELECT SUM(o.finalAmount) FROM Order o " +
            "WHERE o.status = :status AND o.createdAt BETWEEN :from AND :to")
    BigDecimal sumFinalAmountByStatusAndRange(
            @Param("status") OrderStatus status, @Param("from") long from, @Param("to") long to);

    @Query("SELECT new com.nhatnam.server.dto.dashboard.StatusCountDto(CAST(o.status AS string), COUNT(o)) " +
            "FROM Order o GROUP BY o.status")
    List<StatusCountDto> countOrdersByStatus();

    @Query("SELECT new com.nhatnam.server.dto.dashboard.StatusCountDto(CAST(o.status AS string), COUNT(o)) " +
            "FROM Order o WHERE o.createdAt BETWEEN :from AND :to GROUP BY o.status")
    List<StatusCountDto> countOrdersByStatusAndRange(
            @Param("from") long from, @Param("to") long to);

    @Query("""
        SELECT new com.nhatnam.server.dto.dashboard.PaymentMethodStatDto(
            COALESCE(o.paymentMethod, 'UNKNOWN'), COUNT(o), SUM(o.finalAmount)
        )
        FROM Order o
        WHERE o.status = com.nhatnam.server.enumtype.OrderStatus.COMPLETED
          AND o.createdAt BETWEEN :from AND :to
        GROUP BY o.paymentMethod
        """)
    List<PaymentMethodStatDto> revenueByPaymentMethod(@Param("from") Long from, @Param("to") Long to);

    Page<Order> findAll(Specification<Order> spec, Pageable pageable);

    // Feature 4 KPI: tìm đơn trong khoảng thời gian
    @Query("SELECT o FROM Order o WHERE o.createdAt >= :from AND o.createdAt <= :to order by o.id desc")
    List<Order> findByCreatedAtBetween(@Param("from") long from, @Param("to") long to);

    @Query("""
    SELECT o
    FROM Order o
    WHERE o.createdAt BETWEEN :from AND :to
      AND o.status <> :canceled
    ORDER BY o.id DESC
    """)
    List<Order> findByCreatedAtBetweenAndStatusIsNot(
            @Param("from") long from,
            @Param("to") long to,
            @Param("canceled") OrderStatus canceled
    );

    // Feature 4 KPI: tìm thời điểm đặt hàng đầu tiên của customer
    @Query("SELECT MIN(o.createdAt) FROM Order o WHERE o.customer.id = :customerId")
    Long findFirstOrderTimeByCustomerId(@Param("customerId") Long customerId);

    /**
     * Thống kê khách mới / khách cũ trong kỳ [from, to] cho seller.
     *
     * Khách MỚI = có đơn trong [from,to] VÀ không có đơn nào trước from
     * Khách CŨ  = có đơn trong [from,to] VÀ có ít nhất 1 đơn trước from
     *
     * Nhận diện khách theo:
     *   - customer_id nếu có (khách đã đăng ký)
     *   - customer_phone nếu không có customer_id (khách lẻ)
     *   - Bỏ qua đơn không có cả hai (không xác định được khách)
     *
     * Returns Object[3]: [totalCustomers, newCustomers, returningCustomers]
     */
    @Query(value = """
        WITH customers_in_period AS (
            SELECT DISTINCT
                COALESCE(CAST(customer_id AS CHAR), customer_phone) AS customer_key
            FROM `order`
            WHERE created_at BETWEEN :from AND :to
              AND user_id = :sellerId
              AND status != 'CANCELLED'
              AND (customer_id IS NOT NULL OR (customer_phone IS NOT NULL AND customer_phone != ''))
        ),
        has_order_before AS (
            SELECT DISTINCT
                COALESCE(CAST(customer_id AS CHAR), customer_phone) AS customer_key
            FROM `order`
            WHERE created_at < :from
              AND status != 'CANCELLED'
              AND (customer_id IS NOT NULL OR (customer_phone IS NOT NULL AND customer_phone != ''))
        )
        SELECT
            COUNT(*)                                                        AS total_customers,
            SUM(CASE WHEN h.customer_key IS NULL     THEN 1 ELSE 0 END)    AS new_customers,
            SUM(CASE WHEN h.customer_key IS NOT NULL THEN 1 ELSE 0 END)    AS returning_customers
        FROM customers_in_period c
        LEFT JOIN has_order_before h ON h.customer_key = c.customer_key
        """, nativeQuery = true)
    List<Object[]> getCustomerStats(
            @Param("sellerId") Long sellerId,
            @Param("from") Long from,
            @Param("to") Long to);

    Optional<Order> findByOrderCode(String orderCode);
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    // ── Analytics ─────────────────────────────────────────────────────────────
    List<Order> findByStatusAndCreatedAtBetween(OrderStatus status, Long from, Long to);

    @Query("SELECT o FROM Order o " +
            "LEFT JOIN FETCH o.orderItems oi " +
            "LEFT JOIN FETCH oi.orderItemIngredients " +
            "WHERE 1=1 " +
            "AND (:search IS NULL OR LOWER(o.customerName) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "     OR LOWER(o.customerPhone) LIKE LOWER(CONCAT('%', :search, '%'))) " +
            "AND (:status IS NULL OR o.status = :status)")
    Page<Order> findAllWithItems(
            @Param("search") String search,
            @Param("status") OrderStatus status,
            Pageable pageable);

    /**
     * Đơn công nợ dùng cho báo cáo Aged Receivables.
     *
     * <p>FIX: trước đây chỉ lọc {@code paymentMethod='DEBT'} + {@code paymentStatus ∈ {UNPAID, PARTIAL}},
     * KHÔNG lọc trạng thái đơn → kéo cả đơn ĐÃ HUỶ (CANCELLED/FAILED) và đơn CHƯA GIAO
     * (PENDING/CONFIRMED/PREPARING/READY) vào công nợ. Với tính năng "thanh toán trước",
     * đơn đang chuẩn bị có thể ở PARTIAL → sẽ bị tính nhầm thành nợ phải thu.
     *
     * <p>Công nợ chỉ phát sinh khi hàng ĐÃ RỜI KHO → chỉ lấy
     * DELIVERING / PENDING_PAYMENT / COMPLETED.
     */
    @Query("""
            SELECT o FROM Order o
            LEFT JOIN FETCH o.customer c
            WHERE UPPER(o.paymentMethod) = 'DEBT'
              AND o.paymentStatus IN (
                    com.nhatnam.server.enumtype.PaymentStatus.UNPAID,
                    com.nhatnam.server.enumtype.PaymentStatus.PARTIAL)
              AND o.status IN (
                    com.nhatnam.server.enumtype.OrderStatus.DELIVERING,
                    com.nhatnam.server.enumtype.OrderStatus.PENDING_PAYMENT,
                    com.nhatnam.server.enumtype.OrderStatus.COMPLETED)
            """)
    List<Order> findDebtReceivables();

    /**
     * Toàn bộ đơn công nợ dùng cho dashboard (KHÔNG lọc ngày tháng):
     * status = PENDING_PAYMENT và paymentStatus ∈ {UNPAID, PARTIAL}.
     * JOIN FETCH customer để đọc customer.debtDays mà không bị lazy-load.
     */
    @Query("""
            SELECT o FROM Order o
            LEFT JOIN FETCH o.customer c
            WHERE o.status = com.nhatnam.server.enumtype.OrderStatus.PENDING_PAYMENT
              AND o.paymentStatus IN (
                    com.nhatnam.server.enumtype.PaymentStatus.UNPAID,
                    com.nhatnam.server.enumtype.PaymentStatus.PARTIAL)
            """)
    List<Order> findPendingPaymentReceivables();

    /**
     * Đơn có gắn tài xế trong một khoảng thời gian — dùng cho báo cáo ODO tài xế.
     *
     * <p>Mốc thời gian ưu tiên {@code deliveryDatetime} (ngày giao thực tế), thiếu thì
     * lùi về {@code createdAt}, vì đơn cũ có thể chưa nhập ngày giao. Đơn CANCELLED bị
     * loại vì không phát sinh quãng đường. Lọc {@code delivery_info_json} khác rỗng để
     * không kéo về toàn bộ đơn không liên quan tài xế.
     */
    @Query(value = """
            SELECT * FROM `order` o
            WHERE o.status <> 'CANCELLED'
              AND o.delivery_info_json IS NOT NULL
              AND o.delivery_info_json <> ''
              AND COALESCE(o.delivery_datetime, o.created_at) BETWEEN :from AND :to
            ORDER BY COALESCE(o.delivery_datetime, o.created_at) ASC
            """, nativeQuery = true)
    List<Order> findWithDriversBetween(@Param("from") long from, @Param("to") long to);

    /**
     * Tổng tiền khách thanh toán dư (overpaidAmount) CHƯA ĐƯỢC HOÀN LẠI (chưa lập
     * phiếu chi hoàn) — trong khoảng thời gian [from, to].
     */
    @Query("""
            SELECT COALESCE(SUM(o.overpaidAmount), 0)
            FROM Order o
            WHERE o.overpaidAmount > 0
              AND o.overpaidRefundVoucherCode IS NULL
              AND o.createdAt >= :from AND o.createdAt <= :to
            """)
    BigDecimal sumUnrefundedOverpaidBetween(@Param("from") long from, @Param("to") long to);
}