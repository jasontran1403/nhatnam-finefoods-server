package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.request.CreateOrderRequest;
import com.nhatnam.server.dto.request.UpdateOrderItemsRequest;
import com.nhatnam.server.dto.response.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.enumtype.VatRate;
import com.nhatnam.server.exception.PriceChangedException;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.service.*;
import com.nhatnam.server.entity.SellerKpi;
import com.nhatnam.server.repository.SellerKpiRepository;
import com.nhatnam.server.utils.DeliveryZoneUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Locale;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {
    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper;
    private final OrderRepository               orderRepository;
    private final ProductRepository             productRepository;
    private final IngredientRepository          ingredientRepository;
    private final ProductPriceTierRepository    priceTierRepository;
    private final UserRepository                userRepository;
    private final ProductIngredientRepository   productIngredientRepository;
    private final CustomerRepository            customerRepository;
    private final WarehouseRepository           warehouseRepository;
    private final IngredientStockRepository     ingredientStockRepository;
    private final WarehouseReceiptRepository    warehouseReceiptRepository;
    private final WarehouseService              warehouseService;
    private final OrderLogRepository            orderLogRepository;
    private final PaymentTransactionRepository  paymentTransactionRepository;
    private final NotificationService           notificationService;
    private final SellerKpiRepository           sellerKpiRepository;
    private final FifoDeductService             fifoDeductService;
    private final OrderStockDeductionRepository orderStockDeductionRepository;
    private final IngredientExpiryRepository    ingredientExpiryRepository;
    private final com.nhatnam.server.service.CustomerContractService customerContractService;
    private final OrderCodePrefixRepository     orderCodePrefixRepository;
    // BUG FIX: race-safe stock layer
    private final com.nhatnam.server.service.StockMutationService stockMutationService;

    private static final String RETAIL_GUEST_LABEL = "Khách vãng lai";
    private static final ZoneId TZ = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final BigDecimal PRICE_DIFF_THRESHOLD = new BigDecimal("1.00");

    private String resolveCustomerDisplayName(String customerName) {
        if (customerName == null || customerName.isBlank()) return RETAIL_GUEST_LABEL;
        return customerName;
    }

    // ════════════════════════════════════════════════════════════════
    // ADMIN: danh sách đơn hàng
    // ════════════════════════════════════════════════════════════════
    @Override
    public Page<OrderListResponse> getOrders(String search, OrderStatus status, Pageable pageable) {
        return orderRepository.findAllWithItems(search, status, pageable)
                .map(order -> OrderListResponse.builder()
                        .id(order.getId())
                        .orderCode(order.getOrderCode())
                        .customerName(resolveCustomerDisplayName(order.getCustomerName()))
                        .customerPhone(order.getCustomerPhone())
                        .receiverName(resolveCustomerDisplayName(order.getCustomerName()))
                        .receiverPhone(order.getCustomerPhone())
                        .finalAmount(order.getTotalAmount())
                        .createdAt(order.getCreatedAt())
                        .status(order.getStatus().name())
                        .build());
    }

    // ════════════════════════════════════════════════════════════════
    // ADMIN: chi tiết đơn hàng
    // ════════════════════════════════════════════════════════════════
    @Override
    public OrderDetailResponse getOrderDetail(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));

        List<OrderItemDetail> items = order.getOrderItems().stream().map(item -> {
            List<IngredientSnapshot> ingredients = item.getOrderItemIngredients().stream()
                    .map(ii -> IngredientSnapshot.builder()
                            .ingredientId(ii.getIngredientId())
                            .ingredientName(ii.getIngredientName())
                            .ingredientImageUrl(ii.getIngredientImageUrl())
                            .quantityUsed(ii.getQuantityUsed())
                            .unit(ii.getUnit())
                            .build())
                    .collect(Collectors.toList());

            return OrderItemDetail.builder()
                    .productId(item.getProductId())
                    .productName(item.getProductName())
                    .productImageUrl(item.getProductImageUrl())
                    .priceName(buildPriceLabel(item))
                    .unitPrice(item.getUnitPrice())
                    .quantity(item.getQuantity())
                    .subtotal(item.getSubtotal())
                    .unit(item.getUnit())
                    .ingredients(ingredients)
                    .build();
        }).collect(Collectors.toList());

        return OrderDetailResponse.builder()
                .id(order.getId())
                .orderCode(order.getOrderCode())
                .customerName(resolveCustomerDisplayName(order.getCustomerName()))
                .customerPhone(order.getCustomerPhone())
                .shippingAddress(order.getShippingAddress())
                .notes(order.getNotes())
                .totalAmount(order.getTotalAmount())
                .discountAmount(order.getDiscountAmount())
                .finalAmount(order.getFinalAmount())
                .status(order.getStatus().name())
                .paymentStatus(order.getPaymentStatus().name())
                .paymentMethod(order.getPaymentMethod())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .items(items)
                .build();
    }

    private String buildPriceLabel(OrderItem item) {
        if (item.getPriceMode() == null) return null;
        return switch (item.getPriceMode()) {
            case "TIER"             -> item.getTierName() != null ? item.getTierName() : "Khung giá";
            case "DISCOUNT_PERCENT" -> "Giảm " + item.getDiscountPercent() + "%";
            default                 -> "Giá gốc";
        };
    }

    @Transactional
    public OrderResponse completeOrderBySeller(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn #" + orderId));
        assertNotCancelled(order);

        boolean statusOk = order.getStatus() == OrderStatus.DELIVERING
                || order.getStatus() == OrderStatus.PENDING_PAYMENT;
        boolean partialOk = order.getPaymentStatus() == PaymentStatus.PARTIAL;

        if (!statusOk && !partialOk)
            throw new RuntimeException("Không thể hoàn thành đơn ở trạng thái hiện tại");

        order.setStatus(OrderStatus.COMPLETED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaidAmount(order.getFinalAmount());
        order.setUpdatedAt(System.currentTimeMillis());
        Order saved = orderRepository.save(order);
        updateSellerKpi(saved);
        return mapToResponse(saved);
    }

    @Transactional
    public OrderResponse updatePaymentMethodBySeller(Long orderId, String paymentMethod) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn #" + orderId));
        assertNotCancelled(order);

        if (order.getStatus() != OrderStatus.DELIVERING
                && order.getStatus() != OrderStatus.PENDING_PAYMENT)
            throw new RuntimeException("Chỉ được đổi phương thức khi đơn đang Giao hàng hoặc Chờ thanh toán");

        assertDebtAllowed(order.getCustomer(), paymentMethod);
        assertPaymentMethodAllowedForZone(order.getCustomer(), paymentMethod,
                order.getProvinceName(), order.getWardName(), order.getDeliveryAddress());

        order.setPaymentMethod(paymentMethod);
        boolean isDebt = "DEBT".equalsIgnoreCase(paymentMethod) || "OTHER".equalsIgnoreCase(paymentMethod);
        if (isDebt) {
            int debtDays = 1;
            if (order.getCustomer() != null && order.getCustomer().getDebtDays() > 0)
                debtDays = order.getCustomer().getDebtDays();
            order.setDebtDays(debtDays);
            order.setPendingPaymentAt(System.currentTimeMillis());
        } else {
            order.setDebtDays(0);
            order.setPendingPaymentAt(0L);
        }
        order.setUpdatedAt(System.currentTimeMillis());
        return mapToResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    public OrderResponse confirmWaiveRemainder(Long orderId, BigDecimal actualPaid,
                                               String actorName, Long actorUserId,
                                               String paymentMethod, String bankName,
                                               String transactionRef) {
        if (actualPaid == null || actualPaid.compareTo(BigDecimal.ZERO) <= 0)
            throw new RuntimeException("Số tiền thực thu phải lớn hơn 0");

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);

        if (order.getStatus() != OrderStatus.DELIVERING && order.getStatus() != OrderStatus.PENDING_PAYMENT)
            throw new RuntimeException("Chỉ có thể xác nhận ở trạng thái 'Đang giao' hoặc 'Chờ thanh toán'");
        if (actualPaid.compareTo(order.getFinalAmount()) >= 0)
            throw new RuntimeException("Số tiền >= tổng đơn, dùng thu tiền thông thường");

        long now = System.currentTimeMillis();
        BigDecimal currentPaid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal thisPayment = actualPaid.subtract(currentPaid);
        if (thisPayment.compareTo(BigDecimal.ZERO) <= 0)
            throw new RuntimeException("Số tiền thực thu phải lớn hơn số đã thu trước đó");

        String method = paymentMethod != null ? paymentMethod.toUpperCase()
                : (order.getPaymentMethod() != null ? order.getPaymentMethod() : "CASH");
        paymentTransactionRepository.save(PaymentTransaction.builder()
                .order(order).amount(thisPayment).paymentMethod(method)
                .bankName(bankName).transactionRef(transactionRef).collectedBy(actorName)
                .createdAt(now)
                .note("Xác nhận bỏ số lẻ — thực thu: " + actualPaid.toPlainString()
                        + " / đơn: " + order.getFinalAmount().toPlainString())
                .build());

        order.setPaidAmount(actualPaid);
        order.setActualAmountPaid(actualPaid);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setStatus(OrderStatus.COMPLETED);
        order.setUpdatedAt(now);
        Order saved = orderRepository.save(order);

        log(saved, "WAIVE_REMAINDER", actorName, "ACCOUNTANT",
                String.format("Bỏ số lẻ %s đ — thực thu: %s / đơn: %s",
                        order.getFinalAmount().subtract(actualPaid).toPlainString(),
                        actualPaid.toPlainString(), order.getFinalAmount().toPlainString()));

        String payload = "{\"orderId\":" + orderId + ",\"orderCode\":\"" + saved.getOrderCode() + "\"}";
        notifyOrderUpdate(saved, "ORDER_PAID",
                "Đơn " + saved.getOrderCode() + " đã hoàn thành (bỏ số lẻ) bởi " + actorName,
                payload, actorUserId);
        updateSellerKpi(saved);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public OrderResponse recordPartialPayment(Long orderId, BigDecimal paidAmount,
                                              int debtDays, String actorName,
                                              String paymentMethod, String bankName,
                                              String transactionRef) {
        return recordPartialPayment(orderId, paidAmount, debtDays, actorName,
                paymentMethod, bankName, transactionRef, null);
    }

    @Override
    @Transactional
    public OrderResponse recordPartialPayment(Long orderId, BigDecimal paidAmount,
                                              int debtDays, String actorName,
                                              String paymentMethod, String bankName,
                                              String transactionRef, Long actorUserId) {
        if (paidAmount == null || paidAmount.compareTo(BigDecimal.ZERO) <= 0)
            throw new RuntimeException("Số tiền thanh toán phải lớn hơn 0");

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);

        // Đơn CHƯA GIAO đi qua luồng thu tiền trước: nó không đổi trạng thái đơn và bắt
        // buộc thu đủ. Chuyển hướng tại đây thay vì ném lỗi, vì thanh toán bằng voucher
        // hoàn toàn có thể diễn ra lúc đơn còn "Đang chuẩn bị".
        if (isPreDelivery(order))
            return recordPrepayment(orderId, paidAmount, false, actorName,
                    paymentMethod, bankName, transactionRef, actorUserId);

        if (order.getStatus() != OrderStatus.DELIVERING && order.getStatus() != OrderStatus.PENDING_PAYMENT)
            throw new RuntimeException("Chỉ có thể ghi nhận thanh toán ở trạng thái 'Đang giao' hoặc 'Chờ thanh toán'");

        BigDecimal currentPaid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newPaid     = currentPaid.add(paidAmount);
        BigDecimal finalAmount = order.getFinalAmount();

        // Dung sai 1 đồng: VND không có lẻ nên người dùng chỉ gõ được số nguyên, nhưng các đơn
        // cũ (tạo trước khi finalAmount được làm tròn nguyên ở nguồn) có thể còn lệch vài hào/đồng.
        // Không dùng dung sai này để thay đổi cách tính finalAmount — chỉ để so sánh "đã thu đủ".
        BigDecimal tolerance = BigDecimal.ONE;

        if (newPaid.compareTo(finalAmount.add(tolerance)) > 0)
            throw new RuntimeException(String.format(
                    "Tổng số tiền thu (%s) vượt quá giá trị đơn hàng (%s)",
                    newPaid.toPlainString(), finalAmount.toPlainString()));

        long now = System.currentTimeMillis();
        order.setPaidAmount(newPaid);
        order.setUpdatedAt(now);

        boolean isFullyPaid = newPaid.compareTo(finalAmount.subtract(tolerance)) >= 0;

        // ĐƠN CHƯA GIAO XONG thì THU TIỀN KHÔNG ĐƯỢC ĐÓNG ĐƠN.
        //
        // Với luồng thanh toán trước (khách không công nợ, hoặc giao ngoài địa bàn), kế
        // toán lập phiếu thu khi đơn còn PREPARING/DELIVERING. Nếu vẫn set COMPLETED như
        // cũ thì đơn biến mất khỏi hàng chờ của kho, hàng chưa ai giao mà hệ thống báo
        // xong — và trạng thái đó cũng chặn luôn markAsDelivering ở bước sau.
        //
        // Ở đây chỉ ghi nhận ĐÃ THANH TOÁN. Việc chuyển sang COMPLETED để cho
        // markAsDelivered lo: nó đã có sẵn nhánh "thu đủ rồi thì hoàn thành luôn".
        // Không dùng isPreDelivery(): tập đó KHÔNG gồm DELIVERING vì nó phục vụ tính công
        // nợ (hàng đã rời kho là đã ghi nợ). Ở đây câu hỏi khác — "hàng tới tay khách
        // chưa" — nên đơn đang trên đường giao vẫn phải tính là CHƯA giao xong.
        boolean deliveredAlready = order.getStatus() == OrderStatus.PENDING_PAYMENT
                || order.getStatus() == OrderStatus.COMPLETED;

        if (isFullyPaid) {
            order.setPaymentStatus(PaymentStatus.PAID);
            if (deliveredAlready) order.setStatus(OrderStatus.COMPLETED);
        } else {
            order.setPaymentStatus(PaymentStatus.PARTIAL);
            if (deliveredAlready) {
                order.setStatus(OrderStatus.PENDING_PAYMENT);
                order.setPendingPaymentAt(now);
            }
            order.setDebtDays(debtDays);
        }

        Order saved = orderRepository.save(order);

        String method = paymentMethod != null ? paymentMethod.toUpperCase() : order.getPaymentMethod();
        paymentTransactionRepository.save(PaymentTransaction.builder()
                .order(saved).amount(paidAmount)
                .paymentMethod(method != null ? method : "CASH")
                .bankName(bankName).transactionRef(transactionRef)
                .collectedBy(actorName).createdAt(now).build());

        log(saved, isFullyPaid ? "FULLY_PAID" : "PARTIAL_PAYMENT", actorName, "ACCOUNTANT",
                String.format("Thu: %s (%s) | Tổng đã thu: %s / %s",
                        paidAmount.toPlainString(), method,
                        newPaid.toPlainString(), finalAmount.toPlainString()));

        String msg = isFullyPaid
                ? "Đơn " + saved.getOrderCode() + " đã thanh toán đủ bởi " + actorName
                : "Đơn " + saved.getOrderCode() + " đã thanh toán 1 phần bởi " + actorName
                + " (" + paidAmount.toPlainString() + ")";
        String payload = "{\"orderId\":" + orderId + ",\"orderCode\":\"" + saved.getOrderCode() + "\"}";

        notifyOrderUpdate(saved, isFullyPaid ? "ORDER_PAID" : "ORDER_PARTIAL_PAID", msg, payload, actorUserId);
        if (isFullyPaid) updateSellerKpi(saved);
        return mapToResponse(saved);
    }

    // ════════════════════════════════════════════════════════════════
    // YÊU CẦU THANH TOÁN TRƯỚC (PREPAYMENT)
    // ════════════════════════════════════════════════════════════════

    /** Các trạng thái TRƯỚC KHI GIAO — đơn chưa rời kho. */
    private static final java.util.EnumSet<OrderStatus> PRE_DELIVERY_STATUSES =
            java.util.EnumSet.of(OrderStatus.PENDING, OrderStatus.CONFIRMED,
                    OrderStatus.PREPARING, OrderStatus.READY);

    /** Dung sai 1 đồng khi so sánh "đã thu đủ" (đơn cũ có thể còn lệch vài hào). */
    private static final BigDecimal PAY_TOLERANCE = BigDecimal.ONE;

    /**
     * MỨC "BỎ PHẦN DƯ" TỐI ĐA khi thu tiền trước.
     *
     * <p>Đơn thu trước bắt buộc trả HẾT trong một lần, nhưng thực tế quầy hay làm tròn:
     * đơn 202.000 khách đưa 200.000. Cho phép kế toán tick "thu đủ, bỏ phần dư" trong
     * hạn mức này.
     *
     * <p>Phải có trần, nếu không cờ đó thành cửa sau: thu 100.000 cho đơn 5 triệu rồi
     * tick bỏ dư là đơn được đánh dấu PAID và kho được phép giao. Lấy đúng ngưỡng
     * 50.000đ mà màn hình seller đang dùng cho thao tác tương đương.
     */
    private static final BigDecimal MAX_WAIVE_AMOUNT = new BigDecimal("50000");

    /**
     * Đơn này có bắt buộc thanh toán trước không?
     * Ưu tiên SNAPSHOT trên đơn; đơn cũ (null) thì fallback đọc từ khách hàng.
     */
    /**
     * ĐƠN NÀY CÓ BẮT BUỘC THU TIỀN TRƯỚC KHI GIAO KHÔNG.
     *
     * <p>Ba nguồn, xét theo thứ tự:
     * <ol>
     *   <li>Cờ đặt riêng trên đơn — người có thẩm quyền đã quyết cho đơn này.</li>
     *   <li>Cấu hình của khách hàng.</li>
     *   <li><b>Suy ra từ nghiệp vụ</b>: giao NGOÀI địa bàn TP.HCM cũ.</li>
     * </ol>
     *
     * <p>Nhánh (3) là quy tắc mới: xe đi xa, khách từ chối nhận là mất nguyên chuyến. Xem
     * {@link DeliveryZoneUtil} về lý do Bình Dương và Bà Rịa – Vũng Tàu vẫn tính là ngoài
     * địa bàn dù đã sáp nhập.
     *
     * <p>CHƯA xét điều kiện "khách không được cấp công nợ" — {@code invoiceDays} hiện mặc
     * định {@code -1} cho hầu hết khách, bật lên sẽ khiến gần như mọi đơn phải thu tiền
     * trước và kho kẹt hàng loạt. Cần làm sạch dữ liệu khách trước, rồi mới thêm nhánh đó.
     *
     * <p>Cờ trên đơn vẫn TẮT được yêu cầu này cho một đơn cụ thể (khách quen, đã thoả
     * thuận riêng) — vì vậy nó được xét trước.
     */
    public boolean isPrepaymentRequired(Order order) {
        // Cờ trên đơn CHỈ BẬT THÊM được yêu cầu, không tắt được — createOrder luôn ghi
        // false cho mọi đơn nên đọc thẳng giá trị sẽ vô hiệu hoá toàn bộ quy tắc bên dưới.
        if (Boolean.TRUE.equals(order.getRequirePrepayment())) return true;

        Customer c = order.getCustomer();
        if (c != null && Boolean.TRUE.equals(c.getRequirePrepayment())) return true;

        // ── KHÁCH CÓ HỢP ĐỒNG: miễn hoàn toàn ────────────────────────────────
        // CK, TM hay công nợ đều được giao trước khi thu — đã có hợp đồng làm căn cứ đòi.
        if (c != null && c.getId() != null && customerContractService.isDebtAllowed(c))
            return false;

        // ── KHÁCH CHƯA CÓ HỢP ĐỒNG ───────────────────────────────────────────
        // TH1. Chuyển khoản  → luôn phải thu trước. Không ai cầm tiền lúc giao; hàng đi
        //      rồi mà khách không chuyển thì không còn đòn bẩy nào.
        if (!"CASH".equalsIgnoreCase(order.getPaymentMethod())) return true;

        // KHÁCH TỰ TỚI KHO: hàng chỉ rời kho khi khách đứng đó trả tiền — đúng bản chất
        // COD, và không có chuyến giao nào để mất. Không có tỉnh/phường nên phải nhận
        // dạng riêng, nếu không mọi đơn nhận tại kho sẽ bị bắt thu trước.
        if (addressCatalogService.isPickupAtWarehouse(order.getDeliveryAddress())) return false;

        // TH2 + TH3. Tiền mặt → chỉ được COD nếu phường/xã nằm trong allow.json.
        //      Danh sách đó hiện chỉ chứa phường thuộc TP.HCM cũ, nên phường của Bình
        //      Dương / Bà Rịa – Vũng Tàu tuy đã thuộc TP.HCM vẫn phải thu trước — đúng
        //      yêu cầu nghiệp vụ, và mở rộng vùng COD về sau chỉ cần sửa file, không sửa code.
        return !addressCatalogService.isCodAllowed(order.getProvinceName(), order.getWardName());
    }

    /** Lý do bắt buộc thu tiền trước — để thông báo nói rõ nguyên nhân. */
    public String prepaymentReason(Order order) {
        Customer c = order.getCustomer();
        if (c != null && c.getId() != null && customerContractService.isDebtAllowed(c))
            return "Đơn hàng yêu cầu thanh toán trước";

        if (!"CASH".equalsIgnoreCase(order.getPaymentMethod()))
            return "Đơn chuyển khoản phải thanh toán trước khi giao";

        if (addressCatalogService.isPickupAtWarehouse(order.getDeliveryAddress()))
            return "Đơn hàng yêu cầu thanh toán trước";
        if (order.getWardName() == null || order.getWardName().isBlank())
            return "Địa chỉ giao chưa chọn phường/xã nên không xác định được vùng giao";

        return "Địa chỉ giao (" + order.getWardName() + ") không thuộc vùng được giao COD";
    }

    public static boolean isPreDelivery(Order order) {
        return order.getStatus() != null && PRE_DELIVERY_STATUSES.contains(order.getStatus());
    }

    private static BigDecimal remainingAmount(Order order) {
        BigDecimal fin  = order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal paid = order.getPaidAmount()  != null ? order.getPaidAmount()  : BigDecimal.ZERO;
        BigDecimal rem  = fin.subtract(paid);
        return rem.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : rem;
    }

    private static boolean isFullyPaid(Order order) {
        if (order.getPaymentStatus() == PaymentStatus.PAID) return true;
        BigDecimal fin  = order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal paid = order.getPaidAmount()  != null ? order.getPaidAmount()  : BigDecimal.ZERO;
        return paid.compareTo(fin.subtract(PAY_TOLERANCE)) >= 0;
    }

    /**
     * Tính lại {@code paymentStatus} sau khi {@code finalAmount} bị thay đổi (sửa đơn).
     *
     * <p>Chỉ áp dụng cho đơn CHƯA GIAO và ĐÃ CÓ TIỀN THU (thu trước). Không đụng tới
     * đơn đã giao (DELIVERING/PENDING_PAYMENT/COMPLETED) để không phá logic công nợ hiện có.
     *
     * <p>Không đổi {@code order.status} — chỉ đổi {@code paymentStatus}, vì đây chính là
     * thứ quyết định kho có được bấm "Đang giao" hay không.
     */
    private void recalcPaymentStatusAfterAmountChange(Order order) {
        if (!isPreDelivery(order)) return;

        BigDecimal paid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
        if (paid.compareTo(BigDecimal.ZERO) <= 0) return;   // chưa thu đồng nào → giữ nguyên

        BigDecimal fin = order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO;

        if (paid.compareTo(fin.subtract(PAY_TOLERANCE)) >= 0) {
            order.setPaymentStatus(PaymentStatus.PAID);
        } else {
            // Đơn tăng tiền sau khi đã thu → thu THIẾU → chặn kho giao cho tới khi thu bù
            order.setPaymentStatus(PaymentStatus.PARTIAL);
        }
        // Thu vượt (đơn bị giảm tiền sau khi thu) → cần hoàn tiền/ghi có, ghi log để kế toán biết
        if (paid.compareTo(fin.add(PAY_TOLERANCE)) > 0) {
            log.warn("[PREPAYMENT] Đơn {} đã thu {} nhưng tổng đơn sau khi sửa chỉ còn {} — THU VƯỢT {}",
                    order.getOrderCode(), paid.toPlainString(), fin.toPlainString(),
                    paid.subtract(fin).toPlainString());
        }
    }

    /**
     * GHI NHẬN THU TIỀN TRƯỚC KHI GIAO (đơn còn ở PENDING/CONFIRMED/PREPARING/READY).
     *
     * <p>Khác biệt cốt lõi với {@code markAsCompleted} / {@code recordPartialPayment}:
     * <b>KHÔNG đụng tới {@code order.status}</b>. Đơn vẫn nằm nguyên ở "Đang chuẩn bị"
     * để nhân viên kho tiếp tục soạn hàng — chỉ cập nhật {@code paidAmount} +
     * {@code paymentStatus}. Khi đã PAID thì kho mới được bấm "Đang giao".
     *
     * @param waiveRemainder true = bỏ phần lẻ còn thiếu, đánh dấu PAID luôn
     */
    @Override
    @Transactional
    public OrderResponse recordPrepayment(Long orderId, BigDecimal amount, boolean waiveRemainder,
                                          String actorName, String paymentMethod,
                                          String bankName, String transactionRef, Long actorUserId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new RuntimeException("Số tiền thu phải lớn hơn 0");

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);

        if (!isPreDelivery(order))
            throw new RuntimeException("Đơn " + order.getOrderCode()
                    + " không còn ở trạng thái trước khi giao — dùng thu tiền thông thường");

        BigDecimal currentPaid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newPaid     = currentPaid.add(amount);
        BigDecimal finalAmount = order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO;

        if (newPaid.compareTo(finalAmount.add(PAY_TOLERANCE)) > 0)
            throw new RuntimeException(String.format(
                    "Tổng số tiền thu (%s) vượt quá giá trị đơn hàng (%s)",
                    newPaid.toPlainString(), finalAmount.toPlainString()));

        // ── ĐƠN THU TRƯỚC PHẢI TRẢ HẾT TRONG MỘT LẦN ──────────────────────────
        // Mục đích của việc thu trước là kho chỉ giao khi tiền đã về đủ. Cho phép thu
        // nhiều đợt sẽ tạo ra những đơn nằm lửng ở PARTIAL: kho vẫn bị chặn, kế toán
        // tưởng đã xử lý xong, và không ai thấy đơn đó đang kẹt vì lý do gì.
        BigDecimal shortfall = finalAmount.subtract(newPaid);
        boolean coversAll = shortfall.compareTo(PAY_TOLERANCE) <= 0;

        if (!coversAll) {
            if (!waiveRemainder)
                throw new RuntimeException(String.format(
                        "Đơn thu tiền trước phải thanh toán đủ. Còn thiếu %s đ — "
                                + "nếu khách trả thiếu do làm tròn, chọn \"thu đủ, bỏ phần dư\".",
                        shortfall.setScale(0, RoundingMode.HALF_UP).toPlainString()));

            if (shortfall.compareTo(MAX_WAIVE_AMOUNT) > 0)
                throw new RuntimeException(String.format(
                        "Phần dư %s đ vượt mức được phép bỏ (tối đa %s đ). "
                                + "Vui lòng thu đủ hoặc điều chỉnh giá trị đơn hàng.",
                        shortfall.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                        MAX_WAIVE_AMOUNT.toPlainString()));
        }

        long now = System.currentTimeMillis();
        // Tới đây chắc chắn PAID: hoặc đã đủ tiền, hoặc phần thiếu nằm trong hạn mức bỏ dư.
        boolean fullyPaid = true;

        order.setPaidAmount(newPaid);
        order.setPaymentStatus(fullyPaid ? PaymentStatus.PAID : PaymentStatus.PARTIAL);
        // ⚠️ CỐ TÌNH KHÔNG đổi order.status — đơn vẫn "Đang chuẩn bị"
        order.setUpdatedAt(now);
        Order saved = orderRepository.save(order);

        String method = paymentMethod != null ? paymentMethod.toUpperCase()
                : (order.getPaymentMethod() != null ? order.getPaymentMethod() : "CASH");
        paymentTransactionRepository.save(PaymentTransaction.builder()
                .order(saved).amount(amount).paymentMethod(method)
                .bankName(bankName).transactionRef(transactionRef)
                .collectedBy(actorName).createdAt(now)
                .note("Thu trước khi giao hàng"
                        + (waiveRemainder ? " (bỏ số lẻ còn thiếu)" : ""))
                .build());

        log(saved, fullyPaid ? "PREPAYMENT_FULL" : "PREPAYMENT_PARTIAL", actorName, "ACCOUNTANT",
                String.format("Thu trước: %s (%s) | Tổng đã thu: %s / %s%s",
                        amount.toPlainString(), method,
                        newPaid.toPlainString(), finalAmount.toPlainString(),
                        fullyPaid ? " — ĐÃ ĐỦ, kho có thể giao hàng" : " — CHƯA ĐỦ, kho chưa được giao"));

        String payload = "{\"orderId\":" + orderId + ",\"orderCode\":\"" + saved.getOrderCode() + "\"}";
        notifyOrderUpdate(saved,
                fullyPaid ? "ORDER_PREPAID" : "ORDER_PARTIAL_PAID",
                fullyPaid
                        ? "Đơn " + saved.getOrderCode() + " đã thu đủ tiền trước — kho có thể giao hàng"
                        : "Đơn " + saved.getOrderCode() + " đã thu trước 1 phần bởi " + actorName
                        + " (" + amount.toPlainString() + ") — chưa đủ để giao",
                payload, actorUserId);

        return mapToResponse(saved);
    }

    // ════════════════════════════════════════════════════════════════
    // STATUS TRANSITIONS
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public OrderResponse markAsPreparing(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);
        if (order.getUser().getId() != (long) userId)
            throw new RuntimeException("Bạn không có quyền thao tác đơn hàng này");
        if (order.getStatus() != OrderStatus.PENDING)
            throw new RuntimeException("Chỉ có thể chuyển sang 'Đang chuẩn bị' từ trạng thái 'Chờ xử lý'");
        order.setStatus(OrderStatus.PREPARING);
        order.setUpdatedAt(System.currentTimeMillis());
        return mapToResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    public OrderResponse markAsPendingPayment(Long orderId, String actorName) {
        return markAsPendingPayment(orderId, actorName, null);
    }

    @Override
    @Transactional
    public OrderResponse markAsPendingPayment(Long orderId, String actorName, Long actorUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);
        if (order.getStatus() != OrderStatus.DELIVERING)
            throw new RuntimeException("Chỉ có thể chuyển sang 'Chờ thanh toán' từ trạng thái 'Đang giao'");

        // ── ĐƠN 0đ (TOÀN KHUYẾN MÃI, KHÔNG PHÍ) → HOÀN THÀNH LUÔN ────────────
        // Ví dụ điển hình: khách trả hàng khuyến mãi (Cty Rich sampling…) hoặc đơn
        // tặng quà 100% không thu phí — final_amount = 0đ, không có gì để "chờ thu".
        // Cho lên PENDING_PAYMENT sẽ làm đơn nằm lại trên bàn kế toán vô nghĩa và
        // vẫn bị tính vào công nợ tuy không ai nợ ai đồng nào.
        //
        // Rule đúng theo nghiệp vụ:
        //   final_amount = 0đ  → COMPLETED luôn (bất kể "Nhận tại kho" hay giao)
        //   final_amount > 0đ  → luồng cũ (PENDING_PAYMENT chờ kế toán xác nhận)
        //
        // TÁCH khỏi isPaidInFullRounded để giữ đúng ngữ nghĩa hàm đó ("đã thu ĐỦ"
        // đối với đơn có tiền thu) — với đơn 0đ, khái niệm "thu đủ" không áp dụng,
        // ta chỉ đơn thuần bỏ qua bước chờ thanh toán.
        BigDecimal finalAmt = order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO;
        boolean isZeroAmount = finalAmt.compareTo(BigDecimal.ZERO) == 0;

        // ── ĐƠN ĐÃ THU ĐỦ TIỀN → HOÀN THÀNH LUÔN ─────────────────────────────
        // Điển hình: khách bắt buộc THANH TOÁN TRƯỚC — tiền đã thu xong từ lúc đơn
        // còn đang chuẩn bị. Khi kho xác nhận đã giao xong thì không có gì để "chờ thu"
        // nữa → chuyển thẳng COMPLETED thay vì PENDING_PAYMENT (nếu không, đơn sẽ nằm
        // lì ở "Chờ thanh toán" và bị tính vào công nợ dù đã trả tiền).
        //
        // So sánh sau khi LÀM TRÒN CẢ HAI về hàng đơn vị đồng — final_amount có thể
        // còn số lẻ (VAT/chiết khấu), paid_amount là tiền thực thu (số nguyên).
        if (isZeroAmount || isPaidInFullRounded(order)) {
            return markAsCompleted(orderId, actorName, actorUserId);
        }

        Customer customer = order.getCustomer();
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setDebtDays(customer != null ? customer.getDebtDays() : 0);
        order.setPendingPaymentAt(System.currentTimeMillis());
        order.setUpdatedAt(System.currentTimeMillis());
        Order saved = orderRepository.save(order);
        log(saved, "PENDING_PAYMENT", actorName, "WAREHOUSE", null);
        String payload = "{\"orderId\":" + orderId + ",\"orderCode\":\"" + saved.getOrderCode() + "\"}";
        notifyOrderUpdate(saved, "ORDER_PENDING_PAYMENT",
                "Đơn " + saved.getOrderCode() + " chuyển trạng thái Chờ Thanh Toán bởi " + actorName, payload, actorUserId);
        return mapToResponse(saved);
    }

    /**
     * ĐÃ THU ĐỦ chưa? So sánh {@code paid_amount} với {@code final_amount}
     * sau khi LÀM TRÒN CẢ HAI về hàng đơn vị đồng (HALF_UP).
     *
     * <p>{@code final_amount} có thể còn số lẻ do VAT/chiết khấu, trong khi tiền thực thu
     * luôn là số nguyên → so sánh trực tiếp sẽ luôn ra "thiếu vài hào" và đơn không bao giờ
     * được coi là thu đủ.
     */
    private static boolean isPaidInFullRounded(Order order) {
        BigDecimal fin  = order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal paid = order.getPaidAmount()  != null ? order.getPaidAmount()  : BigDecimal.ZERO;
        if (fin.compareTo(BigDecimal.ZERO) <= 0) return false;   // đơn 0đ → không tự hoàn thành
        return paid.setScale(0, RoundingMode.HALF_UP)
                .compareTo(fin.setScale(0, RoundingMode.HALF_UP)) >= 0;
    }

    @Override
    @Transactional
    public OrderResponse markAsCompleted(Long orderId, String actorName) {
        return markAsCompleted(orderId, actorName, null);
    }

    @Override
    @Transactional
    public OrderResponse markAsCompleted(Long orderId, String actorName, Long actorUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);
        if (order.getStatus() != OrderStatus.DELIVERING && order.getStatus() != OrderStatus.PENDING_PAYMENT)
            throw new RuntimeException("Chỉ có thể hoàn thành từ 'Đang giao' hoặc 'Chờ thanh toán'");
        order.setStatus(OrderStatus.COMPLETED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaidAmount(order.getFinalAmount());
        order.setUpdatedAt(System.currentTimeMillis());
        Order saved = orderRepository.save(order);
        log(saved, "COMPLETED", actorName, "ACCOUNTANT", null);
        String payload = "{\"orderId\":" + orderId + ",\"orderCode\":\"" + saved.getOrderCode() + "\"}";
        notifyOrderUpdate(saved, "ORDER_COMPLETED",
                "Đơn " + saved.getOrderCode() + " đã hoàn thành & thanh toán đủ", payload, actorUserId);
        updateSellerKpi(saved);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public void markAsCompletedNoFixedPaidAmount(Long orderId, String actorName, Long actorUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);
        if (order.getStatus() != OrderStatus.DELIVERING && order.getStatus() != OrderStatus.PENDING_PAYMENT)
            throw new RuntimeException("Chỉ có thể hoàn thành từ 'Đang giao' hoặc 'Chờ thanh toán'");
        order.setStatus(OrderStatus.COMPLETED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setUpdatedAt(System.currentTimeMillis());
        Order saved = orderRepository.save(order);
        log(saved, "COMPLETED", actorName, "ACCOUNTANT", null);
        String payload = "{\"orderId\":" + orderId + ",\"orderCode\":\"" + saved.getOrderCode() + "\"}";
        notifyOrderUpdate(saved, "ORDER_COMPLETED",
                "Đơn " + saved.getOrderCode() + " đã hoàn thành & thanh toán đủ", payload, actorUserId);
        updateSellerKpi(saved);
        mapToResponse(saved);
    }

    @Override
    @Transactional
    public OrderResponse detachPaymentForVoucherEdit(Long orderId, BigDecimal amountToRemove,
                                                     String actorName, Long actorUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));

        BigDecimal fin    = order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal remove = amountToRemove != null
                ? amountToRemove.setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // ── 1. XOÁ giao dịch tương ứng khỏi sổ tiền ──────────────────────────
        //   Tìm giao dịch MỚI NHẤT có đúng số tiền đang gỡ. Phiếu chỉ ghi một
        //   khoản cho mỗi đơn nên khớp theo số tiền là đủ; lấy bản mới nhất để
        //   khi có hai khoản trùng số thì gỡ cái gần nhất.
        List<PaymentTransaction> txs =
                paymentTransactionRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        PaymentTransaction toDelete = null;
        for (PaymentTransaction tx : txs) {
            BigDecimal amt = tx.getAmount() != null
                    ? tx.getAmount().setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            if (amt.compareTo(remove) == 0) toDelete = tx;   // giữ cái sau cùng
        }

        boolean ledgerComplete;
        if (toDelete != null) {
            paymentTransactionRepository.delete(toDelete);
            txs.remove(toDelete);
            ledgerComplete = true;
        } else {
            // Không tìm thấy giao dịch khớp (dữ liệu CŨ: khoản thu tạo bằng
            // markAsCompleted trước đây không ghi sổ). Không dựng lại log để khỏi
            // làm mất lịch sử; chỉ trừ tiền và ghi một log gỡ.
            ledgerComplete = false;
        }

        // ── 2. Tính lại paidAmount ───────────────────────────────────────────
        BigDecimal newPaid;
        if (ledgerComplete) {
            // Cộng lại từ sổ đã xoá giao dịch → luôn khớp lịch sử.
            newPaid = txs.stream()
                    .map(t -> t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(0, RoundingMode.HALF_UP);
        } else {
            BigDecimal paid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
            newPaid = paid.subtract(remove);
        }
        if (newPaid.compareTo(BigDecimal.ZERO) < 0) newPaid = BigDecimal.ZERO;
        newPaid = newPaid.setScale(0, RoundingMode.HALF_UP);
        order.setPaidAmount(newPaid);

        // ── 3. Tính lại trạng thái từ số tiền còn lại ────────────────────────
        boolean preDelivery = isPreDelivery(order);
        if (newPaid.compareTo(BigDecimal.ZERO) <= 0) {
            // Gỡ HẾT phần phiếu này đã thu (đơn chỉ thu từ 1 phiếu, hoặc gỡ nốt
            // khoản cuối) → về chưa thanh toán.
            order.setPaymentStatus(PaymentStatus.UNPAID);
            if (!preDelivery && order.getStatus() == OrderStatus.COMPLETED)
                order.setStatus(OrderStatus.PENDING_PAYMENT);
        } else if (newPaid.compareTo(fin) >= 0) {
            order.setPaymentStatus(PaymentStatus.PAID);
        } else {
            // Còn một phần (đơn thu từ nhiều phiếu, mới gỡ một) → PARTIAL.
            order.setPaymentStatus(PaymentStatus.PARTIAL);
            if (!preDelivery && order.getStatus() == OrderStatus.COMPLETED)
                order.setStatus(OrderStatus.PENDING_PAYMENT);
        }
        order.setUpdatedAt(System.currentTimeMillis());
        Order saved = orderRepository.save(order);

        // ── 4. Dựng lại order_log ────────────────────────────────────────────
        if (ledgerComplete) {
            rebuildPaymentLogsFromLedger(saved, txs);
        } else {
            log(saved, "PAYMENT_DETACHED", actorName, "ACCOUNTANT",
                    "Gỡ " + remove.toPlainString() + "đ khi sửa phiếu thu");
        }

        updateSellerKpi(saved);
        return mapToResponse(saved);
    }

    /**
     * DỰNG LẠI toàn bộ log thanh toán của đơn TỪ SỔ TIỀN (payment_transaction).
     *
     * <p>Xoá hết log dạng thanh toán cũ rồi phát lại theo đúng thứ tự giao dịch —
     * mỗi giao dịch một dòng, có tổng luỹ kế đúng, và tự đánh dấu FULLY_PAID khi
     * chạm giá trị đơn. Nhờ vậy khi gỡ một khoản giữa chừng, các khoản còn lại
     * "dồn lên" và một dòng từng là COMPLETED sẽ thành PARTIAL_PAYMENT.
     *
     * <p>KHÔNG đụng các log khác (tạo đơn, chuẩn bị, giao, đổi phương thức…) —
     * chỉ dựng lại đúng nhóm log tiền.
     *
     * @param txs sổ tiền đã bỏ giao dịch bị gỡ, theo thứ tự thời gian
     */
    private void rebuildPaymentLogsFromLedger(Order order, List<PaymentTransaction> txs) {
        // Xoá log tiền cũ, giữ nguyên log quy trình.
        Set<String> paymentActions = Set.of(
                "PARTIAL_PAYMENT", "FULLY_PAID",
                "PREPAYMENT_PARTIAL", "PREPAYMENT_FULL",
                "COMPLETED", "PAYMENT_DETACHED");
        List<OrderLog> all = orderLogRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        List<OrderLog> toRemove = all.stream()
                .filter(l -> l.getAction() != null && paymentActions.contains(l.getAction()))
                .toList();
        orderLogRepository.deleteAll(toRemove);

        BigDecimal fin = order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal tolerance = BigDecimal.ONE;

        BigDecimal running = BigDecimal.ZERO;
        List<OrderLog> rebuilt = new ArrayList<>();
        for (PaymentTransaction tx : txs) {
            BigDecimal amt = tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO;
            running = running.add(amt);
            boolean fully = running.compareTo(fin.subtract(tolerance)) >= 0;
            String method = tx.getPaymentMethod() != null ? tx.getPaymentMethod() : "CASH";

            rebuilt.add(OrderLog.builder()
                    .order(order)
                    .action(fully ? "FULLY_PAID" : "PARTIAL_PAYMENT")
                    .actorName(tx.getCollectedBy())
                    .actorRole("ACCOUNTANT")
                    .note(String.format("Thu: %s (%s) | Tổng đã thu: %s / %s",
                            amt.setScale(0, RoundingMode.HALF_UP).toPlainString(), method,
                            running.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                            fin.setScale(0, RoundingMode.HALF_UP).toPlainString()))
                    // Giữ mốc thời gian gốc của giao dịch để log vẫn đúng thứ tự
                    // xen kẽ với các log quy trình khác.
                    .createdAt(tx.getCreatedAt())
                    .build());
        }
        orderLogRepository.saveAll(rebuilt);
    }

    @Override
    @Transactional
    public void updatePaymentMethod(Long orderId, String paymentMethod, String actorName) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);
        assertDebtAllowed(order.getCustomer(), paymentMethod);
        assertPaymentMethodAllowedForZone(order.getCustomer(), paymentMethod,
                order.getProvinceName(), order.getWardName(), order.getDeliveryAddress());
        order.setPaymentMethod(paymentMethod);
        order.setUpdatedAt(System.currentTimeMillis());
        Order saved = orderRepository.saveAndFlush(order);
        log(saved, "PAYMENT_METHOD_UPDATED", actorName, "ACCOUNTANT", "Đổi sang: " + paymentMethod);
        mapToResponse(saved);
    }

    @Override
    @Transactional
    public OrderResponse markAsDelivering(Long orderId, String actorName) {
        return markAsDelivering(orderId, actorName, null);
    }

    @Override
    @Transactional
    public OrderResponse markAsDelivering(Long orderId, String actorName, Long actorUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);
        if (order.getStatus() != OrderStatus.PREPARING)
            throw new RuntimeException("Chỉ có thể chuyển sang 'Đang giao' từ 'Đang chuẩn bị'");

        // ── YÊU CẦU THANH TOÁN TRƯỚC ──────────────────────────────────────────
        // Khách hàng được owner cấu hình "bắt buộc thanh toán trước" → nhân viên kho
        // KHÔNG được chuyển đơn sang "Đang giao" khi đơn chưa thanh toán đủ.
        if (isPrepaymentRequired(order) && !isFullyPaid(order)) {
            BigDecimal remaining = remainingAmount(order);
            throw new RuntimeException(
                    prepaymentReason(order) + " — cần THANH TOÁN TRƯỚC khi giao hàng. "
                            + "Đơn còn thiếu " + remaining.setScale(0, RoundingMode.HALF_UP).toPlainString()
                            + " đ. Vui lòng chờ kinh doanh/kế toán xác nhận đã thu đủ tiền.");
        }

        order.setStatus(OrderStatus.DELIVERING);
        order.setUpdatedAt(System.currentTimeMillis());
        Order saved = orderRepository.save(order);
        log(saved, "DELIVERING", actorName, "WAREHOUSE", null);
        String payload = "{\"orderId\":" + orderId + ",\"orderCode\":\"" + saved.getOrderCode() + "\"}";
        notifyOrderUpdate(saved, "ORDER_DELIVERING",
                "Đơn " + saved.getOrderCode() + " cập nhật trạng thái Đang Giao Hàng bởi " + actorName, payload, actorUserId);
        return mapToResponse(saved);
    }

    // ════════════════════════════════════════════════════════════════
    // SỬA ĐƠN HÀNG ĐANG PREPARING — delta-based stock
    // ════════════════════════════════════════════════════════════════

    private final CartHoldService cartHoldService;
    private final AddressCatalogService addressCatalogService;

    @Override
    @Transactional
    public OrderResponse updateOrderItems(Long orderId, Long userId, UpdateOrderItemsRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));

        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy user: " + userId));

        Set<Role> roles = currentUser.getAllRoles();
        boolean isOwner       = roles.contains(Role.OWNER) || roles.contains(Role.ADMIN) || roles.contains(Role.SUPERADMIN);
        boolean isSuperSeller = roles.contains(Role.SUPER_SELLER);
        boolean isCreator     = order.getUser().getId() == userId;

        if (!isCreator && !isSuperSeller && !isOwner)
            throw new RuntimeException("Bạn không có quyền sửa đơn hàng này");

        if (order.getStatus() != OrderStatus.PREPARING)
            throw new RuntimeException("Chỉ có thể sửa đơn hàng đang ở trạng thái 'Đang chuẩn bị'");
        assertNotCancelled(order);

        long now = System.currentTimeMillis();
        Long warehouseId = order.getWarehouseId();
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new RuntimeException("Kho không tồn tại: " + warehouseId));

        // ════════════════════════════════════════════════════════════════
        // CẬP NHẬT KHÁCH HÀNG (nếu FE gửi customerId)
        // ════════════════════════════════════════════════════════════════
        if (request.getCustomerId() != null) {
            Customer newCustomer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new RuntimeException(
                            "Không tìm thấy khách hàng: " + request.getCustomerId()));

            if (!Boolean.TRUE.equals(newCustomer.getIsActive()))
                throw new RuntimeException("Khách hàng đã tạm ngưng, không thể gán vào đơn hàng");

            boolean isCompany = newCustomer.getCustomerType() == Customer.CustomerType.COMPANY;

            order.setCustomer(newCustomer);
            order.setCustomerType(isCompany ? "COMPANY" : "RETAIL");

            if (isCompany) {
                order.setCustomerName(firstNonBlank(
                        newCustomer.getCompanyName(),
                        newCustomer.getContactName(),
                        newCustomer.getName()));
                order.setCompanyName(newCustomer.getCompanyName());
                order.setTaxCode(newCustomer.getTaxCode());
                order.setContactName(newCustomer.getContactName());
                order.setCompanyPhone(newCustomer.getCompanyPhone());
                order.setCompanyAddress(newCustomer.getCompanyAddress());
            } else {
                order.setCustomerName(firstNonBlank(newCustomer.getName(), newCustomer.getContactName()));
                order.setCompanyName(null);
                order.setTaxCode(null);
                order.setContactName(null);
                order.setCompanyPhone(null);
                order.setCompanyAddress(null);
            }

            order.setCustomerPhone(newCustomer.getPhone());
            order.setCustomerEmail(newCustomer.getEmail());

            if (newCustomer.getDiscountRate() > 0) {
                order.setDiscountRate(newCustomer.getDiscountRate());
            }

            Long visibleToSellerId = null;
            if (isCompany) {
                User customerCreator = newCustomer.getCreatedBySeller();
                if (customerCreator != null) {
                    Set<Role> creatorRoles = customerCreator.getAllRoles();
                    boolean creatorIsPureSeller = creatorRoles.contains(Role.SELLER)
                            && !creatorRoles.contains(Role.SUPER_SELLER)
                            && !creatorRoles.contains(Role.ADMIN)
                            && !creatorRoles.contains(Role.OWNER)
                            && !creatorRoles.contains(Role.SUPERADMIN);
                    if (creatorIsPureSeller) visibleToSellerId = customerCreator.getId();
                }
            }
            order.setVisibleToSellerId(visibleToSellerId);

            Long currentKpiUserId = order.getKpiUserId();
            Long newKpiUserId = currentKpiUserId;
            User creator = order.getUser();
            Set<Role> creatorRoles = creator.getAllRoles();

            boolean isPureSeller = creatorRoles.contains(Role.SELLER)
                    && !creatorRoles.contains(Role.SUPER_SELLER)
                    && !creatorRoles.contains(Role.ADMIN)
                    && !creatorRoles.contains(Role.OWNER)
                    && !creatorRoles.contains(Role.SUPERADMIN);

            boolean isSuperSellerOrOwner = creatorRoles.contains(Role.SUPER_SELLER)
                    || creatorRoles.contains(Role.OWNER);

            User assignedSeller = newCustomer.getCreatedBySeller();
            Long assignedSellerId = assignedSeller != null ? assignedSeller.getId() : null;

            if (isPureSeller) {
                if (currentKpiUserId != null && currentKpiUserId == 0 && assignedSellerId != null && assignedSellerId.equals(creator.getId())) {
                    newKpiUserId = creator.getId();
                } else if (currentKpiUserId != null && currentKpiUserId > 0 && assignedSellerId == null) {
                    newKpiUserId = 0L;
                }
            } else if (isSuperSellerOrOwner) {
                if (currentKpiUserId != null && currentKpiUserId != -1) {
                    if (currentKpiUserId == 0 && assignedSellerId != null) {
                        newKpiUserId = assignedSellerId;
                    } else if (currentKpiUserId > 0 && assignedSellerId == null) {
                        newKpiUserId = 0L;
                    } else if (currentKpiUserId > 0 && assignedSellerId != null && !assignedSellerId.equals(currentKpiUserId)) {
                        newKpiUserId = assignedSellerId;
                    }
                }
            }

            if (!Objects.equals(currentKpiUserId, newKpiUserId)) {
                order.setKpiUserId(newKpiUserId);
            }

        }

        // ════════════════════════════════════════════════════════════════
        // DELTA KHO
        // ════════════════════════════════════════════════════════════════

        List<OrderStockDeduction> existingDeductions = orderStockDeductionRepository.findByOrderId(orderId);

        Map<Long, BigDecimal> oldUsageMap = existingDeductions
                .stream()
                .collect(Collectors.groupingBy(
                        d -> d.getIngredientStock().getIngredientId(),
                        Collectors.reducing(BigDecimal.ZERO, OrderStockDeduction::getQuantity, BigDecimal::add)
                ));

        // BUG FIX 3.1: XOÁ existingDeductionByIngId map. Trước đây map này chỉ
        // giữ 1 record/ingredient, sau đó dùng để xoá các record khác → mất
        // tracking cho các lô đã trừ. Bây giờ giữ nguyên tất cả deduction records
        // của flow deduct/restore → cancel về sau phân bổ đúng lô.

        Map<Long, BigDecimal> newUsageMap = new LinkedHashMap<>();
        List<Map<Long, BigDecimal>> itemIngredientUsage = new ArrayList<>();
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CreateOrderRequest.OrderItemRequest itemReq : request.getItems()) {
            Map<Long, BigDecimal> usageForThisItem = new LinkedHashMap<>();
            OrderItem item = buildOrderItem(order, itemReq, usageForThisItem);
            orderItems.add(item);
            subtotal = subtotal.add(item.getSubtotal());
            itemIngredientUsage.add(usageForThisItem);
            usageForThisItem.forEach((ingId, qty) -> newUsageMap.merge(ingId, qty, BigDecimal::add));
        }

        final boolean hasDeductionRecords = !oldUsageMap.isEmpty();
        Map<Long, BigDecimal> deltaMap = new LinkedHashMap<>();

        if (hasDeductionRecords) {
            Set<Long> allIngredientIds = new HashSet<>();
            allIngredientIds.addAll(oldUsageMap.keySet());
            allIngredientIds.addAll(newUsageMap.keySet());

            for (Long ingId : allIngredientIds) {
                BigDecimal delta = newUsageMap.getOrDefault(ingId, BigDecimal.ZERO)
                        .subtract(oldUsageMap.getOrDefault(ingId, BigDecimal.ZERO));
                if (delta.compareTo(BigDecimal.ZERO) != 0)
                    deltaMap.put(ingId, delta);
            }
        } else {
            // BUG FIX 3.2 (double-deduct silent):
            // Trước: fallback deltaMap.putAll(newUsageMap) → trừ toàn bộ như đơn mới
            //         → nhưng đơn ĐÃ trừ kho lúc tạo → trừ 2 lần, chỉ log warn.
            // Sau: throw để admin phát hiện dữ liệu bất thường (deduction records
            //      bị xoá do bug khác hoặc thao tác thủ công).
            throw new BusinessException(String.format(
                    "Đơn hàng #%d không có bản ghi trừ kho — không thể sửa an toàn. " +
                            "Vui lòng kiểm tra dữ liệu (bảng order_stock_deduction có thể bị xoá).",
                    orderId));
        }

        Map<Long, BigDecimal> ingredientUnitCostMap = new LinkedHashMap<>();
        List<WarehouseReceiptItem> receiptItems = new ArrayList<>();

        // BUG FIX (deadlock guard): sort ingredientId ASC
        List<Map.Entry<Long, BigDecimal>> sortedDelta = deltaMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        for (Map.Entry<Long, BigDecimal> entry : sortedDelta) {
            Long ingId = entry.getKey();
            BigDecimal delta = entry.getValue();

            Ingredient ing = ingredientRepository.findById(ingId)
                    .orElseThrow(() -> new RuntimeException("Ingredient not found: " + ingId));

            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                // BUG FIX 3.3 (race): atomic decrease thay cho check-modify-write.
                StockMutationResult mut = stockMutationService.decrease(
                        ingId, warehouseId, delta, now);

                // BUG FIX 3.1: XOÁ đoạn code xoá deduction records "trừ 1 keepId".
                // Trước: xoá tất cả deduction của ingredient này trừ 1 record đầu
                //         → mất tracking các lô đã trừ.
                // Sau: KHÔNG xoá gì. FIFO deduct sẽ tự tạo deduction records MỚI
                //      cho các lô mới trừ (+delta). Deduction cũ giữ nguyên → tổng
                //      quantity = tổng của cả cũ + mới, tracking chính xác từng lô.
                BigDecimal costDeducted = fifoDeductService.deduct(order.getId(), warehouse, ing, delta, now);

                BigDecimal unitCost = BigDecimal.ZERO;
                if (costDeducted.compareTo(BigDecimal.ZERO) > 0)
                    unitCost = costDeducted.divide(delta, 6, RoundingMode.HALF_UP);
                ingredientUnitCostMap.put(ingId, unitCost);

                receiptItems.add(WarehouseReceiptItem.builder()
                        .ingredientId(ing.getId())
                        .ingredientNameSnapshot(ing.getName())
                        .ingredientUnitSnapshot(ing.getUnit())
                        .ingredientImageUrlSnapshot(ing.getImageUrl())
                        .quantity(delta.negate())
                        .quantityBefore(mut.getBefore()).quantityAfter(mut.getAfter())
                        .difference(delta.negate()).build());

            } else {
                // Delta < 0: bán bớt → hoàn kho
                BigDecimal restore = delta.abs();

                // BUG FIX 3.3 (race): atomic increase
                StockMutationResult mut = stockMutationService.increase(
                        ingId, warehouseId, restore, now);

                // restorePartialStock: hoàn từng lô theo deduction records HIỆN TẠI
                // (không consolidated) → phân bổ đúng lô nào đã trừ bao nhiêu.
                restorePartialStock(order.getId(), ingId, restore, now);
                ingredientUnitCostMap.put(ingId, BigDecimal.ZERO);

                receiptItems.add(WarehouseReceiptItem.builder()
                        .ingredientId(ing.getId())
                        .ingredientNameSnapshot(ing.getName())
                        .ingredientUnitSnapshot(ing.getUnit())
                        .ingredientImageUrlSnapshot(ing.getImageUrl())
                        .quantity(restore)
                        .quantityBefore(mut.getBefore()).quantityAfter(mut.getAfter())
                        .difference(mut.getAfter().subtract(mut.getBefore())).build());
            }
        }

        for (Long ingId : oldUsageMap.keySet()) {
            if (!deltaMap.containsKey(ingId)) {
                BigDecimal oldQty = oldUsageMap.get(ingId);
                if (oldQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal oldCostTotal = existingDeductions.stream()
                            .filter(d -> d.getIngredientStock().getIngredientId().equals(ingId))
                            .map(d -> d.getCostPrice() != null
                                    ? d.getCostPrice().multiply(d.getQuantity()) : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    ingredientUnitCostMap.put(ingId,
                            oldCostTotal.divide(oldQty, 6, RoundingMode.HALF_UP));
                }
            }
        }

        order.getOrderItems().clear();
        orderRepository.saveAndFlush(order);

        // BUG FIX 3.1: XOÁ toàn bộ khối rebuild consolidate.
        // Trước: xoá deduction cho ingredient bị remove khỏi đơn (dùng
        //         existingDeductionByIngId), sau đó rebuild MỘT record per
        //         ingredient với totalQty + unitCost trung bình.
        //         → mất tracking chính xác từng lô.
        // Sau: KHÔNG động vào deduction records. Flow đã đúng ở loop delta phía trên:
        //   - Delta > 0 → FIFO deduct tự tạo record MỚI cho từng lô mới trừ
        //   - Delta < 0 → restorePartialStock tự update/xoá record cho lô hoàn
        //   - Ingredient bị remove hoàn toàn → delta = 0 - oldQty = -oldQty,
        //     nhánh delta < 0 đã restore hết + xoá record.
        // Deduction records giữ đúng lot-level → cancel về sau phân bổ chính xác.

        for (int i = 0; i < orderItems.size(); i++) {
            OrderItem item = orderItems.get(i);
            BigDecimal itemCost = BigDecimal.ZERO;
            for (Map.Entry<Long, BigDecimal> u : itemIngredientUsage.get(i).entrySet()) {
                BigDecimal unitCost = ingredientUnitCostMap.getOrDefault(u.getKey(), BigDecimal.ZERO);
                itemCost = itemCost.add(unitCost.multiply(u.getValue()));
            }
            item.setCostPrice(itemCost.setScale(2, RoundingMode.HALF_UP));
        }

        int discountRate = order.getDiscountRate() != null ? order.getDiscountRate() : 0;

        // ── FIX: dùng effectiveQty cho BOX khi tính item discount ──
        BigDecimal itemDiscountTotal = BigDecimal.ZERO;
        for (OrderItem oi : orderItems) {
            int pct = oi.getDiscountPercent() != null ? oi.getDiscountPercent() : 0;
            if (pct == 0) continue;
            BigDecimal effectiveQty = oi.getQuantity();
            if (oi.getUnitsPerBox() != null && oi.getUnitsPerBox() > 0) {
                effectiveQty = oi.getQuantity().multiply(BigDecimal.valueOf(oi.getUnitsPerBox()));
            }
            BigDecimal lineGross = oi.getUnitPrice().multiply(effectiveQty);
            itemDiscountTotal = itemDiscountTotal.add(
                    lineGross.multiply(BigDecimal.valueOf(pct))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }

        BigDecimal subtotalAfterItemDiscount = subtotal.subtract(itemDiscountTotal);
        BigDecimal billDiscountAmount = calcDiscountAmount(
                subtotalAfterItemDiscount, request.getDiscountAmount(),
                request.getDiscountRate(), discountRate);
        BigDecimal discountAmount = itemDiscountTotal.add(billDiscountAmount).setScale(2, RoundingMode.HALF_UP);

        if (request.getDiscountAmount() != null && request.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0
                && subtotal.compareTo(BigDecimal.ZERO) > 0) {
            discountRate = discountAmount.multiply(BigDecimal.valueOf(100))
                    .divide(subtotal, 0, RoundingMode.HALF_UP).intValue();
        } else if (itemDiscountTotal.compareTo(BigDecimal.ZERO) > 0 && request.getDiscountRate() == null) {
            discountRate = discountAmount.multiply(BigDecimal.valueOf(100))
                    .divide(subtotal, 0, RoundingMode.HALF_UP).intValue();
        } else if (request.getDiscountRate() != null) {
            discountRate = request.getDiscountRate();
        }

        BigDecimal afterDiscount = subtotal.subtract(discountAmount);
        BigDecimal[] vatResult   = calcVatForItems(orderItems, subtotal, afterDiscount);
        BigDecimal exclusiveVat  = vatResult[0];   // ← FIX: VAT ngoài giá (cộng vào finalAmount)
        BigDecimal totalVat      = vatResult[1];   //        tổng VAT (lưu DB hiển thị)

        BigDecimal surcharge;
        String surchargeDetail;
        if (request.getSurchargeItems() != null) {
            surcharge = calcSurchargeTotal(request.getSurchargeItems());
            surchargeDetail = serializeSurchargeItems(request.getSurchargeItems());
        } else if (request.getSurcharge() != null) {
            surcharge = request.getSurcharge();
            surchargeDetail = order.getSurchargeDetail();
        } else {
            surcharge = order.getSurcharge() != null ? order.getSurcharge() : BigDecimal.ZERO;
            surchargeDetail = order.getSurchargeDetail();
        }

        order.getOrderItems().addAll(orderItems);
        order.setSubtotal(subtotal);
        order.setDiscountRate(discountRate);
        order.setDiscountAmount(discountAmount);
        order.setVatAmount(totalVat);              // lưu tổng VAT (inclusive + exclusive) để hiển thị
        order.setTotalAmount(afterDiscount);
        order.setSurcharge(surcharge);
        order.setFinalAmount(afterDiscount.add(exclusiveVat).add(surcharge)    // ← FIX: chỉ cộng exclusive VAT
                .setScale(0, RoundingMode.HALF_UP));
        order.setSurchargeDetail(surchargeDetail);

        // ── FIX: ĐƠN ĐÃ THU TIỀN TRƯỚC MÀ BỊ SỬA LẠI ─────────────────────────
        // Sale sửa món/số lượng/giá của đơn đang PREPARING → finalAmount thay đổi.
        // Nếu đơn đã thu tiền trước (paidAmount > 0), paymentStatus cũ có thể sai:
        //   - Tăng tiền đơn  → vẫn PAID nhưng thực tế thu THIẾU → kho vẫn giao được (SAI!)
        //   - Giảm tiền đơn  → vẫn PARTIAL nhưng thực tế đã thu ĐỦ → kho bị chặn oan
        // → Tính lại paymentStatus theo finalAmount MỚI.
        recalcPaymentStatusAfterAmountChange(order);

        if (request.getShowPrices() != null) order.setShowPrices(request.getShowPrices());
        if (request.getHideAllPrices() != null) {
            order.setHideAllPrices(request.getHideAllPrices());
            if (request.getHideAllPrices()) order.setShowPrices(true);
        } else if (request.getShowPrices() != null && !request.getShowPrices()) {
            order.setHideAllPrices(false);
        }

        if (request.getNotes() != null)          order.setNotes(request.getNotes());
        if (request.getOrderedByName() != null)  order.setOrderedByName(request.getOrderedByName());
        if (request.getReceiverName() != null)   order.setReceiverName(request.getReceiverName());
        if (request.getDeliveryAddress() != null) {
            order.setDeliveryAddress(request.getDeliveryAddress());
            order.setShippingAddress(request.getDeliveryAddress());
        }
        if (request.getDeliveryDatetime() != null) order.setDeliveryDatetime(request.getDeliveryDatetime());
        if (request.getPaymentMethod() != null) {
            assertDebtAllowed(order.getCustomer(), request.getPaymentMethod());
            assertPaymentMethodAllowedForZone(order.getCustomer(), request.getPaymentMethod(),
                    order.getProvinceName(), order.getWardName(), order.getDeliveryAddress());
            order.setPaymentMethod(request.getPaymentMethod());
            boolean isDebt = "DEBT".equalsIgnoreCase(request.getPaymentMethod());
            if (isDebt && order.getCustomer() != null && order.getCustomer().getDebtDays() > 0) {
                order.setDebtDays(order.getCustomer().getDebtDays());
                order.setPendingPaymentAt(now);
            } else if (!isDebt) {
                order.setDebtDays(0);
            }
        }
        order.setUpdatedAt(now);

        Order saved = orderRepository.save(order);

        if (!receiptItems.isEmpty()) {
            User actor = saved.getUser();
            String actorName2 = actor.getFullName() != null && !actor.getFullName().isBlank()
                    ? actor.getFullName() : actor.getUsername();
            WarehouseReceipt receipt = WarehouseReceipt.builder()
                    .receiptCode(generateReceiptCode("ADJ"))
                    .receiptType(WarehouseReceipt.ReceiptType.EXPORT_ORDER)
                    .order(saved).warehouse(warehouse)
                    .note("Điều chỉnh đơn #" + saved.getOrderCode())
                    .createdBy(actor).createdByName(actorName2)
                    .costStatus(WarehouseReceipt.CostStatus.CONFIRMED)
                    .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();
            for (WarehouseReceiptItem item : receiptItems) item.setReceipt(receipt);
            receipt.getItems().addAll(receiptItems);
            warehouseReceiptRepository.save(receipt);
        }

        Set<Long> allChangedIngIds = new HashSet<>();
        allChangedIngIds.addAll(newUsageMap.keySet());
        allChangedIngIds.addAll(oldUsageMap.keySet());
        for (Long ingId : allChangedIngIds) {
            ingredientStockRepository.findByIngredientIdAndWarehouseId(ingId, warehouseId)
                    .ifPresent(stock -> cartHoldService.broadcastIngredientStockUpdate(
                            warehouseId, ingId, stock.getStockQuantity()));
        }

        String actorName = saved.getUser().getFullName() != null && !saved.getUser().getFullName().isBlank()
                ? saved.getUser().getFullName() : saved.getUser().getUsername();
        log(saved, "ORDER_UPDATED", actorName, "SELLER",
                request.getCustomerId() != null
                        ? "Sửa món/số lượng/giá (delta) + đổi KH → " + saved.getCustomerName()
                        : "Sửa món/số lượng/giá (delta)");
        return mapToResponse(saved);
    }

    private void restorePartialStock(Long orderId, Long ingredientId,
                                     BigDecimal restoreQty, long now) {
        List<OrderStockDeduction> deductions = orderStockDeductionRepository.findByOrderId(orderId)
                .stream()
                .filter(d -> d.getIngredientStock().getIngredientId().equals(ingredientId))
                .sorted(Comparator.comparingLong(OrderStockDeduction::getId).reversed())
                .toList();

        BigDecimal remaining = restoreQty;
        for (OrderStockDeduction d : deductions) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal canRestore = remaining.min(d.getQuantity());
            remaining = remaining.subtract(canRestore);

            IngredientStock stock = d.getIngredientStock();

            // Hoàn lô — cùng lý do như restoreStock(): không được có nhánh nào
            // chỉ cộng tồn tổng mà bỏ qua lô.
            IngredientExpiry linked = d.getIngredientExpiry() != null
                    ? ingredientExpiryRepository.findById(d.getIngredientExpiry().getId()).orElse(null)
                    : null;

            if (linked != null) {
                linked.setQuantity(linked.getQuantity().add(canRestore));
                linked.setUpdatedAt(now);
                ingredientExpiryRepository.save(linked);
            } else {
                ingredientExpiryRepository.save(IngredientExpiry.builder()
                        .warehouse(stock.getWarehouse())
                        .ingredientId(stock.getIngredientId())
                        .expiryDate(d.getExpiryDate())
                        .costPrice(d.getCostPrice() != null ? d.getCostPrice() : BigDecimal.ZERO)
                        .quantity(canRestore)
                        .createdAt(now).updatedAt(now).build());
            }
            if (d.getCostPrice() != null && d.getCostPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal costToRestore = d.getCostPrice().multiply(canRestore).setScale(2, RoundingMode.HALF_UP);
                // BUG FIX (race lost update cost): atomic addCostValue thay cho RMW.
                // stockQuantity đã được cộng atomic ở caller (updateOrderItems),
                // ở đây chỉ cần cộng cost.
                stockMutationService.addCostValue(
                        stock.getIngredientId(), stock.getWarehouse().getId(),
                        costToRestore, now);
            }

            if (canRestore.compareTo(d.getQuantity()) >= 0)
                orderStockDeductionRepository.delete(d);
            else {
                d.setQuantity(d.getQuantity().subtract(canRestore));
                orderStockDeductionRepository.save(d);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0)
            log.warn("[RESTORE_PARTIAL] Còn {} chưa hoàn được cho orderId={} ingId={}",
                    remaining, orderId, ingredientId);
    }

    // ════════════════════════════════════════════════════════════════
    // TẠO ĐƠN HÀNG
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request, Long userId) {
        long now = System.currentTimeMillis();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        if (user.isLockAccount())
            throw new RuntimeException("Tài khoản bị khóa, không thể tạo đơn mới");

        String orderedByName = (request.getOrderedByName() != null && !request.getOrderedByName().isBlank())
                ? request.getOrderedByName() : "";

        Customer customer = null;
        if (request.getCustomerId() != null)
            customer = customerRepository.findById(request.getCustomerId()).orElse(null);
        if (customer == null && request.getCustomerPhone() != null)
            customer = customerRepository.findByPhone(request.getCustomerPhone()).orElse(null);

        if (!customer.getIsActive())
            throw new RuntimeException("Khách hàng tạm ngưng tạo đơn mới");

        // Chặn công nợ trước khi dựng đơn — dựng xong rồi mới ném lỗi là phí công
        // trừ kho / tính giá.
        assertDebtAllowed(customer, request.getPaymentMethod());

        final Customer finalCustomer = customer;
        boolean isCompany = finalCustomer != null
                && finalCustomer.getCustomerType() == Customer.CustomerType.COMPANY;

        Long warehouseId = request.getWarehouseId();
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new RuntimeException("Kho không tồn tại: " + warehouseId));

        String customerType, customerName, customerPhone, customerEmail, shippingAddress;
        String companyPhone = null, companyAddress = null, companyName = null;
        String shortName = null, taxCode = null, contactName = null;
        int discountRate;

        String deliveryAddress = firstNonBlank(request.getReceiverAddress(), request.getShippingAddress());
        assertPaymentMethodAllowedForZone(finalCustomer, request.getPaymentMethod(),
                request.getProvinceName(), request.getWardName(), deliveryAddress);

        if (isCompany) {
            customerType    = "COMPANY";
            companyName     = finalCustomer.getCompanyName();
            taxCode         = finalCustomer.getTaxCode();
            contactName     = finalCustomer.getContactName();
            companyPhone    = finalCustomer.getCompanyPhone();
            companyAddress  = finalCustomer.getCompanyAddress();
            customerName    = firstNonBlank(shortName, companyName, finalCustomer.getName());
            customerPhone   = firstNonBlank(request.getCustomerPhone(), finalCustomer.getPhone());
            customerEmail   = firstNonBlank(request.getCustomerEmail(), finalCustomer.getEmail());
            shippingAddress = deliveryAddress;
            discountRate    = finalCustomer.getDiscountRate() > 0
                    ? finalCustomer.getDiscountRate()
                    : (request.getDiscountRate() != null ? request.getDiscountRate() : 0);
        } else {
            customerType    = "RETAIL";
            customerName    = request.getCustomerName();
            customerPhone   = firstNonBlank(request.getCustomerPhone(),
                    finalCustomer != null ? finalCustomer.getPhone() : null);
            customerEmail   = firstNonBlank(request.getCustomerEmail(),
                    finalCustomer != null ? finalCustomer.getEmail() : null);
            shippingAddress = deliveryAddress;
            discountRate    = (finalCustomer != null && finalCustomer.getDiscountRate() > 0)
                    ? finalCustomer.getDiscountRate()
                    : (request.getDiscountRate() != null ? request.getDiscountRate() : 0);
        }

        String orderCode = generateOrderCode();

        Long visibleToSellerId = null;
        if (finalCustomer != null && finalCustomer.getCustomerType() == Customer.CustomerType.COMPANY) {
            User customerCreator = finalCustomer.getCreatedBySeller();
            if (customerCreator != null) {
                Set<Role> creatorRoles = customerCreator.getAllRoles();
                boolean creatorIsPureSeller = creatorRoles.contains(Role.SELLER)
                        && !creatorRoles.contains(Role.SUPER_SELLER)
                        && !creatorRoles.contains(Role.ADMIN)
                        && !creatorRoles.contains(Role.OWNER)
                        && !creatorRoles.contains(Role.SUPERADMIN);
                if (creatorIsPureSeller) visibleToSellerId = customerCreator.getId();
            }
        }

        Boolean showPrices = request.getShowPrices() != null ? request.getShowPrices() : Boolean.TRUE;
        Boolean hideAllPrices = request.getHideAllPrices() != null && request.getHideAllPrices();
        if (hideAllPrices) showPrices = true;

        Order order = Order.builder()
                .orderCode(orderCode).user(user)
                .warehouseName(warehouse.getName()).warehouseId(warehouse.getId())
                .customer(finalCustomer).customerName(customerName)
                .customerPhone(customerPhone).customerEmail(customerEmail)
                .discountRate(discountRate).receiverName(request.getReceiverName())
                .type(request.getType()).vatRate(VatRate.ZERO)
                .subtotal(BigDecimal.ZERO).discountAmount(BigDecimal.ZERO)
                .vatAmount(BigDecimal.ZERO).totalAmount(BigDecimal.ZERO).finalAmount(BigDecimal.ZERO)
                .status(OrderStatus.PREPARING)
                .paymentStatus(resolvePaymentStatus(request.getPaymentMethod()))
                .paymentMethod(request.getPaymentMethod()).notes(request.getNotes())
                .companyPhone(companyPhone)
                .debtDays(request.getPaymentMethod().equalsIgnoreCase("debt") ? customer.getDebtDays() : 0)
                .companyAddress(companyAddress).customerType(customerType)
                .companyName(companyName).pendingPaymentAt(0L)
                // SNAPSHOT yêu cầu thanh toán trước từ khách hàng tại thời điểm tạo đơn
                // Snapshot: chỉ ghi TRUE khi khách được cấu hình yêu cầu thu trước.
                // Các điều kiện còn lại (vùng giao, hình thức thanh toán) KHÔNG snapshot
                // vì địa chỉ và phương thức đều sửa được sau khi tạo đơn — chốt cứng lúc
                // này sẽ giữ nguyên kết luận cũ dù đơn đã đổi sang địa chỉ khác.
                .requirePrepayment(finalCustomer != null
                        && Boolean.TRUE.equals(finalCustomer.getRequirePrepayment()))
                .shortName(shortName).taxCode(taxCode).contactName(contactName)
                .shippingAddress(deliveryAddress).deliveryAddress(deliveryAddress)
                .provinceName(request.getProvinceName()).wardName(request.getWardName())
                .orderedByName(orderedByName).createdAt(now).updatedAt(now)
                .deliveryDatetime(request.getDeliveryDatetime())
                .showPrices(showPrices).hideAllPrices(hideAllPrices)
                .visibleToSellerId(visibleToSellerId).orderItems(new ArrayList<>()).build();

        Long kpiUserId = resolveKpiUserId(user, finalCustomer, request.getIncludeKpi());
        order.setKpiUserId(kpiUserId);

        Order savedOrder = orderRepository.save(order);

        Map<Long, BigDecimal> ingredientUsageMap = new LinkedHashMap<>();
        List<Map<Long, BigDecimal>> itemIngredientUsage = new ArrayList<>();
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CreateOrderRequest.OrderItemRequest itemReq : request.getItems()) {
            Map<Long, BigDecimal> usageForThisItem = new LinkedHashMap<>();
            OrderItem item = buildOrderItem(savedOrder, itemReq, usageForThisItem);
            orderItems.add(item);
            subtotal = subtotal.add(item.getSubtotal());
            itemIngredientUsage.add(usageForThisItem);
            usageForThisItem.forEach((ingId, qty) -> ingredientUsageMap.merge(ingId, qty, BigDecimal::add));
        }

        Map<Long, BigDecimal> ingredientUnitCostMap = new LinkedHashMap<>();
        List<WarehouseReceiptItem> receiptItems = new ArrayList<>();

        // BUG FIX 1.2 (race lost update stockQuantity) + DEADLOCK GUARD:
        // sort theo ingredientId ASC → khi nhiều đơn cùng lúc trừ nhiều
        // ingredient chung, chúng lock cùng thứ tự → không deadlock chéo.
        List<Map.Entry<Long, BigDecimal>> sortedEntries = ingredientUsageMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        for (Map.Entry<Long, BigDecimal> entry : sortedEntries) {
            Ingredient ing = ingredientRepository.findById(entry.getKey())
                    .orElseThrow(() -> new RuntimeException("Ingredient not found: " + entry.getKey()));
            BigDecimal needed = entry.getValue();

            // BUG FIX 1.2: dùng atomic decrease thay cho check-modify-write.
            // Trước: 2 seller cùng đọc snapshot=15, cùng check pass, cùng save 5
            //         → mất 10kg trong kho vật lý.
            // Sau: 1 câu UPDATE atomic WITH stock_quantity >= needed → MySQL
            //      serialize, chỉ 1 request thành công nếu không đủ hàng.
            //      Nếu 0 → throw InsufficientStockException với thông tin
            //      chi tiết cho FE hiển thị inline.
            StockMutationResult mut = stockMutationService.decrease(
                    ing.getId(), warehouseId, needed, now);
            BigDecimal before = mut.getBefore();
            BigDecimal after  = mut.getAfter();

            BigDecimal costDeducted = fifoDeductService.deduct(savedOrder.getId(), warehouse, ing, needed, now);
            BigDecimal unitCost = BigDecimal.ZERO;
            if (needed.compareTo(BigDecimal.ZERO) > 0 && costDeducted.compareTo(BigDecimal.ZERO) > 0)
                unitCost = costDeducted.divide(needed, 6, RoundingMode.HALF_UP);
            ingredientUnitCostMap.put(ing.getId(), unitCost);

            receiptItems.add(WarehouseReceiptItem.builder()
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(needed.negate())
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .difference(after.subtract(before))
                    .build());
        }

        for (int i = 0; i < orderItems.size(); i++) {
            OrderItem item = orderItems.get(i);
            BigDecimal itemCost = BigDecimal.ZERO;
            for (Map.Entry<Long, BigDecimal> u : itemIngredientUsage.get(i).entrySet()) {
                BigDecimal unitCost = ingredientUnitCostMap.getOrDefault(u.getKey(), BigDecimal.ZERO);
                itemCost = itemCost.add(unitCost.multiply(u.getValue()));
            }
            item.setCostPrice(itemCost.setScale(2, RoundingMode.HALF_UP));
        }

        if (!receiptItems.isEmpty()) {
            WarehouseReceipt receipt = WarehouseReceipt.builder()
                    .receiptCode(generateReceiptCode("EXP"))
                    .receiptType(WarehouseReceipt.ReceiptType.EXPORT_ORDER)
                    .warehouse(warehouse).order(savedOrder)
                    .referenceCode(savedOrder.getOrderCode())
                    .note("Xuất kho theo đơn hàng: " + savedOrder.getOrderCode())
                    .createdBy(user).createdByName(orderedByName)
                    .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();
            for (WarehouseReceiptItem item : receiptItems) item.setReceipt(receipt);
            receipt.getItems().addAll(receiptItems);
            warehouseReceiptRepository.save(receipt);
        }

        // ── FIX: dùng effectiveQty cho BOX khi tính item discount ──
        BigDecimal itemDiscountTotal = BigDecimal.ZERO;
        for (OrderItem oi : orderItems) {
            int pct = oi.getDiscountPercent() != null ? oi.getDiscountPercent() : 0;
            if (pct == 0) continue;
            BigDecimal effectiveQty = oi.getQuantity();
            if (oi.getUnitsPerBox() != null && oi.getUnitsPerBox() > 0) {
                effectiveQty = oi.getQuantity().multiply(BigDecimal.valueOf(oi.getUnitsPerBox()));
            }
            BigDecimal lineGross = oi.getUnitPrice().multiply(effectiveQty);
            itemDiscountTotal = itemDiscountTotal.add(
                    lineGross.multiply(BigDecimal.valueOf(pct))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }

        BigDecimal subtotalAfterItemDiscount = subtotal.subtract(itemDiscountTotal);
        BigDecimal billDiscountAmount = calcDiscountAmount(
                subtotalAfterItemDiscount, request.getDiscountAmount(),
                request.getDiscountRate(), discountRate);
        BigDecimal discountAmount = itemDiscountTotal.add(billDiscountAmount).setScale(2, RoundingMode.HALF_UP);

        if (request.getDiscountAmount() != null && request.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0
                && subtotal.compareTo(BigDecimal.ZERO) > 0) {
            discountRate = discountAmount.multiply(BigDecimal.valueOf(100))
                    .divide(subtotal, 0, RoundingMode.HALF_UP).intValue();
        } else if (itemDiscountTotal.compareTo(BigDecimal.ZERO) > 0 && request.getDiscountRate() == null) {
            discountRate = discountAmount.multiply(BigDecimal.valueOf(100))
                    .divide(subtotal, 0, RoundingMode.HALF_UP).intValue();
        } else if (request.getDiscountRate() != null) {
            discountRate = request.getDiscountRate();
        }

        BigDecimal afterDiscount = subtotal.subtract(discountAmount);
        BigDecimal[] vatResult   = calcVatForItems(orderItems, subtotal, afterDiscount);
        BigDecimal exclusiveVat  = vatResult[0];   // ← FIX: VAT ngoài giá (cộng vào finalAmount)
        BigDecimal totalVat      = vatResult[1];   //        tổng VAT (lưu DB hiển thị)

        BigDecimal surchargeVal = request.getSurchargeItems() != null && !request.getSurchargeItems().isEmpty()
                ? calcSurchargeTotal(request.getSurchargeItems())
                : (request.getSurcharge() != null ? request.getSurcharge() : BigDecimal.ZERO);
        String surchargeDetail = serializeSurchargeItems(request.getSurchargeItems());
        savedOrder.setSurchargeDetail(surchargeDetail);

        savedOrder.setSubtotal(subtotal);
        savedOrder.setDiscountAmount(discountAmount);
        savedOrder.setVatAmount(totalVat);              // lưu tổng VAT (inclusive + exclusive) để hiển thị
        savedOrder.setTotalAmount(afterDiscount);
        savedOrder.setFinalAmount(afterDiscount.add(exclusiveVat).add(surchargeVal)    // ← FIX: chỉ cộng exclusive
                .setScale(0, RoundingMode.HALF_UP));
        savedOrder.setSurcharge(surchargeVal);
        savedOrder.getOrderItems().addAll(orderItems);

        Order finalOrder = orderRepository.save(savedOrder);
        log(finalOrder, "CREATED", savedOrder.getUser().getFullName(), "SELLER", null);

        String payload = "{\"orderId\":" + finalOrder.getId()
                + ",\"orderCode\":\"" + finalOrder.getOrderCode() + "\"}";
        notifyWarehouseUsersOfWarehouse(warehouseId, "ORDER_CREATED",
                "Đơn mới " + finalOrder.getOrderCode() + " từ " + order.getUser().getFullName(), payload);

        return mapToResponse(finalOrder);
    }

    // ════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════

    private BigDecimal calcDiscountAmount(BigDecimal subtotal, BigDecimal discountAmountInput,
                                          Integer discountRateInput, int discountRateCurrent) {
        if (discountAmountInput != null && discountAmountInput.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal maxAllowed = subtotal.multiply(BigDecimal.valueOf(10))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            return discountAmountInput.min(maxAllowed).setScale(2, RoundingMode.HALF_UP);
        }
        int rate = discountRateInput != null ? discountRateInput : discountRateCurrent;
        if (rate > 0)
            return subtotal.multiply(BigDecimal.valueOf(rate))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return BigDecimal.ZERO.setScale(2);
    }

    private BigDecimal[] calcVatForItems(List<OrderItem> items,
                                         BigDecimal subtotal, BigDecimal afterDiscount) {
        BigDecimal totalVat     = BigDecimal.ZERO;
        BigDecimal exclusiveVat = BigDecimal.ZERO;

        // Tính VAT cho từng item
        for (OrderItem item : items) {
            int rate = item.getVatRate() != null ? item.getVatRate() : 0;
            if (rate == 0) continue;

            // ── FIX: tính effective quantity cho BOX products ──
            BigDecimal effectiveQty = item.getQuantity();
            if (item.getUnitsPerBox() != null && item.getUnitsPerBox() > 0) {
                effectiveQty = item.getQuantity().multiply(BigDecimal.valueOf(item.getUnitsPerBox()));
            }

            // Tính lineGross dùng effectiveQty
            BigDecimal lineGross = item.getUnitPrice().multiply(effectiveQty);
            int itemDiscPct = item.getDiscountPercent() != null ? item.getDiscountPercent() : 0;
            BigDecimal itemDiscount = itemDiscPct > 0
                    ? lineGross.multiply(BigDecimal.valueOf(itemDiscPct))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // Phần giảm giá cấp đơn (bill-level discount) vẫn chia tỷ trọng
            BigDecimal itemDiscountTotal = BigDecimal.ZERO;
            for (OrderItem oi : items) {
                int pct = oi.getDiscountPercent() != null ? oi.getDiscountPercent() : 0;
                if (pct == 0) continue;
                BigDecimal effQty = oi.getQuantity();
                if (oi.getUnitsPerBox() != null && oi.getUnitsPerBox() > 0) {
                    effQty = oi.getQuantity().multiply(BigDecimal.valueOf(oi.getUnitsPerBox()));
                }
                BigDecimal lg = oi.getUnitPrice().multiply(effQty);
                itemDiscountTotal = itemDiscountTotal.add(
                        lg.multiply(BigDecimal.valueOf(pct))
                                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            }
            BigDecimal subtotalAfterItemDisc = subtotal.subtract(itemDiscountTotal);
            BigDecimal billDisc = subtotal.subtract(afterDiscount).subtract(itemDiscountTotal);
            if (billDisc.compareTo(BigDecimal.ZERO) < 0) billDisc = BigDecimal.ZERO;

            // Chia bill discount theo tỷ trọng (trên subtotal sau item discount)
            BigDecimal billDiscForItem = BigDecimal.ZERO;
            if (billDisc.compareTo(BigDecimal.ZERO) > 0 && subtotalAfterItemDisc.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal itemAfterOwnDisc = lineGross.subtract(itemDiscount);
                BigDecimal prop = itemAfterOwnDisc.divide(subtotalAfterItemDisc, 10, RoundingMode.HALF_UP);
                billDiscForItem = billDisc.multiply(prop).setScale(2, RoundingMode.HALF_UP);
            }

            BigDecimal itemAfterDisc = lineGross.subtract(itemDiscount).subtract(billDiscForItem);
            if (itemAfterDisc.compareTo(BigDecimal.ZERO) < 0) itemAfterDisc = BigDecimal.ZERO;

            BigDecimal itemVat;
            if ("EXCLUSIVE".equals(item.getVatMode())) {
                // VAT ngoài giá → cộng thêm vào finalAmount
                itemVat = itemAfterDisc.multiply(BigDecimal.valueOf(rate))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                exclusiveVat = exclusiveVat.add(itemVat);
            } else {
                // VAT trong giá → chỉ hiển thị, KHÔNG cộng vào finalAmount
                itemVat = itemAfterDisc.multiply(BigDecimal.valueOf(rate))
                        .divide(BigDecimal.valueOf(100 + rate), 2, RoundingMode.HALF_UP);
            }
            item.setVatAmount(itemVat);
            totalVat = totalVat.add(itemVat);

        }

        // [0] = exclusiveVat (cộng vào finalAmount), [1] = totalVat (lưu DB hiển thị)
        return new BigDecimal[]{ exclusiveVat, totalVat };
    }

    /**
     * CHẶN CÔNG NỢ KHI KHÁCH CHƯA CÓ HỢP ĐỒNG.
     *
     * <p>Bán chịu mà không có hợp đồng thì khoản nợ không có căn cứ đòi. FE đã ẩn
     * lựa chọn công nợ, nhưng chặn ở đây mới là chốt thật: đơn có thể được tạo
     * hoặc đổi phương thức từ nhiều màn khác nhau (POS, sửa đơn, kế toán, trưởng
     * phòng kinh doanh) — sót một đường là thủng.
     *
     * <p>Khách lẻ (customer = null) cũng không được mua công nợ: không có hồ sơ
     * thì không có ai để đòi.
     *
     * <p>KHÁCH CŨ (tạo trước khi có tính năng hợp đồng) được miễn quy tắc này —
     * họ vẫn bán chịu như trước. Chỉ khách tạo mới mới bắt buộc có hợp đồng.
     */
    /**
     * CHẶN HÌNH THỨC THANH TOÁN KHÔNG HỢP LỆ THEO VÙNG GIAO.
     *
     * <p>Khách <b>chưa có hợp đồng</b> chỉ được TIỀN MẶT hoặc CHUYỂN KHOẢN (công nợ đã bị
     * {@link #assertDebtAllowed} chặn riêng). Trong hai lựa chọn đó:
     *
     * <ul>
     *   <li>Giao <b>trong TP.HCM cũ</b> → được cả TM lẫn CK.</li>
     *   <li>Giao <b>ngoài</b> (gồm phường/xã cũ của Bình Dương, Bà Rịa – Vũng Tàu, và mọi
     *       tỉnh khác) → <b>chỉ CHUYỂN KHOẢN</b>.</li>
     * </ul>
     *
     * <p>Vì sao chặn ngay từ lúc tạo đơn thay vì chỉ chặn ở khâu giao: đơn tiền mặt đi
     * tỉnh xa tạo xong sẽ nằm chờ vô thời hạn — kho không được giao (phải thu trước), mà
     * khách cũng không có cách trả tiền mặt từ xa. Người tạo đơn phải biết ngay lúc chọn,
     * không phải để kho phát hiện vài ngày sau.
     *
     * <p>Khách <b>đã có hợp đồng</b> không bị ràng buộc này — họ có công nợ và có căn cứ
     * pháp lý để đòi.
     *
     * @param deliveryAddress địa chỉ giao của ĐƠN (không phải địa chỉ mặc định của khách)
     */
    private void assertPaymentMethodAllowedForZone(Customer customer, String paymentMethod,
                                                   String provinceName, String wardName,
                                                   String deliveryAddress) {
        // Nhận tại kho không phải "giao đi tỉnh" nên không bị hạn chế hình thức thanh toán.
        if (addressCatalogService.isPickupAtWarehouse(deliveryAddress)) return;
        if (paymentMethod == null || paymentMethod.isBlank()) return;
        if (!"CASH".equalsIgnoreCase(paymentMethod.trim())) return;   // chỉ chặn tiền mặt

        // Khách có hợp đồng → miễn.
        if (customer != null && customer.getId() != null
                && customerContractService.isDebtAllowed(customer)) return;

        if (!addressCatalogService.isCodAllowed(provinceName, wardName)) {
            throw new RuntimeException(
                    "Địa chỉ giao không thuộc vùng được giao COD — khách chưa có hợp đồng chỉ "
                            + "được thanh toán bằng CHUYỂN KHOẢN. Vui lòng đổi hình thức thanh toán.");
        }
    }

    private void assertDebtAllowed(Customer customer, String paymentMethod) {
        if (paymentMethod == null) return;
        if (!"DEBT".equalsIgnoreCase(paymentMethod.trim())) return;

        if (customer == null || customer.getId() == null)
            throw new RuntimeException("Khách lẻ không có hợp đồng nên không được thanh toán công nợ.");

        // Khách cũ được miễn — xem CustomerContractService.isDebtAllowed.
        if (!customerContractService.isDebtAllowed(customer))
            throw new RuntimeException(
                    "Khách hàng chưa có hợp đồng nên không được chọn thanh toán công nợ. "
                            + "Vui lòng tải hợp đồng lên trước.");
    }

    private PaymentStatus resolvePaymentStatus(String paymentMethod) {
        return PaymentStatus.UNPAID;
    }

    private ResolvedPrice resolvePrice(Product product, CreateOrderRequest.OrderItemRequest req) {
        BigDecimal base = product.getBasePrice();
        String mode = req.getPriceMode() != null ? req.getPriceMode().toUpperCase(Locale.ROOT) : "TIER";

        if (Boolean.TRUE.equals(req.getIsManualPrice()) && req.getSentUnitPrice() != null
                && !"DISCOUNT_PERCENT".equalsIgnoreCase(mode)) {
            ResolvedPrice meta = resolveFromTierOrBase(product, req, base, mode);
            return new ResolvedPrice(req.getSentUnitPrice(), meta.priceMode(),
                    meta.tierId(), meta.tierName(), meta.discountPercent());
        }

        ResolvedPrice resolved = resolveFromTierOrBase(product, req, base, mode);

        if (req.getSentUnitPrice() != null && !"DISCOUNT_PERCENT".equalsIgnoreCase(mode)) {
            BigDecimal diff = resolved.unitPrice().subtract(req.getSentUnitPrice()).abs();
            if (diff.compareTo(PRICE_DIFF_THRESHOLD) > 0)
                throw new PriceChangedException(String.format(
                        "Giá '%s' đã thay đổi (UI: %s → Thực tế: %s). Giá mới đã được cập nhật vào giỏ hàng, vui lòng kiểm tra lại.",
                        product.getName(), req.getSentUnitPrice().toPlainString(),
                        resolved.unitPrice().toPlainString()));
        }
        return resolved;
    }

    private ResolvedPrice resolveFromTierOrBase(Product product,
                                                CreateOrderRequest.OrderItemRequest req,
                                                BigDecimal base, String mode) {
        return switch (mode) {
            case "BASE" -> new ResolvedPrice(base, "BASE", null, null, null);

            case "DISCOUNT_PERCENT" -> {
                int pct = req.getDiscountPercent() != null ? req.getDiscountPercent() : 0;
                BigDecimal baseForDiscount = base;
                List<ProductPriceTier> allTiers = priceTierRepository.findByProductIdSortedAsc(product.getId());
                if (!allTiers.isEmpty()) {
                    if (req.getTierId() != null) {
                        baseForDiscount = allTiers.stream()
                                .filter(t -> t.getId().equals(req.getTierId()))
                                .map(ProductPriceTier::getPrice).findFirst().orElse(base);
                    } else {
                        ProductPriceTier matched = allTiers.get(0);
                        for (ProductPriceTier t : allTiers) {
                            if (t.getMinQuantity() == null) continue;
                            if (t.getMinQuantity().compareTo(req.getQuantity()) <= 0) matched = t;
                            else break;
                        }
                        baseForDiscount = matched.getPrice();
                    }
                }
                BigDecimal discounted = baseForDiscount.multiply(BigDecimal.valueOf(100L - pct))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                yield new ResolvedPrice(discounted, "DISCOUNT_PERCENT", req.getTierId(), null, pct);
            }

            default -> {
                if (req.getTierId() != null) {
                    Optional<ProductPriceTier> tierOpt = priceTierRepository
                            .findByIdAndProductId(req.getTierId(), product.getId());
                    if (tierOpt.isPresent()) {
                        ProductPriceTier tier = tierOpt.get();
                        yield new ResolvedPrice(tier.getPrice(), "TIER",
                                tier.getId(), tier.getTierName(), null);
                    }
                    log.warn("[PRICE] tierId={} not found for product={} — fallback to qty={}",
                            req.getTierId(), product.getId(), req.getQuantity());
                }
                List<ProductPriceTier> matching =
                        priceTierRepository.findMatchingTiers(product.getId(), req.getQuantity());
                if (!matching.isEmpty()) {
                    ProductPriceTier best = matching.get(0);
                    yield new ResolvedPrice(best.getPrice(), "TIER", best.getId(), best.getTierName(), null);
                }
                log.warn("[PRICE] No tier for product={} qty={}, using basePrice",
                        product.getId(), req.getQuantity());
                yield new ResolvedPrice(base, "BASE", null, null, null);
            }
        };
    }

    // ════════════════════════════════════════════════════════════════
    // BUILD ORDER ITEM — fill đầy đủ snapshot, không phụ thuộc product sau này
    // ════════════════════════════════════════════════════════════════
    private OrderItem buildOrderItem(Order order, CreateOrderRequest.OrderItemRequest itemReq,
                                     Map<Long, BigDecimal> usageMap) {
        Product product = productRepository.findByIdAndIsActiveTrue(itemReq.getProductId())
                .orElseThrow(() -> new RuntimeException("Sản phẩm không tồn tại: " + itemReq.getProductId()));

        ResolvedPrice resolved = resolvePrice(product, itemReq);

        // Snapshot tier price (giá per-unit của tier tại thời điểm bán)
        BigDecimal tierPriceSnapshot = null;
        if (resolved.tierId() != null) {
            tierPriceSnapshot = priceTierRepository.findById(resolved.tierId())
                    .map(ProductPriceTier::getPrice)
                    .orElse(null);
        }

        boolean isBOX = "BOX".equals(itemReq.getSaleType());
        Integer unitsPerBoxSnap = isBOX ? product.getUnitsPerBox() : null;
        BigDecimal effectiveMultiplier = (unitsPerBoxSnap != null && unitsPerBoxSnap > 0)
                ? itemReq.getQuantity().multiply(BigDecimal.valueOf(unitsPerBoxSnap))
                : itemReq.getQuantity();

        BigDecimal subtotal = resolved.unitPrice().multiply(effectiveMultiplier)
                .setScale(2, RoundingMode.HALF_UP);

        int vatRatePct = itemReq.getVatRate() != null
                ? itemReq.getVatRate()
                : (product.getVatRate() != null ? product.getVatRate().getPercentage() : 0);

        List<ProductIngredient> ings = productIngredientRepository.findByProductId(product.getId());

        // Unit lấy từ ingredient đầu tiên (nếu có), fallback về product.unit
        String unit = (!ings.isEmpty() && ings.get(0).getIngredientUnitSnapshot() != null)
                ? ings.get(0).getIngredientUnitSnapshot()
                : (product.getUnit() != null ? product.getUnit() : "kg");

        Integer itemDiscountPct = "DISCOUNT_PERCENT".equals(resolved.priceMode())
                ? resolved.discountPercent()
                : itemReq.getDiscountPercent();

        OrderItem item = OrderItem.builder()
                .order(order)
                // ── Core snapshot ──
                .productId(product.getId())
                .productName(product.getName())
                .productImageUrl(product.getImageUrl())
                .unit(unit)
                // ── Product snapshots mới ──
                .categorySnapshot(product.getCategory())
                .skuSnapshot(product.getSku())
                .packagingDescriptionSnapshot(product.getPackagingDescription())
                .maxDiscountRateSnapshot(product.getMaxDiscountRate())
                .specificationSnapshot(product.getSpecification())
                .misaCategorySnapshot(product.getMisaCategory())
                // ── Quy cách bán ──
                .saleType(itemReq.getSaleType() != null ? itemReq.getSaleType() : "RETAIL")
                .unitsPerBox(unitsPerBoxSnap)
                // ── Giá snapshot ──
                .basePrice(product.getBasePrice())
                .unitPrice(resolved.unitPrice())
                .priceMode(resolved.priceMode())
                // ── Tier snapshot ──
                .tierId(resolved.tierId())
                .tierName(resolved.tierName())
                .tierPriceSnapshot(tierPriceSnapshot)
                .discountPercent(itemDiscountPct)
                // ── VAT snapshot ──
                .vatRate(vatRatePct)
                .vatMode(itemReq.getVatMode() != null
                        ? itemReq.getVatMode()
                        : (product.getVatMode() != null ? product.getVatMode().name() : "INCLUSIVE"))
                .vatAmount(BigDecimal.ZERO)
                // ── Số lượng & tổng ──
                .quantity(itemReq.getQuantity())
                .subtotal(subtotal)
                .notes(itemReq.getNotes())
                .orderItemIngredients(new ArrayList<>())
                .build();

        item.setOrderItemIngredients(
                collectIngredients(item, ings, itemReq.getQuantity(), usageMap, unitsPerBoxSnap));

        return item;
    }

    // ════════════════════════════════════════════════════════════════
    // COLLECT INGREDIENTS — snapshot đầy đủ, không cần ingredient entity sau này
    // ════════════════════════════════════════════════════════════════
    private List<OrderItemIngredient> collectIngredients(OrderItem orderItem,
                                                         List<ProductIngredient> productIngredients,
                                                         BigDecimal quantity,
                                                         Map<Long, BigDecimal> usageMap,
                                                         Integer unitsPerBox) {
        BigDecimal effectiveQty = (unitsPerBox != null && unitsPerBox > 0)
                ? quantity.multiply(BigDecimal.valueOf(unitsPerBox)) : quantity;

        List<OrderItemIngredient> result = new ArrayList<>();
        for (ProductIngredient pi : productIngredients) {
            // Dùng plain id + snapshot — không gọi pi.getIngredient()
            Long ingId       = pi.getIngredientId();
            String ingName   = pi.getIngredientNameSnapshot();
            String ingImgUrl = pi.getIngredientImageUrlSnapshot();
            String ingUnit   = pi.getIngredientUnitSnapshot();

            BigDecimal ingQtyPerUnit = pi.getQty() != null ? pi.getQty() : BigDecimal.ONE;
            BigDecimal usage;
            if (Boolean.TRUE.equals(pi.getCanOverride()))
                usage = effectiveQty.multiply(ingQtyPerUnit).setScale(3, RoundingMode.HALF_UP);
            else
                usage = effectiveQty.setScale(0, RoundingMode.CEILING)
                        .multiply(ingQtyPerUnit).setScale(3, RoundingMode.HALF_UP);
            usageMap.merge(ingId, usage, BigDecimal::add);

            result.add(OrderItemIngredient.builder()
                    .orderItem(orderItem)
                    .ingredientId(ingId)
                    .ingredientName(ingName)
                    .ingredientImageUrl(ingImgUrl)
                    .unit(ingUnit)
                    .quantityUsed(usage)
                    .qtyPerUnit(ingQtyPerUnit)
                    .build());
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    // MAP TO RESPONSE — đọc 100% từ snapshot, không join product/ingredient
    // ════════════════════════════════════════════════════════════════
    private OrderResponse mapToResponse(Order order) {
        List<OrderResponse.OrderItemResponse> itemResponses = order.getOrderItems().stream()
                .map(item -> OrderResponse.OrderItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .productImageUrl(item.getProductImageUrl())
                        .unit(item.getUnit())
                        .saleType(item.getSaleType() != null ? item.getSaleType() : "RETAIL")
                        .unitsPerBox(item.getUnitsPerBox())
                        .basePrice(item.getBasePrice())
                        .unitPrice(item.getUnitPrice())
                        .priceMode(item.getPriceMode())
                        .priceName(buildPriceLabel(item))
                        .defaultPrice(item.getBasePrice())
                        .tierId(item.getTierId())
                        .tierName(item.getTierName())
                        .discountPercent(item.getDiscountPercent())
                        .vatRate(item.getVatRate())
                        .vatAmount(item.getVatAmount())
                        .vatMode(item.getVatMode())
                        .quantity(item.getQuantity())
                        .subtotal(item.getSubtotal())
                        .returnedQty(item.getReturnedQty())  // ← THÊM: để FE highlight SP hoàn/đổi
                        .notes(item.getNotes())
                        // snapshot extras (nếu OrderItemResponse có các field này)
                        .categorySnapshot(item.getCategorySnapshot())
                        .skuSnapshot(item.getSkuSnapshot())
                        .packagingDescriptionSnapshot(item.getPackagingDescriptionSnapshot())
                        .maxDiscountRateSnapshot(item.getMaxDiscountRateSnapshot())
                        .specificationSnapshot(item.getSpecificationSnapshot())
                        .misaCategorySnapshot(item.getMisaCategorySnapshot())
                        .tierPriceSnapshot(item.getTierPriceSnapshot())
                        .ingredientsUsed(item.getOrderItemIngredients().stream()
                                .map(ii -> OrderResponse.IngredientUsed.builder()
                                        .ingredientId(ii.getIngredientId())
                                        .ingredientName(ii.getIngredientName())
                                        .ingredientImageUrl(ii.getIngredientImageUrl())
                                        .quantityUsed(ii.getQuantityUsed())
                                        .unit(ii.getUnit())
                                        .qtyPerUnit(ii.getQtyPerUnit())
                                        .build())
                                .collect(Collectors.toList()))
                        .build())
                .collect(Collectors.toList());
        return OrderResponse.builder()
                .id(order.getId()).orderCode(order.getOrderCode())
                .customerId(order.getCustomer() != null ? order.getCustomer().getId() : null)
                .customerName(resolveCustomerDisplayName(order.getCustomerName()))
                .warehouseName(order.getWarehouseName())
                .warehouseId(order.getWarehouseId())
                .customerPhone(order.getCustomerPhone()).customerEmail(order.getCustomerEmail())
                .shippingAddress(order.getShippingAddress()).subtotal(order.getSubtotal())
                .discountRate(order.getDiscountRate()).discountAmount(order.getDiscountAmount())
                .vatAmount(order.getVatAmount())
                .receiverName(order.getReceiverName()).totalAmount(order.getSubtotal())
                .finalAmount(order.getFinalAmount()).status(order.getStatus().name())
                .actualAmountPaid(order.getActualAmountPaid())
                .paymentStatus(order.getPaymentStatus().name())
                .paymentMethod(order.getPaymentMethod()).notes(order.getNotes())
                .customerType(order.getCustomerType()).companyName(order.getCompanyName())
                .orderedByName(order.getOrderedByName()).taxCode(order.getTaxCode())
                .requirePrepaymentEffective(isPrepaymentRequired(order))
                .prepaymentReason(isPrepaymentRequired(order) ? prepaymentReason(order) : null)
                .provinceName(order.getProvinceName()).wardName(order.getWardName())
                .contactName(order.getContactName()).deliveryAddress(order.getDeliveryAddress())
                .companyPhone(order.getCompanyPhone()).companyAddress(order.getCompanyAddress())
                .createdAt(order.getCreatedAt())
                // ── Tài xế: dùng deliveryInfo thay cho drivers ──
                .deliveryInfo(parseDeliveryInfo(order.getDeliveryInfoJson()))
                .deliveryDatetime(order.getDeliveryDatetime())
                .showPrices(order.getShowPrices() != null ? order.getShowPrices() : Boolean.TRUE)
                .hideAllPrices(order.getHideAllPrices() != null ? order.getHideAllPrices() : Boolean.FALSE)
                .surcharge(order.getSurcharge())
                .paidAmount(order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO)
                .estimatedDelivery(calcEstimatedDelivery(order.getDeliveryDatetime(), order.getCreatedAt()))
                .paymentDeadline(calcPaymentDeadline(
                        order.getPendingPaymentAt(), order.getCreatedAt(), order.getDebtDays()))
                .vatBreakdown(calcVatBreakdown(order.getOrderItems()))
                .surchargeDetail(order.getSurchargeDetail())
                // ── Hoàn/Đổi SP ──────────────────────────────────────────────
                .creditedFromSource(order.getCreditedFromSource())
                .linkType(order.getLinkType())
                .sourceOrderId(order.getSourceOrderId())
                .sourceOrderCode(order.getSourceOrderCode())
                .overpaidAmount(order.getOverpaidAmount())
                .returnExchangeNote(order.getReturnExchangeNote())
                .warehouseId(order.getWarehouseId())
                .overpaidRefundVoucherCode(order.getOverpaidRefundVoucherCode())
                .version(order.getVersion())
                // ── Hoàn tiền (REFUND từ đơn gốc) ────────────────────────────
                .pendingRefundAmount(order.getPendingRefundAmount())
                .refundedAmount(order.getRefundedAmount())
                .refundVoucherCode(order.getRefundVoucherCode())
                .items(itemResponses).build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseDeliveryInfo(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String calcEstimatedDelivery(Long deliveryDatetime, Long createdAt) {
        if (deliveryDatetime == null) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(deliveryDatetime), TZ)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));
    }

    private String calcPaymentDeadline(Long pendingPaymentAt, Long createdAt, Integer debtDays) {
        if (debtDays == null || debtDays == 0) return "Thanh toán khi nhận hàng";
        long baseTs = (pendingPaymentAt != null && pendingPaymentAt > 0)
                ? pendingPaymentAt : (createdAt != null ? createdAt : System.currentTimeMillis());
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(baseTs), TZ)
                .toLocalDate().plusDays(1 + debtDays).format(DATE_FMT);
    }

    private List<OrderResponse.VatBreakdownItem> calcVatBreakdown(List<OrderItem> items) {
        Map<Integer, BigDecimal> map = new TreeMap<>();
        for (OrderItem item : items) {
            Integer rate = item.getVatRate();
            if (rate == null || rate == 0) continue;
            BigDecimal amt = item.getVatAmount() != null ? item.getVatAmount() : BigDecimal.ZERO;
            map.merge(rate, amt, BigDecimal::add);
        }
        return map.entrySet().stream()
                .map(e -> OrderResponse.VatBreakdownItem.builder()
                        .rate(e.getKey()).amount(e.getValue()).build())
                .collect(Collectors.toList());
    }

    @Override
    public OrderResponse getOrderById(Long id) {
        return mapToResponse(orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found")));
    }

    @Override
    public OrderResponse getOrderByCode(String orderCode) {
        return mapToResponse(orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new RuntimeException("Order not found")));
    }

    @Override
    public List<OrderResponse> getMyOrders(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setStatus(newStatus);
        order.setUpdatedAt(System.currentTimeMillis());
        return mapToResponse(orderRepository.save(order));
    }

    // ════════════════════════════════════════════════════════════════
    // CANCEL ORDER
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelledWithLog(order);
        assertCancellable(order);
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(System.currentTimeMillis());
        return mapToResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId, Long actorUserId,
                                     String actorName, String actorRole, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelledWithLog(order);
        assertCancellable(order);

        long now = System.currentTimeMillis();
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(now);
        Order saved = orderRepository.save(order);

        restoreStock(orderId, now);
        log(saved, "CANCELLED", actorName, actorRole, "Lý do: " + reason);

        String payload = "{\"orderId\":" + orderId + ",\"orderCode\":\"" + saved.getOrderCode() + "\""
                + ",\"reason\":\"" + reason + "\"}";
        notifyCancellation(saved, actorUserId, actorRole,
                actorName + " đã hủy đơn " + saved.getOrderCode() + ". Lý do: " + reason, payload);
        return mapToResponse(saved);
    }

    private static final Set<OrderStatus> CANCELLABLE = Set.of(
            OrderStatus.PENDING, OrderStatus.CONFIRMED,
            OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.DELIVERING);

    private void assertCancellable(Order order) {
        if (!CANCELLABLE.contains(order.getStatus()))
            throw new IllegalStateException("Không thể hủy đơn ở trạng thái: " + order.getStatus());
    }

    private void assertNotCancelled(Order order) {
        if (order.getStatus() == OrderStatus.CANCELLED)
            throw new IllegalStateException(
                    "Đơn hàng " + order.getOrderCode() + " đã bị hủy, không thể thực hiện thao tác này.");
    }

    private void assertNotCancelledWithLog(Order order) {
        if (order.getStatus() != OrderStatus.CANCELLED) return;
        orderLogRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())
                .stream().filter(l -> "CANCELLED".equals(l.getAction())).reduce((a, b) -> b)
                .ifPresentOrElse(
                        l -> { throw new IllegalStateException(
                                "Đơn " + order.getOrderCode() + " đã bị hủy bởi "
                                        + l.getActorName() + " (" + l.getActorRole() + ")"
                                        + (l.getNote() != null ? ". " + l.getNote() : "")); },
                        () -> { throw new IllegalStateException(
                                "Đơn " + order.getOrderCode() + " đã bị hủy trước đó."); });
    }

    private void restoreStock(Long orderId, long now) {
        List<OrderStockDeduction> deductions = orderStockDeductionRepository.findByOrderId(orderId);
        if (deductions.isEmpty()) {
            log.warn("[CANCEL] Không tìm thấy deduction records cho orderId={}", orderId);
            return;
        }

        // BUG FIX (deadlock guard): sort deductions theo ingredientId ASC
        // → 2 request cancel song song lock cùng thứ tự → không deadlock.
        deductions.sort(java.util.Comparator.comparing(
                d -> d.getIngredientStock().getIngredientId()));

        for (OrderStockDeduction d : deductions) {
            IngredientStock stock = d.getIngredientStock();
            Long ingId = stock.getIngredientId();
            Long whId  = stock.getWarehouse().getId();
            BigDecimal qty = d.getQuantity();

            // BUG FIX 4.1 (race lost update): atomic increase thay cho RMW.
            // Trước: 2 người cùng cancel 2 đơn khác nhau cho cùng ingredient
            //         → cả 2 đọc snapshot → cả 2 save → mất số hoàn của 1 request.
            // Sau: 1 câu UPDATE atomic, cộng dồn đúng.
            stockMutationService.increase(ingId, whId, qty, now);

            // BUG FIX 4.1 (cost value race): atomic addCostValue thay cho RMW.
            if (d.getCostPrice() != null && d.getCostPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal costToRestore = d.getCostPrice().multiply(qty)
                        .setScale(2, RoundingMode.HALF_UP);
                stockMutationService.addCostValue(ingId, whId, costToRestore, now);
            }

            // HOÀN LÔ — luôn phải có, không được bỏ qua trường hợp nào.
            IngredientExpiry linked = d.getIngredientExpiry() != null
                    ? ingredientExpiryRepository.findById(d.getIngredientExpiry().getId()).orElse(null)
                    : null;

            if (linked != null) {
                linked.setQuantity(linked.getQuantity().add(qty));
                linked.setUpdatedAt(now);
                ingredientExpiryRepository.save(linked);
            } else {
                // Lô gốc đã bị xoá, hoặc bản ghi trừ kho cũ không lưu lô nào.
                // Tạo lô mới mang đúng HSD + giá vốn đã ghi lại lúc trừ.
                ingredientExpiryRepository.save(IngredientExpiry.builder()
                        .warehouse(stock.getWarehouse())
                        .ingredientId(stock.getIngredientId())
                        .expiryDate(d.getExpiryDate())
                        .costPrice(d.getCostPrice() != null ? d.getCostPrice() : BigDecimal.ZERO)
                        .quantity(qty)
                        .createdAt(now).updatedAt(now).build());
            }
        }
    }

    private void notifyCancellation(Order order, Long actorUserId, String actorRole,
                                    String message, String payload) {
        String role = actorRole != null ? actorRole.toUpperCase() : "";
        User creator = order.getUser();
        Role creatorRole = creator != null ? creator.getRole() : null;
        // Issue #4: Tạm thời không gửi noti đơn hàng cho OWNER và ADMIN
        switch (role) {
            case "SELLER", "SUPER_SELLER" ->
                    notifyRoles(List.of(Role.SUPER_SELLER, Role.ACCOUNTANT, Role.SUPER_ACCOUNTANT),
                            actorUserId, "ORDER_CANCELLED", message, payload);
            case "ACCOUNTANT" ->
                    notifyRoles(List.of(Role.SUPER_ACCOUNTANT),
                            actorUserId, "ORDER_CANCELLED", message, payload);
            case "SUPER_ACCOUNTANT" -> {
                // không gửi cho ADMIN/OWNER nữa
            }
            case "WAREHOUSE", "SUPER_WAREHOUSE" -> {
                notifyRoles(List.of(Role.SUPER_WAREHOUSE, Role.SUPER_SELLER, Role.ACCOUNTANT,
                                Role.SUPER_ACCOUNTANT),
                        actorUserId, "ORDER_CANCELLED", message, payload);
                if (creator != null && creatorRole == Role.SELLER
                        && (actorUserId == null || !actorUserId.equals(creator.getId())))
                    notificationService.sendToUser(creator, "SELLER", "ORDER_CANCELLED", message, payload);
            }
            default -> {
                // không gửi cho ADMIN/OWNER nữa
            }
        }
    }

    private void notifyRoles(List<Role> roles, Long actorUserId,
                             String eventType, String message, String payload) {
        for (Role r : roles)
            userRepository.findByRoleAndIsLockAccountFalse(r).forEach(u -> {
                if (actorUserId == null || !actorUserId.equals(u.getId()))
                    notificationService.sendToUser(u, r.name(), eventType, message, payload);
            });
    }

    private void notifyOrderUpdate(Order order, String eventType, String message,
                                   String payload, Long actorUserId) {
        Set<Long> notifiedUserIds = new HashSet<>();

        userRepository.findByRolesContaining(Role.ACCOUNTANT).forEach(u -> {
            if (actorUserId == null || u.getId() != actorUserId) {
                notificationService.sendToUser(u, "ACCOUNTANT", eventType, message, payload);
                notifiedUserIds.add(u.getId());
            }
        });

        userRepository.findByRolesContaining(Role.SUPER_ACCOUNTANT).forEach(u -> {
            if (actorUserId == null || u.getId() != actorUserId) {
                notificationService.sendToUser(u, "SUPER_ACCOUNTANT", eventType, message, payload);
                notifiedUserIds.add(u.getId());
            }
        });

        if (order.getUser() != null && (actorUserId == null || order.getUser().getId() != actorUserId)) {
            User orderUser = order.getUser();
            Set<Role> allRoles = orderUser.getAllRoles();
            if (allRoles.contains(Role.ACCOUNTANT) || allRoles.contains(Role.SUPER_ACCOUNTANT)) {
                if (allRoles.contains(Role.SELLER))
                    notificationService.sendToUser(orderUser, "SELLER", eventType, message, payload);
            } else if (!notifiedUserIds.contains(orderUser.getId())) {
                String sellerRole = orderUser.getRole() != null ? orderUser.getRole().name() : "SELLER";
                notificationService.sendToUser(orderUser, sellerRole, eventType, message, payload);
            }
        }
    }

    /** Public accessor dùng cho OrderExchangeService khi tạo đơn đổi SP. */
    public String generateOrderCodePublic() {
        return generateOrderCode();
    }

    /**
     * BUG FIX 1.1 (race order_code duplicate — tái hiện 100% trong test):
     * Trước: dùng orderRepository.countByCreatedAtBetween(...) + 1 → 2 request
     *         cùng đọc N → cùng sinh order_code với N+1 → 1 request fail với
     *         "Duplicate entry" (SQL constraint violation).
     * Sau: atomic incrementCounter trên bảng order_code_prefix → MySQL serialize
     *      hoàn toàn, mỗi request lấy 1 số duy nhất.
     */
    private String generateOrderCode() {
        int currentYear = LocalDate.now().getYear();
        OrderCodePrefix activePrefix = orderCodePrefixRepository.findByIsActiveTrue()
                .orElseGet(() -> createNewPrefix(currentYear));
        if (activePrefix.getYear() != currentYear) {
            activePrefix.setIsActive(false);
            orderCodePrefixRepository.save(activePrefix);
            activePrefix = createNewPrefix(currentYear);
        }

        // Atomic increment — MySQL InnoDB giữ row lock cho tới commit,
        // 2 request cùng gọi phải serialize.
        orderCodePrefixRepository.incrementCounter(activePrefix.getId());

        // Force reload counter từ DB (bỏ qua L1 cache của Hibernate).
        // Không dùng findById(): entity đang managed nên L1 sẽ trả lại object cũ
        // với counter chưa cập nhật → sinh mã trùng.
        entityManager.refresh(activePrefix);
        long counter = activePrefix.getCounter() != null ? activePrefix.getCounter() : 1L;

        return String.format("%s-%05d", activePrefix.getPrefix(), counter);
    }

    private OrderCodePrefix createNewPrefix(int year) {
        Set<String> usedPrefixes = orderCodePrefixRepository.findAll().stream()
                .map(OrderCodePrefix::getPrefix).collect(Collectors.toSet());
        String newPrefix = generateUniquePrefix(usedPrefixes);
        OrderCodePrefix created = orderCodePrefixRepository.save(
                OrderCodePrefix.builder().prefix(newPrefix).year(year).isActive(true)
                        .createdAt(System.currentTimeMillis()).build());
        log.info("[ORDER CODE] New prefix '{}' created for year {}", newPrefix, year);
        return created;
    }

    private String generateUniquePrefix(Set<String> usedPrefixes) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        Random random = new Random();
        for (int attempt = 0; attempt < 1000; attempt++) {
            String candidate = "" + chars.charAt(random.nextInt(chars.length()))
                    + chars.charAt(random.nextInt(chars.length()));
            if (!usedPrefixes.contains(candidate)) return candidate;
        }
        throw new RuntimeException("Không thể tạo prefix mới — đã dùng hết các tổ hợp");
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private String generateReceiptCode(String prefix) {
        String date = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String rand = String.format("%04d", new Random().nextInt(10000));
        String code = prefix + "-" + date + "-" + rand;
        while (warehouseReceiptRepository.existsByReceiptCode(code)) {
            rand = String.format("%04d", new Random().nextInt(10000));
            code = prefix + "-" + date + "-" + rand;
        }
        return code;
    }

    @Override
    public List<PaymentTransaction> getPaymentTransactions(Long orderId) {
        return paymentTransactionRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    private void log(Order order, String action, String actorName, String actorRole, String note) {
        orderLogRepository.save(OrderLog.builder()
                .order(order).action(action).actorName(actorName)
                .actorRole(actorRole).note(note).createdAt(System.currentTimeMillis()).build());
    }

    private void notifyWarehouseUsersOfWarehouse(Long warehouseId, String eventType,
                                                 String message, String payload) {
        if (warehouseId == null) return;
        var warehouseUsers = userRepository.findActiveWarehouseUsersByWarehouseId(warehouseId);
        if (warehouseUsers.isEmpty()) {
            log.warn("[NOTIFY] No warehouse users found for warehouseId={}", warehouseId);
        } else {
            for (var u : warehouseUsers) {
                String activeRole = u.getAllRoles().contains(Role.SUPER_WAREHOUSE)
                        ? "SUPER_WAREHOUSE" : "WAREHOUSE";
                notificationService.sendToUser(u, activeRole, eventType, message, payload);
            }
        }
    }

    private void updateSellerKpi(Order order) {
        try {
            if (order.getUser() == null) return;
            User seller = order.getUser();
            String periodKey = Instant.ofEpochMilli(order.getUpdatedAt())
                    .atZone(ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
            SellerKpi kpi = sellerKpiRepository.findBySellerIdAndPeriodKey(seller.getId(), periodKey)
                    .orElseGet(() -> SellerKpi.builder().seller(seller).periodKey(periodKey)
                            .totalOrders(0).totalRevenue(BigDecimal.ZERO).build());
            kpi.setTotalOrders(kpi.getTotalOrders() + 1);
            kpi.setTotalRevenue(kpi.getTotalRevenue().add(
                    order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO));
            kpi.setUpdatedAt(System.currentTimeMillis());
            sellerKpiRepository.save(kpi);
        } catch (Exception e) {
            log.warn("[KPI] Failed to update KPI for order {}: {}", order.getId(), e.getMessage());
        }
    }

    private Long resolveKpiUserId(User creator, Customer customer, Boolean includeKpi) {
        Set<Role> roles = creator.getAllRoles();
        if (roles.contains(Role.OWNER)) {
            if (Boolean.FALSE.equals(includeKpi)) return -1L;
            return resolveKpiForAutoRole(customer);
        }
        if (roles.contains(Role.SUPER_SELLER)) return resolveKpiForAutoRole(customer);
        if (roles.contains(Role.SELLER)) {
            if (customer == null) return 0L;
            if (customer.getCustomerType() == Customer.CustomerType.RETAIL) {
                if (customer.getAssignedSeller() != null) return customer.getAssignedSeller().getId();
                return 0L;
            }
            return creator.getId();
        }
        return -1L;
    }

    private Long resolveKpiForAutoRole(Customer customer) {
        if (customer == null) return 0L;
        if (customer.getCustomerType() != Customer.CustomerType.COMPANY) return 0L;
        User assignedSeller = customer.getCreatedBySeller();
        if (assignedSeller == null) return 0L;
        Set<Role> assignedRoles = assignedSeller.getAllRoles();
        boolean isPureSeller = assignedRoles.contains(Role.SELLER)
                && !assignedRoles.contains(Role.SUPER_SELLER)
                && !assignedRoles.contains(Role.ADMIN)
                && !assignedRoles.contains(Role.OWNER)
                && !assignedRoles.contains(Role.SUPERADMIN);
        return isPureSeller ? assignedSeller.getId() : 0L;
    }

    private BigDecimal calcSurchargeTotal(List<CreateOrderRequest.SurchargeItem> items) {
        if (items == null || items.isEmpty()) return BigDecimal.ZERO;
        return items.stream()
                .filter(i -> i.getAmount() != null && i.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(CreateOrderRequest.SurchargeItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String serializeSurchargeItems(List<CreateOrderRequest.SurchargeItem> items) {
        if (items == null || items.isEmpty()) return null;
        try {
            List<CreateOrderRequest.SurchargeItem> filtered = items.stream()
                    .filter(i -> i.getAmount() != null && i.getAmount().compareTo(BigDecimal.ZERO) > 0
                            && i.getName() != null && !i.getName().isBlank())
                    .toList();
            return filtered.isEmpty() ? null : objectMapper.writeValueAsString(filtered);
        } catch (Exception e) {
            log.warn("[SURCHARGE] serialize error: {}", e.getMessage());
            return null;
        }
    }
}