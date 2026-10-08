// src/main/java/com/nhatnam/server/service/admin/OrderAdminService.java
package com.nhatnam.server.service.admin;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.order.OrderDto;
import com.nhatnam.server.dto.order.OrderItemDto;
import com.nhatnam.server.dto.order.OrderLogDto;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.entity.OrderLog;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.repository.OrderLogRepository;
import com.nhatnam.server.repository.OrderRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderAdminService {

    private final OrderRepository    orderRepository;
    private final OrderLogRepository orderLogRepository;   // ← thêm

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> list(String q,
                                       OrderStatus status,
                                       String paymentStatus,
                                       Long userId,
                                       Long customerId,
                                       Long productId,
                                       Long fromDate,
                                       Long toDate,
                                       Pageable pageable) {
        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("orderCode")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("customerName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("customerPhone"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("companyName"), "")), like)
                ));
            }
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (paymentStatus != null && !paymentStatus.isBlank()) {
                ps.add(cb.equal(root.get("paymentStatus"),
                        com.nhatnam.server.enumtype.PaymentStatus.valueOf(paymentStatus)));
            }
            if (userId != null)     ps.add(cb.equal(root.get("user").get("id"), userId));
            if (customerId != null) ps.add(cb.equal(root.get("customer").get("id"), customerId));
            if (productId != null) {
                var orderItemsJoin = root.join("orderItems", jakarta.persistence.criteria.JoinType.INNER);
                ps.add(cb.equal(orderItemsJoin.get("productId"), productId));
                query.distinct(true);
            }
            if (fromDate != null)   ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate));
            if (toDate != null)     ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), toDate));
            return cb.and(ps.toArray(new Predicate[0]));
        };

        Page<Order> page = orderRepository.findAll(spec, pageable);
        List<OrderDto> content = page.getContent().stream().map(this::toDto).toList();
        return PageResponse.from(page, content);
    }

    @Transactional(readOnly = true)
    public OrderDto getById(Long id) {
        Order o = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại: " + id));
        return toDtoWithLogs(o); // ← dùng bản có logs
    }

    // ── Sửa: thêm actorName param ───────────────────────────────────────────
    @Transactional
    public OrderDto cancel(Long id, String reason, String actorName) {
        Order o = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại: " + id));

        if (o.getStatus() == OrderStatus.CANCELLED)
            throw new BusinessException("Đơn hàng đã hủy trước đó");
        if (o.getStatus() == OrderStatus.COMPLETED)
            throw new BusinessException("Không thể hủy đơn đã hoàn thành");

        o.setStatus(OrderStatus.CANCELLED);
        o.setUpdatedAt(System.currentTimeMillis());

        // Ghi lý do vào notes
        String prefix    = "[HỦY] ";
        String existing  = o.getNotes() == null ? "" : o.getNotes();
        String reasonPart = reason == null || reason.isBlank() ? "Không có lý do" : reason;
        o.setNotes(prefix + reasonPart + (existing.isBlank() ? "" : "\n---\n" + existing));

        Order saved = orderRepository.save(o);

        // ← Ghi log CANCELLED (chỉ admin dùng)
        orderLogRepository.save(OrderLog.builder()
                .order(saved)
                .action("CANCELLED")
                .actorName(actorName)
                .actorRole("ADMIN")
                .note(reasonPart)
                .createdAt(System.currentTimeMillis())
                .build());

        return toDtoWithLogs(saved);
    }

    // ── toDto không có logs (dùng cho list — tránh N+1) ─────────────────────
    private OrderDto toDto(Order o) {
        return buildDto(o, List.of());
    }

    // ── toDto có logs (dùng cho getById và cancel) ───────────────────────────
    private OrderDto toDtoWithLogs(Order o) {
        List<OrderLogDto> logs = orderLogRepository
                .findByOrderIdOrderByCreatedAtAsc(o.getId())
                .stream()
                .map(l -> OrderLogDto.builder()
                        .action(l.getAction())
                        .actorName(l.getActorName())
                        .actorRole(l.getActorRole())
                        .note(l.getNote())
                        .createdAt(l.getCreatedAt())
                        .build())
                .toList();
        return buildDto(o, logs);
    }

    private OrderDto buildDto(Order o, List<OrderLogDto> logs) {
        List<OrderItemDto> items = Optional.ofNullable(o.getOrderItems()).orElse(List.of())
                .stream().map(this::toItemDto).toList();

        return OrderDto.builder()
                .id(o.getId())
                .orderCode(o.getOrderCode())
                .userId(o.getUser() == null ? null : o.getUser().getId())
                .userName(o.getUser() == null ? null : o.getUser().getUsername())
                .fullName(o.getUser() == null ? null : o.getUser().getFullName())
                .customerId(o.getCustomer() == null ? null : o.getCustomer().getId())
                .customerName(o.getCustomerName())
                .customerPhone(o.getCustomerPhone())
                .customerEmail(o.getCustomerEmail())
                .customerType(o.getCustomerType())
                .companyName(o.getCompanyName())
                .shortName(o.getShortName())
                .taxCode(o.getTaxCode())
                .contactName(o.getContactName())
                .companyPhone(o.getCompanyPhone())
                .companyAddress(o.getCompanyAddress())
                .shippingAddress(o.getShippingAddress())
                .deliveryAddress(o.getDeliveryAddress())
                .orderedByName(o.getOrderedByName())
                .subtotal(o.getSubtotal())
                .surcharge(o.getSurcharge())
                .discountAmount(o.getDiscountAmount())
                .discountRate(o.getDiscountRate())
                .vatRate(o.getVatRate() == null ? null : o.getVatRate().name())
                .vatAmount(o.getVatAmount())
                .totalAmount(o.getTotalAmount())
                .finalAmount(o.getFinalAmount())
                .status(o.getStatus() == null ? null : o.getStatus().name())
                .paymentStatus(o.getPaymentStatus() == null ? null : o.getPaymentStatus().name())
                .paymentMethod(o.getPaymentMethod())
                .warehouseName(o.getWarehouseName())
                .warehouseId(o.getWarehouseId())
                .paidAmount(o.getPaidAmount() != null ? o.getPaidAmount() : BigDecimal.ZERO)
                .type(o.getType())
                .notes(o.getNotes())
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .items(items)
                // ── Hoàn/Đổi SP ─────────────────────────────────────────────
                .linkType(o.getLinkType())
                .sourceOrderId(o.getSourceOrderId())
                .sourceOrderCode(o.getSourceOrderCode())
                .returnExchangeNote(o.getReturnExchangeNote())
                .pendingRefundAmount(o.getPendingRefundAmount() != null ? o.getPendingRefundAmount() : BigDecimal.ZERO)
                .refundedAmount(o.getRefundedAmount() != null ? o.getRefundedAmount() : BigDecimal.ZERO)
                .refundVoucherCode(o.getRefundVoucherCode())
                // ── Tài xế: dùng deliveryInfo thay cho drivers ──
                .deliveryInfo(parseDeliveryInfo(o.getDeliveryInfoJson()))
                .logs(logs)
                .build();
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

    private OrderItemDto toItemDto(OrderItem it) {
        return OrderItemDto.builder()
                .id(it.getId())
                .productId(it.getProductId())
                .productName(it.getProductName())
                .productImageUrl(it.getProductImageUrl())
                .unit(it.getUnit())
                .quantity(it.getQuantity())
                .basePrice(it.getBasePrice())
                .unitPrice(it.getUnitPrice())
                .subtotal(it.getSubtotal())
                .vatAmount(it.getVatAmount())
                .vatRate(it.getVatRate())
                .vatMode(it.getVatMode())
                .discountPercent(it.getDiscountPercent())
                .tierName(it.getTierName())
                .notes(it.getNotes())
                .build();
    }
}