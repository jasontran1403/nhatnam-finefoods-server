package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.InvoiceDTO;
import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.order.CancelOrderRequest;
import com.nhatnam.server.dto.order.OrderDto;
import com.nhatnam.server.dto.response.OrderResponse;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderLog;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.OrderLogRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.service.OrderService;
import com.nhatnam.server.service.admin.OrderAdminService;
import com.nhatnam.server.utils.InvoicePdf;
import com.nhatnam.server.utils.OrderExcelExporter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@Log4j2
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderAdminService orderAdminService;
    private final OrderRepository orderRepository;
    private final OrderLogRepository orderLogRepository;
    private final OrderExcelExporter exporter;
    private final InvoicePdf invoicePdf;
    private final OrderService orderService;
    private final CustomerRepository customerRepository;

    @GetMapping("/customers/search")
    public ApiResponse<?> searchCustomers(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size) {

        Pageable pageable = org.springframework.data.domain.PageRequest.of(
                page, size,
                org.springframework.data.domain.Sort.by("name").ascending()
        );

        var result = customerRepository.search(q.isBlank() ? null : q, null, null, pageable);

        var items = result.getContent().stream().map(c -> {
            var map = new java.util.LinkedHashMap<String, Object>();
            map.put("id", c.getId());
            map.put("name", c.getName());
            map.put("companyName", c.getCompanyName());
            map.put("phone", c.getPhone());
            map.put("customerType", c.getCustomerType() != null ? c.getCustomerType().name() : "RETAIL");
            map.put("customerCode", c.getCustomerCode());
            return map;
        }).toList();

        return ApiResponse.ok(items);
    }


    @GetMapping("/orders/{orderId}/invoice")
    public ResponseEntity<?> generateInvoice(@PathVariable Long orderId) {
        try {
            OrderResponse order = orderService.getOrderById(orderId);

            // ── VAT breakdown tách INCLUSIVE / EXCLUSIVE ──────────────────────────
            Map<Integer, BigDecimal> vatBreakdownInclusive = new LinkedHashMap<>();
            Map<Integer, BigDecimal> vatBreakdownExclusive = new LinkedHashMap<>();

            for (var item : order.getItems()) {
                Integer    rate   = item.getVatRate();
                BigDecimal amount = item.getVatAmount();
                if (rate == null || rate == 0 || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) continue;

                boolean isInclusive = "INCLUSIVE".equalsIgnoreCase(item.getVatMode());
                if (isInclusive) {
                    vatBreakdownInclusive.merge(rate, amount, BigDecimal::add);
                } else {
                    vatBreakdownExclusive.merge(rate, amount, BigDecimal::add);
                }
            }

            InvoiceDTO invoiceDTO = InvoiceDTO.builder()
                    .orderId(order.getId())
                    .orderCode(order.getOrderCode())
                    .customerName(order.getCustomerName())
                    .customerPhone(order.getCustomerPhone())
                    .customerEmail(order.getCustomerEmail())
                    .shippingAddress(order.getShippingAddress())
                    .notes(order.getNotes())
                    .totalAmount(order.getTotalAmount())
                    .discountAmount(order.getDiscountAmount())
                    .finalAmount(order.getFinalAmount())
                    .vatAmount(order.getVatAmount())
                    .surcharge(order.getSurcharge())
                    .surchargeDetail(order.getSurchargeDetail()) // thêm dòng này
                    .status(order.getStatus())
                    .paymentStatus(order.getPaymentStatus())
                    .paymentMethod(order.getPaymentMethod())
                    .createdAt(order.getCreatedAt())
                    .receiverName(order.getReceiverName())
                    .estimatedDelivery(order.getEstimatedDelivery())
                    .paymentDeadline(order.getPaymentDeadline())
                    .deliveryDatetime(order.getDeliveryDatetime())
                    .customerType(order.getCustomerType())
                    .companyName(order.getCompanyName())
                    .orderedByName(order.getOrderedByName())
                    .taxCode(order.getTaxCode())
                    .companyPhone(order.getCompanyPhone())
                    .companyAddress(order.getCompanyAddress())
                    .contactName(order.getContactName())
                    .deliveryAddress(order.getDeliveryAddress())
                    .hideAllPrices(order.getHideAllPrices())
                    .vatBreakdownInclusive(vatBreakdownInclusive)
                    .vatBreakdownExclusive(vatBreakdownExclusive)
                    .items(order.getItems().stream()
                            .map(item -> InvoiceDTO.Item.builder()
                                    .productName(item.getProductName())
                                    .priceName(item.getPriceName())
                                    .unitPrice(item.getUnitPrice())
                                    .quantity(item.getQuantity())
                                    .subtotal(item.getSubtotal())
                                    .unit(item.getUnit())
                                    .saleType(item.getSaleType() != null ? item.getSaleType() : "RETAIL")
                                    .unitsPerBox(item.getUnitsPerBox())
                                    .defaultPrice(item.getDefaultPrice())
                                    .vatRate(item.getVatRate())
                                    .vatMode(item.getVatMode())
                                    .vatAmount(item.getVatAmount())
                                    .tierPrice(
                                            item.getDiscountPercent() != null && item.getDiscountPercent() > 0
                                                    ? item.getUnitPrice().multiply(BigDecimal.valueOf(100))
                                                    .divide(BigDecimal.valueOf(100 - item.getDiscountPercent()), 0, java.math.RoundingMode.HALF_UP)
                                                    : item.getUnitPrice()
                                    )
                                    .notes(item.getNotes())
                                    .ingredientsUsed(item.getIngredientsUsed() == null
                                            ? List.of()
                                            : item.getIngredientsUsed().stream()
                                            .map(ing -> InvoiceDTO.Ingredient.builder()
                                                    .ingredientName(ing.getIngredientName())
                                                    .quantityUsed(ing.getQuantityUsed())
                                                    .unit(ing.getUnit())
                                                    .build())
                                            .collect(Collectors.toList()))
                                    .build())
                            .collect(Collectors.toList()))
                    .build();

            boolean showPrices = order.getShowPrices() == null || order.getShowPrices();
            byte[] pdfBytes = invoicePdf.GenerateInvoicePdf(invoiceDTO, showPrices);
            String filename = "invoice_" + order.getOrderCode().substring(2) + ".pdf";

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "application/pdf")
                    .body(pdfBytes);

        } catch (Exception e) {
            log.error("Lỗi generate invoice cho order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(com.nhatnam.server.dto.response.ApiResponse.error(500, "Lỗi khi xử lý hóa đơn: " + e.getMessage()));
        }
    }

    @GetMapping("/export")
    public ResponseEntity<?> exportAllOrders(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long customerId,
            Authentication auth) {
        try {
            // Lấy tất cả không phân trang, dùng Specification giống list()
            org.springframework.data.jpa.domain.Specification<Order> spec = (root, query, cb) -> {
                var ps = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();

                if (q != null && !q.isBlank()) {
                    String like = "%" + q.toLowerCase() + "%";
                    ps.add(cb.or(
                            cb.like(cb.lower(root.get("orderCode")), like),
                            cb.like(cb.lower(cb.coalesce(root.get("customerName"), "")), like),
                            cb.like(cb.lower(cb.coalesce(root.get("customerPhone"), "")), like),
                            cb.like(cb.lower(cb.coalesce(root.get("companyName"), "")), like)
                    ));
                }
                if (status != null && !status.isBlank()) {
                    try {
                        ps.add(cb.equal(root.get("status"),
                                com.nhatnam.server.enumtype.OrderStatus.valueOf(status)));
                    } catch (IllegalArgumentException ignored) {}
                }
                if (customerId != null) {
                    ps.add(cb.equal(root.get("customer").get("id"), customerId));
                }
                if (from != null) {
                    ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
                }
                if (to != null) {
                    ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
                }
                return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
            };

            List<Order> orders = orderRepository.findAll(spec,
                    Sort.by("createdAt").descending());

            String actorName = getActorNameFromAuth(auth);
            byte[] bytes = exporter.export(orders, "Tất cả đơn hàng", actorName);
            String filename = "don-hang-all-" + LocalDate.now() + ".xlsx";
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(bytes);
        } catch (Exception e) {
            log.error("[ADMIN] exportAllOrders error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/{orderId}/extend-deadline")
    public ResponseEntity<ApiResponse<?>> extendPaymentDeadline(
            @PathVariable Long orderId,
            @RequestParam @Min(1) @Max(365) int days,
            Authentication auth) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng"));

        if (!"DEBT".equals(order.getPaymentMethod()))
            return ResponseEntity.badRequest().body(ApiResponse.error("Chỉ áp dụng cho đơn công nợ"));

        String actorName = getActorNameFromAuth(auth);
        order.setDebtDays(order.getDebtDays() + days);
        orderRepository.save(order);

        // Log gia hạn
        orderLogRepository.save(OrderLog.builder()
                .order(order)
                .action("DEADLINE_EXTENDED")
                .actorName(actorName)
                .actorRole("ADMIN")
                .note("Gia hạn thêm " + days + " ngày")
                .createdAt(System.currentTimeMillis())
                .build());

        return ResponseEntity.ok(ApiResponse.ok("Gia hạn thành công"));
    }

    @GetMapping
    public ApiResponse<PageResponse<OrderDto>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long fromDate,
            @RequestParam(required = false) Long toDate,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(orderAdminService.list(q, status, paymentStatus, userId, customerId, productId, fromDate, toDate, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(orderAdminService.getById(id));
    }

    /** Soft cancel. Đơn vẫn còn trong DB, chỉ đổi status = CANCELLED */
    @PostMapping("/{id}/cancel")
    public ApiResponse<OrderDto> cancel(@PathVariable Long id,
                                        @RequestBody(required = false) CancelOrderRequest req,
                                        Authentication auth) {
        String actorName = getActorNameFromAuth(auth);
        String reason = req == null ? null : req.getReason();
        // orderAdminService.cancel phải log CANCELLED + actorName + reason
        return ApiResponse.ok("Hủy đơn hàng thành công",
                orderAdminService.cancel(id, reason, actorName));
    }

    private String getActorNameFromAuth(Authentication auth) {
        if (auth == null) return "Admin";
        Object p = auth.getPrincipal();
        if (p instanceof User u)
            return u.getFullName() != null && !u.getFullName().isBlank()
                    ? u.getFullName() : u.getUsername();
        return auth.getName();
    }
}
