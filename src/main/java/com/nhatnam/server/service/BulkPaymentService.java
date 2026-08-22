package com.nhatnam.server.service;

import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.service.serviceimpl.OrderServiceImpl;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PHIẾU THU GỘP NHIỀU ĐƠN của cùng một khách hàng.
 *
 * <p>Nghiệp vụ: seller báo khách đã chuyển khoản một lần cho vài đơn. Kế toán lập MỘT phiếu
 * thu, nhập tổng số tiền nhận được, hệ thống chia về từng đơn.
 *
 * <p>Ví dụ: ba đơn 101.000 + 202.000 + 96.000 = 399.000. Khách chuyển 390.000. Kế toán tick
 * "bỏ phần dư" → cả ba đơn chuyển PAID, phần thiếu 9.000 ghi vào đơn CUỐI CÙNG.
 *
 * <h3>Ràng buộc</h3>
 * <ol>
 *   <li><b>Thu đủ tổng.</b> Thiếu thì phải nằm trong hạn mức bỏ dư, và phần thiếu chỉ được
 *       gán vào đơn cuối — chia đều phần lẻ ra nhiều đơn sẽ tạo ra những số tiền không
 *       khớp với bất kỳ chứng từ nào.</li>
 * </ol>
 *
 * <p>Cho phép gộp đơn của nhiều khách hàng, cũng như trộn đơn chưa giao và đã giao.
 * Mỗi đơn được ghi nhận qua đúng luồng của nó (prepayment / partialPayment).</p>
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class BulkPaymentService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final OrderServiceImpl orderServiceImpl;

    /** Trần bỏ phần dư cho CẢ PHIẾU (không phải từng đơn). */
    private static final BigDecimal MAX_WAIVE_AMOUNT = new BigDecimal("50000");
    private static final BigDecimal TOLERANCE = BigDecimal.ONE;

    private static final Set<OrderStatus> PRE_DELIVERY = Set.of(
            OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY);

    /**
     * XEM TRƯỚC — kiểm tra danh sách đơn có hợp lệ để gộp không, và tổng phải thu là bao nhiêu.
     *
     * <p>Không thay đổi gì. Màn hình gọi hàm này mỗi lần kế toán tick thêm/bớt một đơn.
     */
    @Transactional(readOnly = true)
    public BulkPreview preview(List<Long> orderIds) {
        List<Order> orders = _load(orderIds);
        if (orders.isEmpty())
            return BulkPreview.builder().valid(false).reason("Chưa chọn đơn nào").build();

        // Xác định nhóm đơn (chỉ dùng cho hiển thị, không chặn)
        boolean anyPre = orders.stream().anyMatch(o -> PRE_DELIVERY.contains(o.getStatus()));

        for (Order o : orders) {
            if (o.getStatus() == OrderStatus.CANCELLED || o.getStatus() == OrderStatus.FAILED)
                return BulkPreview.builder().valid(false)
                        .reason("Đơn " + o.getOrderCode() + " đã huỷ").build();
            if (o.getPaymentStatus() == PaymentStatus.PAID)
                return BulkPreview.builder().valid(false)
                        .reason("Đơn " + o.getOrderCode() + " đã thanh toán đủ").build();
        }

        BigDecimal totalDue = BigDecimal.ZERO;
        List<BulkOrderLine> lines = new ArrayList<>();
        for (Order o : orders) {
            BigDecimal due = _remaining(o);
            totalDue = totalDue.add(due);
            lines.add(BulkOrderLine.builder()
                    .orderId(o.getId())
                    .orderCode(o.getOrderCode())
                    .status(o.getStatus() != null ? o.getStatus().name() : null)
                    .finalAmount(o.getFinalAmount())
                    .paidAmount(o.getPaidAmount())
                    .remaining(due)
                    .build());
        }

        // Lấy customerId đầu tiên (hoặc null nếu khách lẻ) — dùng cho hiển thị.
        Long firstCustomerId = orders.get(0).getCustomer() != null
                ? orders.get(0).getCustomer().getId() : null;

        return BulkPreview.builder()
                .valid(true)
                .preDelivery(anyPre)
                .customerId(firstCustomerId)
                .customerName(orders.get(0).getCustomerName())
                .totalDue(totalDue.setScale(0, RoundingMode.HALF_UP))
                .maxWaive(MAX_WAIVE_AMOUNT)
                .orders(lines)
                .build();
    }

    /**
     * GHI NHẬN phiếu thu gộp.
     *
     * <p>Chia tiền theo thứ tự danh sách: đơn nào cũng thu ĐỦ phần còn thiếu của nó, cho tới
     * khi hết tiền. Phần thiếu (nếu có, và trong hạn mức bỏ dư) rơi vào đơn cuối cùng.
     *
     * <p>Từng đơn được ghi nhận qua đúng luồng của nó — đơn chưa giao đi
     * {@code recordPrepayment} (không đổi trạng thái), đơn đã giao đi
     * {@code recordPartialPayment}. Nhờ vậy mọi kiểm tra, log và thông báo của hai luồng đó
     * đều được giữ nguyên, thay vì nhân bản ở đây rồi trôi lệch dần.
     *
     * <p>Toàn bộ nằm trong MỘT transaction: một đơn lỗi thì cả phiếu rollback. Thu tiền
     * một phần rồi dừng giữa chừng sẽ để lại tình trạng không ai đối chiếu nổi.
     */
    @Transactional
    public BulkResult record(List<Long> orderIds, BigDecimal totalPaid, boolean waiveRemainder,
                             String paymentMethod, String bankName, String transactionRef,
                             User actor) {
        BulkPreview p = preview(orderIds);
        if (!p.isValid()) throw new RuntimeException(p.getReason());

        if (totalPaid == null || totalPaid.compareTo(BigDecimal.ZERO) <= 0)
            throw new RuntimeException("Số tiền thu phải lớn hơn 0");

        BigDecimal totalDue = p.getTotalDue();
        BigDecimal shortfall = totalDue.subtract(totalPaid);

        if (totalPaid.compareTo(totalDue.add(TOLERANCE)) > 0)
            throw new RuntimeException(String.format(
                    "Số tiền thu (%s đ) vượt quá tổng phải thu (%s đ)",
                    _money(totalPaid), _money(totalDue)));

        if (shortfall.compareTo(TOLERANCE) > 0) {
            if (!waiveRemainder)
                throw new RuntimeException(String.format(
                        "Phiếu thu gộp phải thu đủ %s đ. Còn thiếu %s đ — "
                                + "nếu do làm tròn, chọn \"thu đủ, bỏ phần dư\".",
                        _money(totalDue), _money(shortfall)));
            if (shortfall.compareTo(MAX_WAIVE_AMOUNT) > 0)
                throw new RuntimeException(String.format(
                        "Phần dư %s đ vượt mức được phép bỏ (tối đa %s đ).",
                        _money(shortfall), _money(MAX_WAIVE_AMOUNT)));
        }

        String actorName = actor != null
                ? (actor.getFullName() != null ? actor.getFullName() : actor.getUsername())
                : null;
        Long actorId = actor != null ? actor.getId() : null;

        List<Order> orders = _load(orderIds);
        BigDecimal remainingCash = totalPaid;
        List<String> applied = new ArrayList<>();

        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            boolean isLast = i == orders.size() - 1;
            BigDecimal due = _remaining(o);
            if (due.compareTo(BigDecimal.ZERO) <= 0) continue;

            // Đơn cuối nhận nốt số tiền còn lại — chính là chỗ hấp thụ phần dư bị bỏ.
            BigDecimal amount = isLast ? remainingCash.min(due) : due.min(remainingCash);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) break;

            boolean waiveThis = isLast && amount.compareTo(due) < 0;

            if (PRE_DELIVERY.contains(o.getStatus())) {
                orderService.recordPrepayment(o.getId(), amount, waiveThis, actorName,
                        paymentMethod, bankName, transactionRef, actorId);
            } else {
                orderService.recordPartialPayment(o.getId(), amount, o.getDebtDays(), actorName,
                        paymentMethod, bankName, transactionRef, actorId);
            }

            remainingCash = remainingCash.subtract(amount);
            applied.add(o.getOrderCode());
        }

        log.info("[BulkPayment] Thu gộp {} đ cho {} đơn của khách {} — {}",
                _money(totalPaid), applied.size(), p.getCustomerName(), applied);

        return BulkResult.builder()
                .orderCodes(applied)
                .totalPaid(totalPaid)
                .totalDue(totalDue)
                .waived(shortfall.compareTo(TOLERANCE) > 0 ? shortfall : BigDecimal.ZERO)
                .build();
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Nạp đơn theo ĐÚNG THỨ TỰ id được gửi lên.
     *
     * <p>Thứ tự quan trọng: đơn cuối trong danh sách là đơn hấp thụ phần dư. Dùng
     * {@code findAllById} trực tiếp sẽ trả về theo thứ tự của DB và phần dư rơi vào đơn
     * ngẫu nhiên.
     */
    private List<Order> _load(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Order> out = new ArrayList<>();
        for (Long id : ids) {
            orderRepository.findById(id).ifPresent(out::add);
        }
        return out;
    }

    private BigDecimal _remaining(Order o) {
        BigDecimal fin = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal paid = o.getPaidAmount() != null ? o.getPaidAmount() : BigDecimal.ZERO;
        return fin.subtract(paid).max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP);
    }

    private String _money(BigDecimal v) {
        return String.format("%,d", v != null ? v.longValue() : 0L).replace(',', '.');
    }

    // ── DTO ──────────────────────────────────────────────────────────────────

    @Data @Builder
    public static class BulkPreview {
        private boolean valid;
        private String reason;
        /** true = nhóm đơn CHƯA GIAO (thu trước); false = nhóm đã giao (thu công nợ). */
        private boolean preDelivery;
        private Long customerId;
        private String customerName;
        private BigDecimal totalDue;
        private BigDecimal maxWaive;
        private List<BulkOrderLine> orders;
    }

    @Data @Builder
    public static class BulkOrderLine {
        private Long orderId;
        private String orderCode;
        private String status;
        private BigDecimal finalAmount;
        private BigDecimal paidAmount;
        private BigDecimal remaining;
    }

    @Data @Builder
    public static class BulkResult {
        private List<String> orderCodes;
        private BigDecimal totalPaid;
        private BigDecimal totalDue;
        private BigDecimal waived;
    }
}
