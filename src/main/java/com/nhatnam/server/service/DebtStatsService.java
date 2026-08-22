package com.nhatnam.server.service;

import com.nhatnam.server.entity.Order;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Tính công nợ "sắp đến hạn TT", "quá hạn" và phân tuổi nợ (aging) — DÙNG CHUNG
 * cho cả 4 role OWNER / ADMIN / SUPER_ACCOUNTANT / ACCOUNTANT để đảm bảo số liệu
 * đồng nhất giữa các dashboard.
 *
 * <p><b>Tập đơn được coi là công nợ (KHÔNG lọc theo ngày tháng — lấy toàn bộ
 * lịch sử):</b>
 * <ul>
 *   <li>status = PENDING_PAYMENT, VÀ</li>
 *   <li>paymentStatus ∈ {UNPAID, PARTIAL}.</li>
 * </ul>
 *
 * <p><b>Số tiền tính cho MỖI đơn</b> (làm tròn TỪNG đơn về đồng, HALF_UP: 0.5→1,
 * 0.4→0, TRƯỚC khi cộng dồn):
 * <ul>
 *   <li>UNPAID  → round(finalAmount)</li>
 *   <li>PARTIAL → round(finalAmount − paidAmount)</li>
 * </ul>
 *
 * <p><b>Phân loại sắp đến hạn / quá hạn</b> dựa trên so sánh
 * {@code debtDays của đơn} với {@code debtDays của khách hàng} (bảng customers,
 * tra theo customer_id của đơn):
 * <ul>
 *   <li>Quá hạn (overdue): {@code order.debtDays > customer.debtDays}</li>
 *   <li>Sắp đến hạn (nearing): {@code order.debtDays <= customer.debtDays}
 *       HOẶC {@code order.debtDays == 0}</li>
 * </ul>
 *
 * <p><b>Phân tuổi nợ (aging)</b> theo số ngày kể từ lúc TẠO đơn (createdAt) đến
 * hôm nay: 0–30, 31–60, 61–90, &gt;90 ngày. Dùng cùng số tiền mỗi đơn như trên.
 */
@Service
@RequiredArgsConstructor
public class DebtStatsService {

    private final OrderRepository orderRepository;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Kết quả tổng hợp công nợ.
     *
     * @param nearingCount     số đơn sắp đến hạn
     * @param overdueCount     số đơn quá hạn
     * @param totalDebtOrders  tổng số đơn công nợ
     * @param nearingAmount    tổng tiền chưa thu của các đơn sắp đến hạn
     * @param overdueAmount    tổng tiền chưa thu của các đơn quá hạn
     * @param totalDebtAmount  tổng tiền chưa thu của toàn bộ đơn công nợ
     * @param aging0to30       tổng tiền chưa thu của đơn tạo cách đây 0–30 ngày
     * @param aging31to60      tổng tiền chưa thu của đơn tạo cách đây 31–60 ngày
     * @param aging61to90      tổng tiền chưa thu của đơn tạo cách đây 61–90 ngày
     * @param aging90plus      tổng tiền chưa thu của đơn tạo cách đây &gt;90 ngày
     */
    public record DebtStats(
            long nearingCount,
            long overdueCount,
            long totalDebtOrders,
            BigDecimal nearingAmount,
            BigDecimal overdueAmount,
            BigDecimal totalDebtAmount,
            BigDecimal aging0to30,
            BigDecimal aging31to60,
            BigDecimal aging61to90,
            BigDecimal aging90plus) {}

    @Transactional(readOnly = true)
    public DebtStats compute() {
        LocalDate today = LocalDate.now(VN);

        // Toàn bộ đơn công nợ (không lọc ngày): PENDING_PAYMENT + paymentStatus UNPAID/PARTIAL,
        // đã JOIN FETCH customer để đọc debtDays mà không bị lazy-load.
        List<Order> debtOrders = orderRepository.findPendingPaymentReceivables();

        long nearingCount = 0, overdueCount = 0, totalDebtOrders = 0;
        BigDecimal nearingAmount   = BigDecimal.ZERO;
        BigDecimal overdueAmount   = BigDecimal.ZERO;
        BigDecimal totalDebtAmount = BigDecimal.ZERO;
        BigDecimal aging0to30  = BigDecimal.ZERO;
        BigDecimal aging31to60 = BigDecimal.ZERO;
        BigDecimal aging61to90 = BigDecimal.ZERO;
        BigDecimal aging90plus = BigDecimal.ZERO;

        for (Order o : debtOrders) {
            BigDecimal amount = orderReceivable(o);   // đã làm tròn từng đơn
            if (amount.signum() <= 0) continue;       // không còn phải thu → bỏ qua

            totalDebtOrders++;
            totalDebtAmount = totalDebtAmount.add(amount);

            // ── Sắp đến hạn / quá hạn: so sánh debtDays đơn vs debtDays khách hàng ──
            int orderDebtDays    = o.getDebtDays();
            int customerDebtDays = customerDebtDays(o);

            if (orderDebtDays > customerDebtDays) {
                overdueCount++;
                overdueAmount = overdueAmount.add(amount);
            } else { // orderDebtDays <= customerDebtDays  (bao gồm cả == 0)
                nearingCount++;
                nearingAmount = nearingAmount.add(amount);
            }

            // ── Phân tuổi nợ theo số ngày kể từ khi tạo đơn tới hôm nay ──
            long ageDays = daysSinceCreated(o, today);
            if (ageDays <= 30)      aging0to30  = aging0to30.add(amount);
            else if (ageDays <= 60) aging31to60 = aging31to60.add(amount);
            else if (ageDays <= 90) aging61to90 = aging61to90.add(amount);
            else                    aging90plus = aging90plus.add(amount);
        }

        return new DebtStats(nearingCount, overdueCount, totalDebtOrders,
                nearingAmount, overdueAmount, totalDebtAmount,
                aging0to30, aging31to60, aging61to90, aging90plus);
    }

    /**
     * Số tiền chưa thu của 1 đơn, làm tròn về đồng (HALF_UP), tối thiểu 0:
     * UNPAID → round(finalAmount); PARTIAL → round(finalAmount − paidAmount).
     */
    public static BigDecimal orderReceivable(Order o) {
        BigDecimal fin  = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal paid = o.getPaidAmount()  != null ? o.getPaidAmount()  : BigDecimal.ZERO;
        BigDecimal base = (o.getPaymentStatus() == PaymentStatus.PARTIAL)
                ? fin.subtract(paid)   // PARTIAL: phần còn lại
                : fin;                 // UNPAID (và mặc định): toàn bộ finalAmount
        if (base.signum() <= 0) return BigDecimal.ZERO;
        return base.setScale(0, RoundingMode.HALF_UP);
    }

    /** debtDays của khách hàng gắn với đơn (null/không có khách → 0). */
    private static int customerDebtDays(Order o) {
        if (o.getCustomer() == null || o.getCustomer().getDebtDays() == null) return 0;
        return o.getCustomer().getDebtDays();
    }

    /** Số ngày kể từ ngày tạo đơn đến hôm nay (tối thiểu 0). */
    private static long daysSinceCreated(Order o, LocalDate today) {
        if (o.getCreatedAt() == null || o.getCreatedAt() <= 0) return 0;
        LocalDate created = Instant.ofEpochMilli(o.getCreatedAt()).atZone(VN).toLocalDate();
        long days = ChronoUnit.DAYS.between(created, today);
        return Math.max(0, days);
    }
}
