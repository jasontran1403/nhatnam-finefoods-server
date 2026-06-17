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
import com.nhatnam.server.service.CartHoldService;
import com.nhatnam.server.service.NotificationService;
import com.nhatnam.server.service.OrderService;
import com.nhatnam.server.entity.SellerKpi;
import com.nhatnam.server.service.FifoDeductService;
import com.nhatnam.server.repository.SellerKpiRepository;
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
    private final OrderCodePrefixRepository     orderCodePrefixRepository;

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

        if (order.getStatus() != OrderStatus.DELIVERING && order.getStatus() != OrderStatus.PENDING_PAYMENT)
            throw new RuntimeException("Chỉ có thể ghi nhận thanh toán ở trạng thái 'Đang giao' hoặc 'Chờ thanh toán'");

        BigDecimal currentPaid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal newPaid     = currentPaid.add(paidAmount);
        BigDecimal finalAmount = order.getFinalAmount();

        if (newPaid.compareTo(finalAmount) > 0)
            throw new RuntimeException(String.format(
                    "Tổng số tiền thu (%s) vượt quá giá trị đơn hàng (%s)",
                    newPaid.toPlainString(), finalAmount.toPlainString()));

        long now = System.currentTimeMillis();
        order.setPaidAmount(newPaid);
        order.setUpdatedAt(now);

        boolean isFullyPaid = newPaid.compareTo(finalAmount) >= 0;
        if (isFullyPaid) {
            order.setPaymentStatus(PaymentStatus.PAID);
            order.setStatus(OrderStatus.COMPLETED);
        } else {
            order.setPaymentStatus(PaymentStatus.PARTIAL);
            order.setStatus(OrderStatus.PENDING_PAYMENT);
            order.setPendingPaymentAt(now);
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
    public OrderResponse updatePaymentMethod(Long orderId, String paymentMethod, String actorName) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
        assertNotCancelled(order);
        order.setPaymentMethod(paymentMethod);
        order.setUpdatedAt(System.currentTimeMillis());
        Order saved = orderRepository.saveAndFlush(order);
        log(saved, "PAYMENT_METHOD_UPDATED", actorName, "ACCOUNTANT", "Đổi sang: " + paymentMethod);
        return mapToResponse(saved);
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

            log.info("[UPDATE_ORDER] orderId={} — customer changed to id={} name={}, KPI: {} -> {}",
                    orderId, newCustomer.getId(), order.getCustomerName(), currentKpiUserId, newKpiUserId);
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

        Map<Long, OrderStockDeduction> existingDeductionByIngId = existingDeductions
                .stream()
                .collect(Collectors.toMap(
                        d -> d.getIngredientStock().getIngredientId(),
                        d -> d,
                        (a, b) -> a
                ));

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
            log.warn("[UPDATE_ORDER] orderId={} — Không có OrderStockDeduction records. Deduct toàn bộ newUsage={} như tạo mới.", orderId, newUsageMap);
            deltaMap.putAll(newUsageMap);
        }

        Map<Long, BigDecimal> ingredientUnitCostMap = new LinkedHashMap<>();
        List<WarehouseReceiptItem> receiptItems = new ArrayList<>();

        for (Map.Entry<Long, BigDecimal> entry : deltaMap.entrySet()) {
            Long ingId = entry.getKey();
            BigDecimal delta = entry.getValue();

            Ingredient ing = ingredientRepository.findById(ingId)
                    .orElseThrow(() -> new RuntimeException("Ingredient not found: " + ingId));
            IngredientStock stock = ingredientStockRepository
                    .findByIngredientIdAndWarehouseId(ingId, warehouseId)
                    .orElseThrow(() -> new RuntimeException(String.format(
                            "Nguyên liệu '%s' chưa được cấu hình tồn kho cho kho '%s'",
                            ing.getName(), warehouse.getName())));

            BigDecimal before = stock.getStockQuantity();

            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                if (stock.getStockQuantity().compareTo(delta) < 0)
                    throw new BusinessException(String.format(
                            "Không đủ tồn kho '%s' tại kho '%s' (còn: %s, cần thêm: %s %s)",
                            ing.getName(), warehouse.getName(),
                            stock.getStockQuantity(), delta, ing.getUnit()));

                BigDecimal after = before.subtract(delta).setScale(3, RoundingMode.HALF_UP);
                stock.setStockQuantity(after);
                stock.setUpdatedAt(now);
                ingredientStockRepository.save(stock);

                BigDecimal costDeducted = fifoDeductService.deduct(order.getId(), warehouse, ing, delta, now);

                Long keepId = existingDeductionByIngId.containsKey(ingId)
                        ? existingDeductionByIngId.get(ingId).getId()
                        : null;
                orderStockDeductionRepository.findByOrderId(orderId)
                        .stream()
                        .filter(d -> d.getIngredientStock().getIngredientId().equals(ingId))
                        .filter(d -> keepId == null || !d.getId().equals(keepId))
                        .forEach(d -> {
                            log.info("[UPDATE_ORDER] orderId={} — deleted fifo-created deduction id={} ingId={} qty={}",
                                    orderId, d.getId(), ingId, d.getQuantity());
                            orderStockDeductionRepository.delete(d);
                        });

                BigDecimal unitCost = BigDecimal.ZERO;
                if (costDeducted.compareTo(BigDecimal.ZERO) > 0)
                    unitCost = costDeducted.divide(delta, 6, RoundingMode.HALF_UP);
                ingredientUnitCostMap.put(ingId, unitCost);

                log.info("[UPDATE_ORDER] orderId={} ingId={} — deducted delta={}, costDeducted={}, stock: {} → {}",
                        orderId, ingId, delta, costDeducted, before, after);

                receiptItems.add(WarehouseReceiptItem.builder()
                        .ingredientId(ing.getId())
                        .ingredientNameSnapshot(ing.getName())
                        .ingredientUnitSnapshot(ing.getUnit())
                        .ingredientImageUrlSnapshot(ing.getImageUrl())
                        .quantity(delta.negate())
                        .quantityBefore(before).quantityAfter(before.subtract(delta).setScale(3, RoundingMode.HALF_UP))
                        .difference(delta.negate()).build());

            } else {
                BigDecimal restore = delta.abs();
                BigDecimal after = before.add(restore).setScale(3, RoundingMode.HALF_UP);
                stock.setStockQuantity(after);
                stock.setUpdatedAt(now);
                ingredientStockRepository.save(stock);

                restorePartialStock(order.getId(), ingId, restore, now);
                ingredientUnitCostMap.put(ingId, BigDecimal.ZERO);

                receiptItems.add(WarehouseReceiptItem.builder()
                        .ingredientId(ing.getId())
                        .ingredientNameSnapshot(ing.getName())
                        .ingredientUnitSnapshot(ing.getUnit())
                        .ingredientImageUrlSnapshot(ing.getImageUrl())
                        .quantity(restore)
                        .quantityBefore(before).quantityAfter(after)
                        .difference(after.subtract(before)).build());
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

        for (Long ingId : existingDeductionByIngId.keySet()) {
            if (!newUsageMap.containsKey(ingId)) {
                orderStockDeductionRepository.delete(existingDeductionByIngId.get(ingId));
                log.info("[UPDATE_ORDER] orderId={} — removed deduction record ingId={}", orderId, ingId);
            }
        }

        for (Map.Entry<Long, BigDecimal> entry : newUsageMap.entrySet()) {
            Long ingId = entry.getKey();
            BigDecimal totalQty = entry.getValue();
            if (totalQty.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal unitCost = ingredientUnitCostMap.getOrDefault(ingId, BigDecimal.ZERO);

            if (existingDeductionByIngId.containsKey(ingId)) {
                OrderStockDeduction existing = orderStockDeductionRepository
                        .findById(existingDeductionByIngId.get(ingId).getId())
                        .orElse(null);

                if (existing != null) {
                    existing.setQuantity(totalQty);
                    if (unitCost.compareTo(BigDecimal.ZERO) > 0) {
                        existing.setCostPrice(unitCost);
                    }
                    orderStockDeductionRepository.save(existing);
                } else {
                    IngredientStock st = ingredientStockRepository
                            .findByIngredientIdAndWarehouseId(ingId, warehouseId)
                            .orElseThrow(() -> new RuntimeException("Stock not found: " + ingId));
                    orderStockDeductionRepository.save(OrderStockDeduction.builder()
                            .orderId(orderId).ingredientStock(st)
                            .quantity(totalQty).costPrice(unitCost).createdAt(now).build());
                }
            } else {
                IngredientStock st = ingredientStockRepository
                        .findByIngredientIdAndWarehouseId(ingId, warehouseId)
                        .orElseThrow(() -> new RuntimeException("Stock not found: " + ingId));
                orderStockDeductionRepository.save(OrderStockDeduction.builder()
                        .orderId(orderId).ingredientStock(st)
                        .quantity(totalQty).costPrice(unitCost).createdAt(now).build());
                log.info("[UPDATE_ORDER] orderId={} — inserted deduction ingId={} qty={} unitCost={}",
                        orderId, ingId, totalQty, unitCost);
            }
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

        int discountRate = order.getDiscountRate() != null ? order.getDiscountRate() : 0;

        BigDecimal itemDiscountTotal = BigDecimal.ZERO;
        for (OrderItem oi : orderItems) {
            int pct = oi.getDiscountPercent() != null ? oi.getDiscountPercent() : 0;
            if (pct == 0) continue;
            BigDecimal lineGross = oi.getUnitPrice().multiply(oi.getQuantity());
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
        BigDecimal exclusiveVat  = vatResult[0];
        BigDecimal totalVat      = vatResult[1];

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
        order.setVatAmount(totalVat);
        order.setTotalAmount(afterDiscount);
        order.setSurcharge(surcharge);
        order.setFinalAmount(afterDiscount.add(exclusiveVat).add(surcharge));
        order.setSurchargeDetail(surchargeDetail);

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
                .collect(Collectors.toList());

        log.info("[RESTORE_PARTIAL] orderId={} ingId={} restoreQty={} — found {} deduction records: {}",
                orderId, ingredientId, restoreQty, deductions.size(),
                deductions.stream().map(d -> "id=" + d.getId() + " qty=" + d.getQuantity())
                        .collect(Collectors.joining(", ")));

        BigDecimal remaining = restoreQty;
        for (OrderStockDeduction d : deductions) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal canRestore = remaining.min(d.getQuantity());
            remaining = remaining.subtract(canRestore);

            if (d.getIngredientExpiry() != null) {
                ingredientExpiryRepository.findById(d.getIngredientExpiry().getId())
                        .ifPresent(lot -> {
                            lot.setQuantity(lot.getQuantity().add(canRestore));
                            lot.setUpdatedAt(now);
                            ingredientExpiryRepository.save(lot);
                        });
            }

            IngredientStock stock = d.getIngredientStock();
            if (d.getCostPrice() != null && d.getCostPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal costToRestore = d.getCostPrice().multiply(canRestore).setScale(2, RoundingMode.HALF_UP);
                BigDecimal newCost = (stock.getTotalCostValue() != null
                        ? stock.getTotalCostValue() : BigDecimal.ZERO).add(costToRestore);
                stock.setTotalCostValue(newCost);
                stock.setUpdatedAt(now);
                ingredientStockRepository.save(stock);
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
                .shortName(shortName).taxCode(taxCode).contactName(contactName)
                .shippingAddress(deliveryAddress).deliveryAddress(deliveryAddress)
                .orderedByName(orderedByName).createdAt(now).updatedAt(now)
                .deliveryDatetime(request.getDeliveryDatetime() != null
                        ? request.getDeliveryDatetime() : calcDeliveryDatetimeFallback(now))
                .showPrices(showPrices).hideAllPrices(hideAllPrices)
                .visibleToSellerId(visibleToSellerId).orderItems(new ArrayList<>()).build();

        Long kpiUserId = resolveKpiUserId(user, finalCustomer, request.getIncludeKpi());
        order.setKpiUserId(kpiUserId);
        log.info("[KPI] order={} creator={} role={} kpiUserId={}", orderCode, userId, user.getRole(), kpiUserId);

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

        for (Map.Entry<Long, BigDecimal> entry : ingredientUsageMap.entrySet()) {
            Ingredient ing = ingredientRepository.findById(entry.getKey())
                    .orElseThrow(() -> new RuntimeException("Ingredient not found: " + entry.getKey()));
            IngredientStock stock = ingredientStockRepository
                    .findByIngredientIdAndWarehouseId(ing.getId(), warehouseId)
                    .orElseThrow(() -> new RuntimeException(String.format(
                            "Nguyên liệu '%s' chưa được cấu hình tồn kho cho kho '%s'",
                            ing.getName(), warehouse.getName())));
            BigDecimal needed = entry.getValue();
            if (stock.getStockQuantity().compareTo(needed) < 0)
                throw new BusinessException(String.format(
                        "Không đủ tồn kho '%s' tại kho '%s' (còn: %s, cần: %s %s)",
                        ing.getName(), warehouse.getName(),
                        stock.getStockQuantity(), needed, ing.getUnit()));

            BigDecimal before = stock.getStockQuantity();
            BigDecimal after  = before.subtract(needed).setScale(3, RoundingMode.HALF_UP);
            stock.setStockQuantity(after);
            stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);

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

        BigDecimal itemDiscountTotal = BigDecimal.ZERO;
        for (OrderItem oi : orderItems) {
            int pct = oi.getDiscountPercent() != null ? oi.getDiscountPercent() : 0;
            if (pct == 0) continue;
            BigDecimal lineGross = oi.getUnitPrice().multiply(oi.getQuantity());
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
        BigDecimal exclusiveVat  = vatResult[0];
        BigDecimal totalVat      = vatResult[1];
        BigDecimal surchargeVal = request.getSurchargeItems() != null && !request.getSurchargeItems().isEmpty()
                ? calcSurchargeTotal(request.getSurchargeItems())
                : (request.getSurcharge() != null ? request.getSurcharge() : BigDecimal.ZERO);
        String surchargeDetail = serializeSurchargeItems(request.getSurchargeItems());
        savedOrder.setSurchargeDetail(surchargeDetail);

        savedOrder.setSubtotal(subtotal);
        savedOrder.setDiscountAmount(discountAmount);
        savedOrder.setVatAmount(totalVat);
        savedOrder.setTotalAmount(afterDiscount);
        savedOrder.setFinalAmount(afterDiscount.add(exclusiveVat).add(surchargeVal));
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

    private BigDecimal[] calcVatForItems(List<OrderItem> orderItems,
                                         BigDecimal subtotal, BigDecimal afterDiscount) {
        BigDecimal exclusiveVat = BigDecimal.ZERO;
        BigDecimal totalVat     = BigDecimal.ZERO;

        for (OrderItem item : orderItems) {
            int rate = item.getVatRate() == null ? 0 : item.getVatRate();
            if (rate == 0) continue;

            BigDecimal proportion = subtotal.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : item.getSubtotal().divide(subtotal, 10, RoundingMode.HALF_UP);
            BigDecimal itemAfterDisc = afterDiscount.multiply(proportion);

            BigDecimal itemVat;
            if ("EXCLUSIVE".equals(item.getVatMode())) {
                itemVat = itemAfterDisc.multiply(BigDecimal.valueOf(rate))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                exclusiveVat = exclusiveVat.add(itemVat);
            } else {
                itemVat = itemAfterDisc.multiply(BigDecimal.valueOf(rate))
                        .divide(BigDecimal.valueOf(100 + rate), 2, RoundingMode.HALF_UP);
            }
            item.setVatAmount(itemVat);
            totalVat = totalVat.add(itemVat);
        }
        return new BigDecimal[]{ exclusiveVat, totalVat };
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
                .vatMode(product.getVatMode() != null ? product.getVatMode().name() : "INCLUSIVE")
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
                        .notes(item.getNotes())
                        // snapshot extras (nếu OrderItemResponse có các field này)
                        .categorySnapshot(item.getCategorySnapshot())
                        .skuSnapshot(item.getSkuSnapshot())
                        .packagingDescriptionSnapshot(item.getPackagingDescriptionSnapshot())
                        .maxDiscountRateSnapshot(item.getMaxDiscountRateSnapshot())
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
                .contactName(order.getContactName()).deliveryAddress(order.getDeliveryAddress())
                .companyPhone(order.getCompanyPhone()).companyAddress(order.getCompanyAddress())
                .createdAt(order.getCreatedAt())
                // ── Tài xế: dùng deliveryInfo thay cho drivers ──
                .deliveryInfo(parseDeliveryInfo(order.getDeliveryInfoJson()))
                .deliveryDatetime(order.getDeliveryDatetime() != null
                        ? order.getDeliveryDatetime() : calcDeliveryDatetimeFallback(order.getCreatedAt()))
                .showPrices(order.getShowPrices() != null ? order.getShowPrices() : Boolean.TRUE)
                .hideAllPrices(order.getHideAllPrices() != null ? order.getHideAllPrices() : Boolean.FALSE)
                .surcharge(order.getSurcharge())
                .paidAmount(order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO)
                .estimatedDelivery(calcEstimatedDelivery(order.getDeliveryDatetime(), order.getCreatedAt()))
                .paymentDeadline(calcPaymentDeadline(
                        order.getPendingPaymentAt(), order.getCreatedAt(), order.getDebtDays()))
                .vatBreakdown(calcVatBreakdown(order.getOrderItems()))
                .surchargeDetail(order.getSurchargeDetail())
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

    private Long calcDeliveryDatetimeFallback(Long createdAt) {
        if (createdAt == null) return null;
        LocalDateTime ordered = LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAt), TZ);
        LocalDateTime rounded = ordered.getMinute() == 0 && ordered.getSecond() == 0
                ? ordered.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                : ordered.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
        return rounded.plusHours(1).atZone(TZ).toInstant().toEpochMilli();
    }

    private String calcEstimatedDelivery(Long deliveryDatetime, Long createdAt) {
        Long ts = deliveryDatetime != null ? deliveryDatetime : calcDeliveryDatetimeFallback(createdAt);
        if (ts == null) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), TZ)
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
        for (OrderStockDeduction d : deductions) {
            IngredientStock stock = d.getIngredientStock();
            stock.setStockQuantity(stock.getStockQuantity().add(d.getQuantity()));
            if (d.getCostPrice() != null && d.getCostPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal costToRestore = d.getCostPrice().multiply(d.getQuantity())
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal newCost = (stock.getTotalCostValue() != null
                        ? stock.getTotalCostValue() : BigDecimal.ZERO).add(costToRestore);
                stock.setTotalCostValue(newCost);
            }
            stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);

            if (d.getIngredientExpiry() != null) {
                ingredientExpiryRepository.findById(d.getIngredientExpiry().getId())
                        .ifPresentOrElse(lot -> {
                            lot.setQuantity(lot.getQuantity().add(d.getQuantity()));
                            lot.setUpdatedAt(now);
                            ingredientExpiryRepository.save(lot);
                        }, () -> {
                            if (d.getExpiryDate() != null || d.getCostPrice() != null)
                                ingredientExpiryRepository.save(IngredientExpiry.builder()
                                        .warehouse(stock.getWarehouse()).ingredientId(stock.getIngredientId())
                                        .expiryDate(d.getExpiryDate()).costPrice(d.getCostPrice())
                                        .quantity(d.getQuantity()).createdAt(now).updatedAt(now).build());
                        });
            }
        }
        log.info("[CANCEL] Đã hoàn kho {} deduction records cho orderId={}", deductions.size(), orderId);
    }

    private void notifyCancellation(Order order, Long actorUserId, String actorRole,
                                    String message, String payload) {
        String role = actorRole != null ? actorRole.toUpperCase() : "";
        User creator = order.getUser();
        Role creatorRole = creator != null ? creator.getRole() : null;
        switch (role) {
            case "SELLER", "SUPER_SELLER" ->
                    notifyRoles(List.of(Role.SUPER_SELLER, Role.ACCOUNTANT, Role.SUPER_ACCOUNTANT,
                            Role.ADMIN, Role.OWNER), actorUserId, "ORDER_CANCELLED", message, payload);
            case "ACCOUNTANT" ->
                    notifyRoles(List.of(Role.SUPER_ACCOUNTANT, Role.ADMIN, Role.OWNER),
                            actorUserId, "ORDER_CANCELLED", message, payload);
            case "SUPER_ACCOUNTANT" ->
                    notifyRoles(List.of(Role.ADMIN, Role.OWNER),
                            actorUserId, "ORDER_CANCELLED", message, payload);
            case "WAREHOUSE", "SUPER_WAREHOUSE" -> {
                notifyRoles(List.of(Role.SUPER_WAREHOUSE, Role.SUPER_SELLER, Role.ACCOUNTANT,
                                Role.SUPER_ACCOUNTANT, Role.ADMIN, Role.OWNER),
                        actorUserId, "ORDER_CANCELLED", message, payload);
                if (creator != null && creatorRole == Role.SELLER
                        && (actorUserId == null || !actorUserId.equals(creator.getId())))
                    notificationService.sendToUser(creator, "SELLER", "ORDER_CANCELLED", message, payload);
            }
            default -> notifyRoles(List.of(Role.ADMIN, Role.OWNER),
                    actorUserId, "ORDER_CANCELLED", message, payload);
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

    private String generateOrderCode() {
        int currentYear = LocalDate.now().getYear();
        OrderCodePrefix activePrefix = orderCodePrefixRepository.findByIsActiveTrue()
                .orElseGet(() -> createNewPrefix(currentYear));
        if (activePrefix.getYear() != currentYear) {
            activePrefix.setIsActive(false);
            orderCodePrefixRepository.save(activePrefix);
            activePrefix = createNewPrefix(currentYear);
        }
        long start = LocalDate.of(currentYear, 1, 1)
                .atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000;
        long end = LocalDate.of(currentYear + 1, 1, 1)
                .atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000;
        long count = orderRepository.countByCreatedAtBetween(start, end) + 1;
        return String.format("%s-%05d", activePrefix.getPrefix(), count);
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