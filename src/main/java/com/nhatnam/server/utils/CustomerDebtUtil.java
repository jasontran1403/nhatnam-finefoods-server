package com.nhatnam.server.utils;

import com.nhatnam.server.entity.Order;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tính "tổng công nợ chưa thanh toán" của khách hàng từ các đơn hàng.
 *
 * Quy tắc cộng dồn cho từng đơn:
 *  - paymentStatus == PARTIAL            → cộng phần còn thiếu = finalAmount - paidAmount
 *  - paymentStatus == UNPAID
 *    hoặc status == PENDING_PAYMENT       → cộng toàn bộ finalAmount
 *  - PAID / REFUNDED                      → không cộng
 *  - Đơn CANCELLED / FAILED               → bỏ qua (không phải công nợ)
 *
 * Lưu ý: PARTIAL luôn có status PENDING_PAYMENT, nên phải xét PARTIAL TRƯỚC
 * để không cộng nhầm nguyên đơn.
 *
 * Làm tròn: LÀM TRÒN LÊN tới đơn vị đồng cho TỪNG đơn TRƯỚC khi cộng dồn.
 */
public final class CustomerDebtUtil {

    private CustomerDebtUtil() {}

    /**
     * Phần công nợ chưa thanh toán của một đơn (đã làm tròn lên tới đồng).
     *
     * Định nghĩa công nợ: đơn ĐÃ GIAO đang chờ thu tiền
     *   status == PENDING_PAYMENT  VÀ  paymentStatus ∈ {UNPAID, PARTIAL}.
     *   - UNPAID  → công nợ = finalAmount
     *   - PARTIAL → công nợ = finalAmount − paidAmount
     * Các trạng thái khác (chưa giao, COMPLETED+PAID, CANCELLED, FAILED, REFUNDED) → 0.
     */
    public static long unpaidOfOrder(Order o) {
        if (o == null) return 0L;

        if (o.getStatus() != OrderStatus.PENDING_PAYMENT) return 0L;

        PaymentStatus ps = o.getPaymentStatus();
        if (ps != PaymentStatus.UNPAID && ps != PaymentStatus.PARTIAL) return 0L;

        BigDecimal fin  = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal paid = o.getPaidAmount()  != null ? o.getPaidAmount()  : BigDecimal.ZERO;

        BigDecimal amount = (ps == PaymentStatus.PARTIAL) ? fin.subtract(paid) : fin;

        if (amount.signum() <= 0) return 0L;
        // Làm tròn LÊN tới đồng cho từng đơn TRƯỚC khi cộng.
        return amount.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /**
     * Gộp công nợ chưa thanh toán theo khách hàng.
     * @param orders danh sách đơn (thường lấy theo customerId IN (...))
     * @return map customerId → tổng công nợ (đồng, đã làm tròn lên từng đơn)
     */
    public static Map<Long, Long> unpaidByCustomer(List<Order> orders) {
        Map<Long, Long> map = new HashMap<>();
        if (orders == null) return map;
        for (Order o : orders) {
            if (o == null || o.getCustomer() == null || o.getCustomer().getId() == null) continue;
            Long cid = o.getCustomer().getId();
            long v = unpaidOfOrder(o);
            map.merge(cid, v, Long::sum);
        }
        return map;
    }

    /** Tổng công nợ chưa thanh toán của một khách từ danh sách đơn của riêng khách đó. */
    public static long unpaidTotal(List<Order> customerOrders) {
        if (customerOrders == null) return 0L;
        long sum = 0L;
        for (Order o : customerOrders) sum += unpaidOfOrder(o);
        return sum;
    }

    /**
     * Map customerId → createdAt (mốc thời gian ms) của ĐƠN CÔNG NỢ CŨ NHẤT của khách.
     * Chỉ xét các đơn được tính là công nợ (unpaidOfOrder > 0).
     */
    public static Map<Long, Long> oldestDebtCreatedAtByCustomer(List<Order> orders) {
        Map<Long, Long> map = new HashMap<>();
        if (orders == null) return map;
        for (Order o : orders) {
            if (o == null || o.getCustomer() == null || o.getCustomer().getId() == null) continue;
            if (unpaidOfOrder(o) <= 0) continue;
            Long created = o.getCreatedAt();
            if (created == null) continue;
            map.merge(o.getCustomer().getId(), created, Math::min);
        }
        return map;
    }

    /**
     * Số ngày công nợ = số ngày kể từ createdAt tới hôm nay (>= 0).
     * @return null nếu createdAtMillis null.
     */
    public static Integer debtDaysFromMillis(Long createdAtMillis,
                                             java.time.ZoneId zone,
                                             java.time.LocalDate today) {
        if (createdAtMillis == null) return null;
        java.time.LocalDate created =
                java.time.Instant.ofEpochMilli(createdAtMillis).atZone(zone).toLocalDate();
        long d = java.time.temporal.ChronoUnit.DAYS.between(created, today);
        return (int) Math.max(0, d);
    }
}