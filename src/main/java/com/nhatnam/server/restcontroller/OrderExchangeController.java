package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.service.OrderExchangeService;
import com.nhatnam.server.service.OrderExchangeService.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hoàn/Đổi sản phẩm — chỉ áp dụng với đơn COMPLETED.
 *
 * Bước 1a — Hoàn tiền:         POST /api/orders/{id}/refund
 * Bước 1b — Đổi SP:            POST /api/orders/{id}/exchange
 * Bước 2  — Xử lý hàng về:     POST /api/orders/{id}/restock
 * Bulk phiếu chi hoàn:         POST /api/orders/refund-disbursement/bulk
 * Tìm đơn cần hoàn tiền:       GET  /api/orders/refund-pending
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderExchangeController {

    private static final String ALLOWED =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN','SUPER_SELLER','SELLER')";

    private static final String ACCOUNTANT_ALLOWED =
            "hasAnyRole('OWNER','SUPERADMIN','SUPER_ACCOUNTANT','ACCOUNTANT')";

    private final OrderExchangeService exchangeService;
    private final OrderRepository orderRepository;

    /** Bước 1a — Hoàn tiền. */
    @PostMapping("/{orderId}/refund")
    @PreAuthorize(ALLOWED)
    public ApiResponse<RefundResult> refund(@PathVariable Long orderId,
                                             @RequestBody RefundRequest req,
                                             Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(exchangeService.processRefund(orderId, user.getId(), req));
    }

    /** Bước 1b — Đổi sản phẩm. */
    @PostMapping("/{orderId}/exchange")
    @PreAuthorize(ALLOWED)
    public ApiResponse<ExchangeResult> exchange(@PathVariable Long orderId,
                                                 @RequestBody ExchangeRequest req,
                                                 Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(exchangeService.processExchange(orderId, user.getId(), req));
    }

    /** Phiếu chi hoàn 1 đơn EXCHANGE (overpaid) — backward compat. */
    @PostMapping("/{orderId}/refund-disbursement")
    @PreAuthorize(ALLOWED)
    public ApiResponse<RefundDisbursementResult> createRefundDisbursement(
            @PathVariable Long orderId,
            @RequestBody RefundDisbursementRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(
                exchangeService.createRefundDisbursement(orderId, user.getId(), req));
    }

    /**
     * Tạo 1 phiếu chi cho nhiều đơn REFUND cùng lúc.
     * Chỉ kế toán và owner được gọi.
     */
    @PostMapping("/refund-disbursement/bulk")
    @PreAuthorize(ACCOUNTANT_ALLOWED)
    public ApiResponse<BulkRefundDisbursementResult> createBulkRefundDisbursement(
            @RequestBody BulkRefundDisbursementRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(exchangeService.createBulkRefundDisbursement(user.getId(), req));
    }

    /**
     * Tìm các đơn đang chờ hoàn tiền (pendingRefundAmount > 0, refundVoucherCode = null).
     * Hỗ trợ filter theo keyword (mã đơn, tên KH) và phân trang.
     */
    @GetMapping("/refund-pending")
    @PreAuthorize(ACCOUNTANT_ALLOWED)
    public ApiResponse<List<Map<String, Object>>> getRefundPendingOrders(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {

        // Lấy danh sách đơn có pendingRefundAmount > 0 và chưa có refundVoucherCode
        List<com.nhatnam.server.entity.Order> orders = orderRepository
                .findPendingRefundOrders(keyword, PageRequest.of(page, size));

        List<Map<String, Object>> result = orders.stream().map(o -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("orderCode", o.getOrderCode());
            m.put("customerName", o.getCustomerName());
            m.put("customerPhone", o.getCustomerPhone());
            m.put("pendingRefundAmount", o.getPendingRefundAmount());
            m.put("refundedAmount", o.getRefundedAmount());
            m.put("finalAmount", o.getFinalAmount());
            m.put("paidAmount", o.getPaidAmount());
            m.put("status", o.getStatus());
            m.put("createdAt", o.getCreatedAt());
            m.put("returnExchangeNote", o.getReturnExchangeNote());
            return m;
        }).collect(Collectors.toList());

        return ApiResponse.ok(result);
    }

    /** Bước 2 — Xử lý hàng nhận về (DESTROY / RESTOCK). */
    @PostMapping("/{orderId}/restock")
    @PreAuthorize(ALLOWED)
    public ApiResponse<Void> restock(@PathVariable Long orderId,
                                      @RequestBody RestockRequest req,
                                      Authentication auth) {
        User user = (User) auth.getPrincipal();
        req.setSourceOrderId(orderId);
        exchangeService.processRestock(user.getId(), req);
        return ApiResponse.ok(null);
    }
}
