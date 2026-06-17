package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.CreateOrderRequest;
import com.nhatnam.server.dto.response.OrderDetailResponse;
import com.nhatnam.server.dto.response.OrderListResponse;
import com.nhatnam.server.dto.response.OrderResponse;
import com.nhatnam.server.entity.PaymentTransaction;
import com.nhatnam.server.enumtype.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

public interface OrderService {
    OrderResponse completeOrderBySeller(Long orderId);
    OrderResponse updatePaymentMethodBySeller(Long orderId, String paymentMethod);

    OrderResponse confirmWaiveRemainder(Long orderId, BigDecimal actualPaid,
                                        String actorName, Long actorUserId,
                                        String paymentMethod, String bankName,
                                        String transactionRef);

    /** Ghi nhận 1 lần thanh toán (partial hoặc full) với thông tin giao dịch */
    OrderResponse recordPartialPayment(Long orderId, BigDecimal paidAmount,
                                       int debtDays, String actorName,
                                       String paymentMethod, String bankName,
                                       String transactionRef);
    OrderResponse recordPartialPayment(Long orderId, BigDecimal paidAmount,
                                       int debtDays, String actorName,
                                       String paymentMethod, String bankName,
                                       String transactionRef, Long actorUserId);

    /** Backward-compat overload (không có bank info) */
    default OrderResponse recordPartialPayment(Long orderId, BigDecimal paidAmount,
                                               int debtDays, String actorName) {
        return recordPartialPayment(orderId, paidAmount, debtDays, actorName,
                                    null, null, null);
    }

    OrderResponse createOrder(CreateOrderRequest request, Long userId);
    OrderResponse markAsPreparing(Long orderId, Long userId);
    OrderResponse markAsCompleted(Long orderId, String actorName);
    OrderResponse markAsCompleted(Long orderId, String actorName, Long actorUserId);
    OrderResponse markAsPendingPayment(Long orderId, String actorName);
    OrderResponse markAsPendingPayment(Long orderId, String actorName, Long actorUserId);
    OrderResponse updatePaymentMethod(Long orderId, String paymentMethod, String actorName);
    OrderResponse markAsDelivering(Long orderId, String actorName);
    OrderResponse markAsDelivering(Long orderId, String actorName, Long actorUserId);

    OrderResponse getOrderById(Long id);
    OrderResponse getOrderByCode(String orderCode);

    List<OrderResponse> getMyOrders(Long userId);
    List<OrderResponse> getOrdersByStatus(OrderStatus status);

    OrderResponse updateOrderStatus(Long orderId, OrderStatus newStatus);
    OrderResponse cancelOrder(Long orderId, Long userId);

    /** Hủy đơn với lý do + hoàn kho + thông báo theo role */
    OrderResponse cancelOrder(Long orderId, Long actorUserId, String actorName,
                              String actorRole, String reason);


    Page<OrderListResponse> getOrders(String search, OrderStatus status, Pageable pageable);

    OrderResponse updateOrderItems(Long orderId, Long userId,
                                   com.nhatnam.server.dto.request.UpdateOrderItemsRequest request);
    OrderDetailResponse getOrderDetail(Long orderId);

    /** Lấy danh sách các lần thanh toán của 1 đơn */
    List<PaymentTransaction> getPaymentTransactions(Long orderId);
}
