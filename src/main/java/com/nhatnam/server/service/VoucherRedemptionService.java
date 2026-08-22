package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.repository.OrderLogRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.VoucherUsageRepository;
import com.nhatnam.server.service.serviceimpl.OrderServiceImpl;
import org.springframework.context.annotation.Lazy;
import com.nhatnam.server.repository.ProductRepository;
import com.nhatnam.server.repository.VoucherRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * THANH TOÁN ĐƠN HÀNG BẰNG VOUCHER.
 *
 * <p>Khách đưa phiếu (giấy hoặc ảnh chụp), nhân viên nhập mã hoặc quét QR. Hệ thống kiểm
 * tra voucher rồi ghi nhận một khoản thu với {@code paymentMethod = "VOUCHER"}.
 *
 * <h3>Thanh toán một phần là mặc định, không phải ngoại lệ</h3>
 * Voucher có hạn mức cố định nên hiếm khi khớp đúng giá trị đơn. Service này luôn tính
 * ra <b>số tiền áp dụng được</b> = min(số dư voucher, số tiền đơn còn thiếu, tổng tiền
 * các mặt hàng đủ điều kiện) rồi ghi nhận đúng bằng đó. Phần còn lại của đơn vẫn thu
 * bằng tiền mặt / chuyển khoản như bình thường, và mỗi lần thu là một
 * {@link PaymentTransaction} riêng — nhờ vậy đơn 3tr trả 1tr mặt + 1tr CK + 1tr voucher
 * lưu được đủ ba dòng với ba hình thức khác nhau.
 *
 * <h3>Vì sao trừ voucher và ghi thu tiền phải nằm chung một transaction</h3>
 * Nếu trừ voucher xong mà ghi thu thất bại, khách mất tiền trong voucher nhưng đơn vẫn
 * ghi nợ — kiểu lỗi này không ai phát hiện cho tới khi khách quay lại khiếu nại. Cả hai
 * thao tác đặt trong {@code @Transactional} để cùng thành công hoặc cùng bị rollback.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class VoucherRedemptionService {

    private final VoucherRepository voucherRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final VoucherUsageRepository voucherUsageRepository;
    private final OrderLogRepository orderLogRepository;
    /**
     * @Lazy để cắt vòng phụ thuộc: OrderServiceImpl → (thông báo, kho…) và ngược lại
     *       service này cần hỏi nó về quy tắc thu tiền trước.
     */
    @Lazy
    private final OrderServiceImpl orderServiceImpl;

    private final OrderService orderService;

    /** Giá trị ghi vào {@code payment_method} — FE và báo cáo đều nhận diện bằng chuỗi này. */
    public static final String PAYMENT_METHOD_VOUCHER = "VOUCHER";

    // ════════════════════════════════════════════════════════════════════════
    // XEM TRƯỚC
    // ════════════════════════════════════════════════════════════════════════

    /**
     * KIỂM TRA VOUCHER TRƯỚC KHI ÁP DỤNG — không thay đổi gì.
     *
     * <p>Màn hình gọi hàm này ngay khi nhân viên nhập xong mã, để hiện trước "voucher này
     * trừ được bao nhiêu" và lý do nếu không dùng được. Nhân viên đang đứng trước mặt
     * khách cần biết ngay, không phải bấm áp dụng rồi mới nhận lỗi.
     */
    @Transactional(readOnly = true)
    public RedemptionPreview preview(String code, Long orderId) {
        Voucher v = _findByCode(code);
        Order order = _findOrder(orderId);
        return _evaluate(v, order);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ÁP DỤNG
    // ════════════════════════════════════════════════════════════════════════

    /**
     * ÁP DỤNG VOUCHER VÀO ĐƠN.
     *
     * @param requestedAmount số tiền muốn dùng; null = dùng tối đa có thể.
     *                        Cho phép nhập tay để nhân viên giữ lại số dư voucher cho
     *                        lần mua sau khi khách yêu cầu.
     * @param actor           người thao tác (seller / kế toán / owner)
     */
    @Transactional
    public RedemptionResult redeem(String code, Long orderId, BigDecimal requestedAmount, User actor) {
        // KHOÁ voucher ngay từ đầu transaction — xem javadoc findByCodeForUpdate.
        // preview() cố tình KHÔNG khoá: nó chỉ đọc để hiển thị, khoá ở đó sẽ giữ voucher
        // suốt thời gian nhân viên còn đang nhìn màn hình.
        Voucher v = _findByCodeLocked(code);
        Order order = _findOrder(orderId);

        RedemptionPreview p = _evaluate(v, order);
        if (!p.isApplicable())
            throw new BusinessException(p.getReason());

        BigDecimal amount = p.getApplicableAmount();
        if (requestedAmount != null) {
            if (requestedAmount.compareTo(BigDecimal.ZERO) <= 0)
                throw new BusinessException("Số tiền sử dụng phải lớn hơn 0");
            if (requestedAmount.compareTo(amount) > 0)
                throw new BusinessException("Số tiền vượt quá mức voucher này áp dụng được ("
                        + _money(amount) + " đ)");
            amount = requestedAmount.setScale(0, java.math.RoundingMode.DOWN);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("Số tiền sử dụng phải lớn hơn 0");

        // 1) Trừ voucher
        long used = v.getUsedAmount() != null ? v.getUsedAmount() : 0L;
        v.setUsedAmount(used + amount.longValue());
        if (v.remaining() <= 0) v.setStatus(Voucher.VoucherStatus.USED);
        voucherRepository.save(v);

        // 2) Ghi nhận khoản thu. Dùng chung đường ghi nhận với tiền mặt / chuyển khoản
        //    thay vì tự cộng paidAmount: đường đó còn tính lại paymentStatus, sinh
        //    PaymentTransaction và bắn thông báo — tự làm tay sẽ bỏ sót.
        int debtDays = order.getDebtDays();
        orderService.recordPartialPayment(
                orderId,
                amount,
                debtDays,
                _nameOf(actor),
                PAYMENT_METHOD_VOUCHER,
                null,                    // không có ngân hàng
                v.getCode(),             // transactionRef = mã voucher, để đối chiếu về sau
                actor != null ? actor.getId() : null);

        Order after = _findOrder(orderId);

        // 3) Lịch sử sử dụng — tra được cả hai chiều đơn ↔ voucher.
        voucherUsageRepository.save(VoucherUsage.builder()
                .voucher(v)
                .voucherCode(v.getCode())
                .order(after)
                .orderCode(after.getOrderCode())
                .amount(amount)
                .remainingAfter(v.remaining())
                .usedByName(_nameOf(actor))
                .usedById(actor != null ? actor.getId() : null)
                .createdAt(System.currentTimeMillis())
                .build());

        // 4) Nhật ký đơn hàng — ghi rõ mã voucher và số tiền, để người xem lịch sử đơn
        //    không phải mở thêm màn hình khác mới biết đơn được trừ bằng phiếu nào.
        orderLogRepository.save(OrderLog.builder()
                .order(after)
                .action("VOUCHER_PAYMENT")
                .actorName(_nameOf(actor))
                .actorRole(actor != null && actor.getRole() != null ? actor.getRole().name() : null)
                .note("Thanh toán bằng voucher " + v.getCode()
                        + " — " + _money(amount) + " đ"
                        + " (số dư còn " + _money(BigDecimal.valueOf(v.remaining())) + " đ)")
                .createdAt(System.currentTimeMillis())
                .build());

        log.info("[Voucher] Áp dụng {} cho đơn {} — {} đ, còn lại voucher {} đ",
                v.getCode(), order.getOrderCode(), _money(amount), _money(BigDecimal.valueOf(v.remaining())));

        return RedemptionResult.builder()
                .voucherCode(v.getCode())
                .appliedAmount(amount)
                .voucherRemaining(BigDecimal.valueOf(v.remaining()))
                .orderPaidAmount(after.getPaidAmount())
                .orderRemaining(_orderRemaining(after))
                .orderPaymentStatus(after.getPaymentStatus() != null
                        ? after.getPaymentStatus().name() : null)
                .build();
    }

    /** Voucher đã trừ vào một đơn — dùng ở chi tiết đơn hàng. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> usagesOfOrder(Long orderId) {
        return voucherUsageRepository.findByOrder_IdOrderByCreatedAtAsc(orderId).stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("voucherId",   u.getVoucher() != null ? u.getVoucher().getId() : null);
                    m.put("voucherCode", u.getVoucherCode());
                    m.put("amount",      u.getAmount());
                    m.put("usedByName",  u.getUsedByName());
                    m.put("createdAt",   u.getCreatedAt());
                    return m;
                })
                .toList();
    }

    /** Lịch sử tiêu của một voucher — voucher này đã dùng cho những đơn nào. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> historyOfVoucher(Long voucherId) {
        return voucherUsageRepository.findByVoucher_IdOrderByCreatedAtDesc(voucherId).stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("orderId",        u.getOrder() != null ? u.getOrder().getId() : null);
                    m.put("orderCode",      u.getOrderCode());
                    m.put("amount",         u.getAmount());
                    m.put("remainingAfter", u.getRemainingAfter());
                    m.put("usedByName",     u.getUsedByName());
                    m.put("createdAt",      u.getCreatedAt());
                    return m;
                })
                .toList();
    }

    // ════════════════════════════════════════════════════════════════════════
    // ĐÁNH GIÁ
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Tính xem voucher dùng được cho đơn này không, và được bao nhiêu.
     *
     * <p>Trả về đối tượng mô tả thay vì ném lỗi, vì {@link #preview} cần hiển thị lý do
     * cho nhân viên chứ không phải làm hỏng cả request.
     */
    private RedemptionPreview _evaluate(Voucher v, Order order) {
        long now = System.currentTimeMillis();

        // ── Trạng thái voucher ───────────────────────────────────────────────
        Voucher.VoucherStatus st = v.effectiveStatus(now);
        if (st == Voucher.VoucherStatus.CANCELLED)
            return _reject(v, "Voucher đã bị thu hồi");
        if (st == Voucher.VoucherStatus.EXPIRED)
            return _reject(v, "Voucher đã hết hạn sử dụng");
        if (st == Voucher.VoucherStatus.USED)
            return _reject(v, "Voucher đã sử dụng hết hạn mức");
        if (v.getValidFrom() != null && now < v.getValidFrom())
            return _reject(v, "Voucher chưa tới ngày có hiệu lực");

        // ── Voucher phải thuộc đúng khách của đơn ────────────────────────────
        // Voucher là quà tặng đích danh, không phải mã khuyến mãi dùng chung. Cho dùng
        // chéo khách sẽ mở đường cho việc gom voucher của nhiều khách để trừ vào một đơn.
        Long voucherCustomerId = v.getCustomer() != null ? v.getCustomer().getId() : null;
        Long orderCustomerId = order.getCustomer() != null ? order.getCustomer().getId() : null;
        if (voucherCustomerId != null && !voucherCustomerId.equals(orderCustomerId))
            return _reject(v, "Voucher này thuộc về khách hàng khác");

        // ── Đơn còn nợ bao nhiêu ─────────────────────────────────────────────
        if (order.getPaymentStatus() == PaymentStatus.PAID)
            return _reject(v, "Đơn hàng đã thanh toán đủ");
        BigDecimal orderRemaining = _orderRemaining(order);
        if (orderRemaining.compareTo(BigDecimal.ZERO) <= 0)
            return _reject(v, "Đơn hàng không còn số tiền cần thu");

        // ── Tiền hàng đủ điều kiện theo phạm vi voucher ──────────────────────
        BigDecimal eligible = _eligibleSubtotal(v, order);
        if (eligible.compareTo(BigDecimal.ZERO) <= 0)
            return _reject(v, "Đơn hàng không có mặt hàng nào thuộc phạm vi áp dụng của voucher");

        BigDecimal voucherRemaining = BigDecimal.valueOf(v.remaining());

        // Lấy min của ba giới hạn. Giới hạn "tiền hàng đủ điều kiện" là quan trọng nhất:
        // voucher chỉ áp dụng cho vài danh mục thì không được trừ vào phần tiền của các
        // mặt hàng ngoài phạm vi, dù đơn còn nợ nhiều hơn.
        // LÀM TRÒN XUỐNG về đồng chẵn. Voucher lưu hạn mức bằng số nguyên (Long), nên
        // nếu trừ một số có phần lẻ thì số dư voucher sẽ bị cắt cụt khi ép về Long và
        // khách mất vài đồng mỗi lần dùng. Làm tròn ngay từ đây để số hiển thị cho khách
        // đúng bằng số thực sự bị trừ.
        BigDecimal applicable = voucherRemaining.min(orderRemaining).min(eligible)
                .setScale(0, java.math.RoundingMode.DOWN);

        // ── ĐƠN THU TIỀN TRƯỚC PHẢI TRẢ HẾT TRONG MỘT LẦN ────────────────────
        // Voucher không phủ hết đơn thì phần còn lại sẽ phải thu bằng cách khác, và đơn
        // nằm lửng ở PARTIAL — kho vẫn bị chặn giao, nhưng nhìn vào thì tưởng đã xử lý.
        // Chặn ngay ở đây để nhân viên biết phải thu trọn gói bằng hình thức khác.
        BigDecimal applicableCheck = voucherRemaining.min(orderRemaining).min(eligible)
                .setScale(0, java.math.RoundingMode.DOWN);
        if (orderServiceImpl.isPrepaymentRequired(order)
                && applicableCheck.compareTo(orderRemaining) < 0) {
            return _reject(v, "Đơn này phải thanh toán đủ trong một lần ("
                    + orderServiceImpl.prepaymentReason(order).toLowerCase()
                    + "). Voucher chỉ trừ được " + _money(applicableCheck)
                    + " đ trong tổng " + _money(orderRemaining) + " đ còn thiếu.");
        }

        return RedemptionPreview.builder()
                .applicable(true)
                .voucherCode(v.getCode())
                .voucherTitle(v.getTitle())
                .voucherAmount(BigDecimal.valueOf(v.getAmount() != null ? v.getAmount() : 0L))
                .voucherRemaining(voucherRemaining)
                .validTo(v.getValidTo())
                .customerName(v.getCustomer() != null ? v.getCustomer().getName() : null)
                .applyScope(v.getApplyScope() != null ? v.getApplyScope().name() : "ALL")
                .orderRemaining(orderRemaining)
                .eligibleSubtotal(eligible)
                .applicableAmount(applicable)
                .build();
    }

    /**
     * TỔNG TIỀN CÁC MẶT HÀNG THUỘC PHẠM VI VOUCHER.
     *
     * <p>Phạm vi ALL ⇒ lấy luôn tổng tiền đơn. Phạm vi CATEGORY/PRODUCT ⇒ cộng subtotal
     * của các dòng khớp.
     *
     * <p>Đối chiếu danh mục dựa trên {@code productId} tra ngược sang sản phẩm hiện tại,
     * KHÔNG dùng {@code categorySnapshot} trên dòng đơn: snapshot lưu TÊN danh mục còn
     * voucher lưu ID, so tên sẽ sai ngay khi có hai danh mục trùng tên hoặc danh mục
     * được đổi tên sau khi đơn đã tạo.
     */
    private BigDecimal _eligibleSubtotal(Voucher v, Order order) {
        List<OrderItem> items = order.getOrderItems() != null ? order.getOrderItems() : List.of();

        if (v.getApplyScope() == null || v.getApplyScope() == Voucher.ApplyScope.ALL)
            return order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO;

        if (v.getApplyScope() == Voucher.ApplyScope.PRODUCT) {
            Set<Long> allowed = v.getProductIds() != null ? v.getProductIds() : Set.of();
            return items.stream()
                    .filter(i -> i.getProductId() != null && allowed.contains(i.getProductId()))
                    .map(this::_lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        // CATEGORY
        Set<Long> allowedCategories = v.getCategoryIds() != null ? v.getCategoryIds() : Set.of();
        Set<Long> productIds = new HashSet<>();
        for (OrderItem i : items) if (i.getProductId() != null) productIds.add(i.getProductId());
        if (productIds.isEmpty()) return BigDecimal.ZERO;

        Map<Long, Long> categoryOfProduct = new HashMap<>();
        for (Product p : productRepository.findAllById(productIds))
            categoryOfProduct.put(p.getId(), p.getCategoryId());

        return items.stream()
                .filter(i -> i.getProductId() != null)
                .filter(i -> allowedCategories.contains(categoryOfProduct.get(i.getProductId())))
                .map(this::_lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Tiền một dòng đơn, đã gồm VAT nếu có. */
    private BigDecimal _lineTotal(OrderItem i) {
        BigDecimal sub = i.getSubtotal() != null ? i.getSubtotal() : BigDecimal.ZERO;
        BigDecimal vat = i.getVatAmount() != null ? i.getVatAmount() : BigDecimal.ZERO;
        return sub.add(vat);
    }

    private BigDecimal _orderRemaining(Order o) {
        BigDecimal fin = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal paid = o.getPaidAmount() != null ? o.getPaidAmount() : BigDecimal.ZERO;
        return fin.subtract(paid).max(BigDecimal.ZERO);
    }

    private RedemptionPreview _reject(Voucher v, String reason) {
        return RedemptionPreview.builder()
                .applicable(false)
                .reason(reason)
                .voucherCode(v.getCode())
                .voucherTitle(v.getTitle())
                .voucherRemaining(BigDecimal.valueOf(v.remaining()))
                .validTo(v.getValidTo())
                .customerName(v.getCustomer() != null ? v.getCustomer().getName() : null)
                .applicableAmount(BigDecimal.ZERO)
                .build();
    }

    /**
     * Tra voucher theo mã.
     *
     * <p>Chuẩn hoá chữ hoa và bỏ khoảng trắng: mã nhập tay từ phiếu giấy rất hay dính
     * khoảng trắng thừa hoặc gõ thường, còn mã từ QR thì luôn sạch. Không chuẩn hoá sẽ
     * làm nhân viên tưởng voucher không tồn tại.
     */
    /** Như {@link #_findByCode} nhưng khoá bản ghi tới hết transaction. */
    private Voucher _findByCodeLocked(String code) {
        String normalized = _normalizeCode(code);
        return voucherRepository.findByCodeForUpdate(normalized)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy voucher có mã " + normalized));
    }

    private String _normalizeCode(String code) {
        if (code == null || code.isBlank())
            throw new BusinessException("Vui lòng nhập mã voucher");
        return code.trim().toUpperCase().replaceAll("\\s+", "");
    }

    private Voucher _findByCode(String code) {
        if (code == null || code.isBlank())
            throw new BusinessException("Vui lòng nhập mã voucher");
        String normalized = code.trim().toUpperCase().replaceAll("\\s+", "");
        return voucherRepository.findByCodeIgnoreCase(normalized)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy voucher có mã " + normalized));
    }

    private Order _findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng #" + orderId));
    }

    private String _nameOf(User u) {
        if (u == null) return null;
        return u.getFullName() != null ? u.getFullName() : u.getUsername();
    }

    private String _money(BigDecimal v) {
        return String.format("%,d", v != null ? v.longValue() : 0L).replace(',', '.');
    }

    // ════════════════════════════════════════════════════════════════════════
    // DTO
    // ════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class RedemptionPreview {
        /** true = dùng được cho đơn này. */
        private boolean applicable;
        /** Lý do không dùng được — chỉ có nghĩa khi {@code applicable = false}. */
        private String reason;

        private String voucherCode;
        private String voucherTitle;
        private String customerName;
        private BigDecimal voucherAmount;
        private BigDecimal voucherRemaining;
        private Long validTo;
        private String applyScope;

        /** Số tiền đơn còn phải thu. */
        private BigDecimal orderRemaining;
        /** Tổng tiền các mặt hàng thuộc phạm vi voucher. */
        private BigDecimal eligibleSubtotal;
        /** Số tiền voucher trừ được lần này = min của ba giới hạn trên. */
        private BigDecimal applicableAmount;
    }

    @Data
    @Builder
    public static class RedemptionResult {
        private String voucherCode;
        private BigDecimal appliedAmount;
        private BigDecimal voucherRemaining;
        private BigDecimal orderPaidAmount;
        private BigDecimal orderRemaining;
        private String orderPaymentStatus;
    }
}
