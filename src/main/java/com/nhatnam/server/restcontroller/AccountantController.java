package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.ChartPointResponse;
import com.nhatnam.server.dto.DashboardSummaryResponse;
import com.nhatnam.server.dto.InvoiceDTO;
import com.nhatnam.server.dto.TopProductResponse;
import com.nhatnam.server.repository.ProductRepository;
import com.nhatnam.server.dto.dashboard.TopCustomerDto;
import com.nhatnam.server.dto.dashboard.TopSellerDto;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.dto.response.OrderResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.service.DashboardService;
import com.nhatnam.server.service.OrderService;
import com.nhatnam.server.utils.InvoicePdf;
import com.nhatnam.server.utils.OrderExcelExporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import com.nhatnam.server.service.FileStorageService;
import com.nhatnam.server.repository.OrderLogRepository;

@RestController
@RequestMapping("/api/accountant")
@RequiredArgsConstructor
@Log4j2
public class AccountantController {

    private final ProductRepository productRepository;
    private final DashboardService   dashboardService;
    private final OrderRepository    orderRepository;
    private final OrderService       orderService;
    private final CustomerRepository customerRepository;
    private final OrderExcelExporter exporter;
    private final InvoicePdf         invoicePdf;
    private final OrderLogRepository  orderLogRepository;
    private final FileStorageService   fileStorageService;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @GetMapping("/orders/{id}/detail")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderDetail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(orderService.getOrderById(id), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // INVOICE  (accountant có endpoint riêng, không gọi vào seller)
    // ════════════════════════════════════════════════════════════════

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
                    .surchargeDetail(order.getSurchargeDetail())
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
                    .hideAllPrices(order.getHideAllPrices())
                    .deliveryAddress(order.getDeliveryAddress())
                    .vatBreakdownInclusive(vatBreakdownInclusive)   // ← thay thế vatBreakdown cũ
                    .vatBreakdownExclusive(vatBreakdownExclusive)   // ← thêm mới
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
                    .body(ApiResponse.error(500, "Lỗi khi xử lý hóa đơn: " + e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // EXPORT EXCEL
    // ════════════════════════════════════════════════════════════════

    /**
     * Lấy danh sách đơn hàng PENDING_PAYMENT — không filter theo ngày.
     * Hỗ trợ search theo mã đơn hoặc tên khách hàng (param: search).
     * Dùng cho form tạo phiếu thu.
     */
    @GetMapping("/orders/pending-payment")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPendingPaymentOrders(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<Order> orders = new java.util.ArrayList<>(
                    orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.PENDING_PAYMENT)
            );

            // Filter search (accent-insensitive)
            if (search != null && !search.isBlank()) {
                String kw = removeAccents(search.toLowerCase().trim());
                orders = orders.stream()
                        .filter(o -> matchesKeyword(o.getOrderCode(),    kw)
                                || matchesKeyword(o.getCustomerName(), kw)
                                || matchesKeyword(o.getCustomerPhone(),kw))
                        .toList();
            }

            int total = orders.size();
            int start = page * size;
            int end   = Math.min(start + size, total);
            List<Map<String, Object>> content = (start >= total ? List.<Order>of() : orders.subList(start, end))
                    .stream().map(this::_toOrderResponseMap).toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",     content);
            result.put("totalItems",  total);
            result.put("currentPage", page);
            result.put("totalPages",  (int) Math.ceil((double) total / size));
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getPendingPaymentOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // THÊM param keyword vào signature:
    @GetMapping("/orders/export")
    public ResponseEntity<?> exportAllOrders(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,        // ← THÊM
            @RequestParam(required = false, defaultValue = "false") boolean excludeCancelled,
            Authentication auth) {
        try {
            boolean hasKeyword = keyword != null && !keyword.isBlank();

            List<Order> orders;
            if (hasKeyword) {
                // Search toàn bộ, không filter ngày
                orders = new java.util.ArrayList<>(
                        orderRepository.findAll(Sort.by("createdAt").descending())
                );
            } else if (from != null && to != null) {
                orders = new java.util.ArrayList<>(
                        orderRepository.findByCreatedAtBetween(from, to)
                );
            } else {
                orders = new java.util.ArrayList<>(
                        orderRepository.findAll(Sort.by("createdAt").descending())
                );
            }

            // Filter status
            if (status != null && !status.isBlank())
                orders = orders.stream()
                        .filter(o -> o.getStatus().name().equalsIgnoreCase(status))
                        .collect(Collectors.toList());

            // Filter keyword — dùng lại logic accent-insensitive đã có
            if (hasKeyword) {
                String kw = removeAccents(keyword.toLowerCase().trim());
                orders = orders.stream()
                        .filter(o ->
                                matchesKeyword(o.getOrderCode(),     kw)
                                        || matchesKeyword(o.getCustomerName(),  kw)
                                        || matchesKeyword(o.getCustomerPhone(), kw)
                                        || matchesKeyword(o.getOrderedByName(), kw)
                                        || (o.getUser() != null && (
                                        matchesKeyword(o.getUser().getFullName(), kw)
                                                || matchesKeyword(o.getUser().getUsername(), kw)))
                        )
                        .collect(Collectors.toList());
            }

            boolean isAccountant = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ACCOUNTANT") || a.getAuthority().equals("ROLE_SUPER_ACCOUNTANT"));

            String actorName = getActorName(auth);

            orders.sort(Comparator.comparingLong(Order::getId));

            byte[] bytes = exporter.exportForAccountant(orders, "Tất cả đơn hàng", actorName, isAccountant);
            String filename = "don-hang-all-" + LocalDate.now() + ".xlsx";
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(bytes);
        } catch (Exception e) {
            log.error("[ACCOUNTANT] exportAllOrders error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // DASHBOARD
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/dashboard/summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> getSummary(
            @RequestParam long from, @RequestParam long to) {
        try {
            return ResponseEntity.ok(ApiResponse.success(dashboardService.getSummary(from, to), "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getSummary error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/chart")
    public ResponseEntity<ApiResponse<List<ChartPointResponse>>> getChart(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(defaultValue = "DAY") String groupBy) {
        try {
            return ResponseEntity.ok(ApiResponse.success(dashboardService.getChart(from, to, groupBy), "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getChart error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/top-products")
    public ResponseEntity<ApiResponse<List<TopProductResponse>>> getTopProducts(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            return ResponseEntity.ok(ApiResponse.success(dashboardService.getTopProducts(from, to, limit), "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getTopProducts error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/top-sellers")
    public ResponseEntity<ApiResponse<List<TopSellerDto>>> getTopSellers(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    dashboardService.getTopSellers(limit, from, to, "revenue"), "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getTopSellers error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/top-customers")
    public ResponseEntity<ApiResponse<List<TopCustomerDto>>> getTopCustomers(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            return ResponseEntity.ok(ApiResponse.success(dashboardService.getTopCustomers(limit, from, to), "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getTopCustomers error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/debt-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDebtStats(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        try {
            LocalDate today    = LocalDate.now(VN);
            long todayMs       = today.atStartOfDay(VN).toInstant().toEpochMilli();
            long in7DaysMs     = today.plusDays(7).atStartOfDay(VN).toInstant().toEpochMilli();

            List<Order> debtOrders = orderRepository
                    .findByStatusOrderByCreatedAtDesc(OrderStatus.PENDING_PAYMENT)
                    .stream()
                    .filter(o -> "DEBT".equalsIgnoreCase(
                            o.getPaymentMethod() != null ? o.getPaymentMethod() : ""))
                    .toList();

            long nearingDeadline = 0, overdueCount = 0;
            for (Order o : debtOrders) {
                if (o.getPendingPaymentAt() == null || o.getPendingPaymentAt() <= 0) continue;
                if (o.getDebtDays() <= 0) continue;
                LocalDate pendingDate = Instant.ofEpochMilli(o.getPendingPaymentAt())
                        .atZone(VN).toLocalDate();
                LocalDate deadline    = pendingDate.plusDays(1).plusDays(o.getDebtDays());
                long deadlineMs       = deadline.atStartOfDay(VN).toInstant().toEpochMilli();
                if (deadlineMs < todayMs)          overdueCount++;
                else if (deadlineMs <= in7DaysMs)  nearingDeadline++;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nearingDeadline", nearingDeadline);
            result.put("overdueCount",    overdueCount);
            result.put("totalDebtOrders", debtOrders.size());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getDebtStats error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ORDERS
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOrders(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            boolean hasKeyword = keyword != null && !keyword.isBlank();

            // Khi có keyword → bỏ filter ngày, lấy toàn bộ
            // Khi không có keyword → filter theo ngày (mặc định hôm nay nếu không truyền)
            List<Order> orders;
            if (hasKeyword) {
                orders = new java.util.ArrayList<>(
                        orderRepository.findAll(Sort.by("createdAt").descending())
                );
            } else if (from != null && to != null) {
                orders = new java.util.ArrayList<>(
                        orderRepository.findByCreatedAtBetween(from, to)
                );
            } else {
                // Default: hôm nay theo timezone VN
                LocalDate today = LocalDate.now(VN);
                long fromMs = today.atStartOfDay(VN).toInstant().toEpochMilli();
                long toMs   = today.atTime(23, 59, 59, 999_000_000).atZone(VN).toInstant().toEpochMilli();
                orders = new java.util.ArrayList<>(
                        orderRepository.findByCreatedAtBetween(fromMs, toMs)
                );
            }

            // Filter status
            if (status != null && !status.isBlank()) {
                orders = orders.stream()
                        .filter(o -> o.getStatus().name().equalsIgnoreCase(status))
                        .collect(Collectors.toList());
            }

            // Filter customerId
            if (customerId != null) {
                final Long cid = customerId;
                orders = orders.stream()
                        .filter(o -> o.getCustomer() != null && o.getCustomer().getId().equals(cid))
                        .collect(Collectors.toList());
            }

            // Filter productId
            if (productId != null) {
                final Long pid = productId;
                orders = orders.stream()
                        .filter(o -> o.getOrderItems() != null && o.getOrderItems().stream()
                                .anyMatch(i -> pid.equals(i.getProductId())))
                        .collect(Collectors.toList());
            }

            // Filter keyword — accent-insensitive (gõ không dấu vẫn ra)
            if (hasKeyword) {
                String kw = removeAccents(keyword.toLowerCase().trim());
                orders = orders.stream()
                        .filter(o ->
                                matchesKeyword(o.getOrderCode(),     kw)
                                        || matchesKeyword(o.getCustomerName(),  kw)
                                        || matchesKeyword(o.getCustomerPhone(), kw)
                                        || matchesKeyword(o.getOrderedByName(), kw)
                                        || (o.getUser() != null && (
                                        matchesKeyword(o.getUser().getFullName(), kw)
                                                || matchesKeyword(o.getUser().getUsername(), kw)))
                        )
                        .collect(Collectors.toList());
            }

            int total = orders.size();
            int start = page * size;
            int end   = Math.min(start + size, total);
            List<Order> paged = start >= total ? List.of() : orders.subList(start, end);
            List<Map<String, Object>> content = paged.stream().map(this::_toOrderMap).toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",     content);
            result.put("totalItems",  total);
            result.put("currentPage", page);
            result.put("totalPages",  (int) Math.ceil((double) total / size));
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Helpers accent-insensitive ────────────────────────────────────────────────
    private static String removeAccents(String s) {
        if (s == null) return "";
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return normalized
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace("đ", "d").replace("Đ", "D");
    }

    private static boolean matchesKeyword(String field, String kwNoAccent) {
        if (field == null || field.isBlank()) return false;
        return removeAccents(field.toLowerCase()).contains(kwNoAccent);
    }

    @PatchMapping("/orders/{id}/partial-payment")
    public ResponseEntity<ApiResponse<Object>> recordPartialPayment(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        try {
            Object amtRaw = body.get("paidAmount");
            if (amtRaw == null)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Thiếu trường paidAmount"));
            BigDecimal paidAmount = new BigDecimal(amtRaw.toString());
            int debtDays = body.get("debtDays") instanceof Number n ? n.intValue() : 0;
            String paymentMethod = body.get("paymentMethod") instanceof String s ? s : null;
            String bankName      = body.get("bankName") instanceof String s ? s : null;
            String txRef         = body.get("transactionRef") instanceof String s ? s : null;

            User actor = (User) auth.getPrincipal();
            orderService.recordPartialPayment(id, paidAmount, debtDays, getActorName(auth),
                    paymentMethod, bankName, txRef, actor.getId());
            return ResponseEntity.ok(ApiResponse.success(null, "Ghi nhận thanh toán thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] recordPartialPayment error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{id}/pending-payment")
    public ResponseEntity<ApiResponse<Object>> markPendingPayment(
            @PathVariable Long id, Authentication auth) {
        try {
            User actor = (User) auth.getPrincipal();
            orderService.markAsPendingPayment(id, getActorName(auth), actor.getId());
            return ResponseEntity.ok(ApiResponse.success(null, "Đã chuyển sang Chờ thanh toán"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{id}/cancel")
    public ResponseEntity<ApiResponse<Object>> cancelOrder(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        try {
            String reason = body.get("reason");
            if (reason == null || reason.isBlank())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Vui lòng nhập lý do hủy đơn"));
            User user = (User) auth.getPrincipal();
            orderService.cancelOrder(id, user.getId(), getActorName(auth),
                    user.getRole().name(), reason);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã hủy đơn hàng"));
        } catch (IllegalStateException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] cancelOrder id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{id}/complete")
    public ResponseEntity<ApiResponse<Object>> markCompleted(
            @PathVariable Long id, Authentication auth) {
        try {
            User actor = (User) auth.getPrincipal();
            orderService.markAsCompleted(id, getActorName(auth), actor.getId());
            return ResponseEntity.ok(ApiResponse.success(null, "Đã hoàn thành đơn hàng"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{id}/payment")
    public ResponseEntity<ApiResponse<Object>> updatePayment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        try {
            String actor = getActorName(auth);
            if (body.containsKey("paymentMethod"))
                orderService.updatePaymentMethod(id, body.get("paymentMethod"), actor);
            if (body.containsKey("paymentStatus")) {
                Order order = orderRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Order not found: " + id));
                order.setPaymentStatus(PaymentStatus.valueOf(body.get("paymentStatus").toUpperCase()));
                order.setUpdatedAt(System.currentTimeMillis());
                orderRepository.save(order);
            }
            return ResponseEntity.ok(ApiResponse.success(null, "Cập nhật thanh toán thành công"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{id}/status")
    public ResponseEntity<ApiResponse<Object>> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        try {
            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + id));
            order.setStatus(OrderStatus.valueOf(body.get("status").toUpperCase()));
            order.setUpdatedAt(System.currentTimeMillis());
            orderRepository.save(order);
            return ResponseEntity.ok(ApiResponse.success(null, "Cập nhật trạng thái thành công"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CUSTOMERS
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCustomers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<Customer> all = customerRepository
                    .findByIsActiveTrueOrderByCustomerCodeAscNameAsc();

            if (q != null && !q.isBlank()) {
                String lower = q.toLowerCase();
                all = all.stream().filter(c ->
                        (c.getName()         != null && c.getName().toLowerCase().contains(lower))        ||
                                (c.getCompanyName()  != null && c.getCompanyName().toLowerCase().contains(lower)) ||
                                (c.getPhone()        != null && c.getPhone().contains(lower))                     ||
                                (c.getCustomerCode() != null && c.getCustomerCode().toLowerCase().contains(lower))
                ).toList();
            }

            List<Map<String, Object>> content = all.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",           c.getId());
                m.put("name",         c.getName());
                m.put("companyName",  c.getCompanyName());
                m.put("contactName",  c.getContactName());
                m.put("phone",        c.getPhone());
                m.put("customerCode", c.getCustomerCode());
                m.put("customerType", c.getCustomerType());
                m.put("debtDays",     c.getDebtDays());
                m.put("isActive",     c.getIsActive());

                Long nearest = null;
                if (c.getDebtDays() != null && c.getDebtDays() > 0) {
                    List<Order> pending = orderRepository
                            .findByCustomerIdAndStatus(c.getId(), OrderStatus.PENDING_PAYMENT);
                    for (Order o : pending) {
                        if (o.getPendingPaymentAt() == null) continue;
                        LocalDate base     = Instant.ofEpochMilli(o.getPendingPaymentAt())
                                .atZone(VN).toLocalDate();
                        LocalDate deadline = base.plusDays(1).plusDays(c.getDebtDays());
                        long ms            = deadline.atStartOfDay(VN).toInstant().toEpochMilli();
                        if (nearest == null || ms < nearest) nearest = ms;
                    }
                }
                m.put("nearestDeadlineMillis", nearest);
                return m;
            }).toList();

            int total = content.size();
            int start = page * size;
            int end   = Math.min(start + size, total);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",     start >= total ? List.of() : content.subList(start, end));
            result.put("totalItems",  total);
            result.put("currentPage", page);
            result.put("totalPages",  (int) Math.ceil((double) total / size));
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getCustomers error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/customers/{customerId}/orders")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCustomerOrders(
            @PathVariable Long customerId) {
        try {
            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy khách hàng: " + customerId));

            List<Order> orders       = orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
            long totalOrders         = orders.size();
            long completedOrders     = orders.stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED).count();
            long activeOrders        = orders.stream().filter(o ->
                    o.getStatus() != OrderStatus.COMPLETED &&
                            o.getStatus() != OrderStatus.CANCELLED &&
                            o.getStatus() != OrderStatus.FAILED).count();

            BigDecimal totalAmount = orders.stream().map(Order::getFinalAmount)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal completedAmount = orders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
                    .map(Order::getFinalAmount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal pendingPaymentAmount = orders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.PENDING_PAYMENT)
                    .map(Order::getFinalAmount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            List<Map<String, Object>> orderList = orders.stream().map(o -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",            o.getId());
                m.put("orderCode",     o.getOrderCode());
                m.put("status",        o.getStatus());
                m.put("paymentStatus", o.getPaymentStatus());
                m.put("paymentMethod", o.getPaymentMethod());
                m.put("finalAmount",   o.getFinalAmount());
                m.put("orderedByName", o.getOrderedByName());
                m.put("createdAt",     o.getCreatedAt());
                int debtDays = o.getDebtDays();
                if (debtDays > 0 && "DEBT".equals(o.getPaymentMethod())
                        && o.getStatus() == OrderStatus.PENDING_PAYMENT
                        && o.getPendingPaymentAt() != null) {
                    LocalDate base     = Instant.ofEpochMilli(o.getPendingPaymentAt())
                            .atZone(VN).toLocalDate();
                    LocalDate deadline = base.plusDays(1).plusDays(debtDays);
                    m.put("paymentDeadline",       deadline.format(fmt));
                    m.put("paymentDeadlineMillis",
                            deadline.atStartOfDay(VN).toInstant().toEpochMilli());
                } else {
                    m.put("paymentDeadline",       null);
                    m.put("paymentDeadlineMillis", null);
                }
                return m;
            }).toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("customerId",           customerId);
            result.put("customerName",         customer.getCustomerType() == Customer.CustomerType.COMPANY
                    ? customer.getCompanyName() : customer.getName());
            result.put("customerPhone",        customer.getPhone());
            result.put("customerCode",         customer.getCustomerCode());
            result.put("customerType",         customer.getCustomerType());
            result.put("debtDays",             customer.getDebtDays());
            result.put("totalOrders",          totalOrders);
            result.put("completedOrders",      completedOrders);
            result.put("activeOrders",         activeOrders);
            result.put("totalAmount",          totalAmount);
            result.put("completedAmount",      completedAmount);
            result.put("pendingPaymentAmount", pendingPaymentAmount);
            result.put("orders",               orderList);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getCustomerOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/orders/{id}/payment-transactions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPaymentTransactions(
            @PathVariable Long id) {
        try {
            List<Map<String, Object>> txList = orderService.getPaymentTransactions(id)
                    .stream().map(tx -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("id",             tx.getId());
                        m.put("amount",         tx.getAmount());
                        m.put("paymentMethod",  tx.getPaymentMethod());
                        m.put("bankName",       tx.getBankName());
                        m.put("transactionRef", tx.getTransactionRef());
                        m.put("note",           tx.getNote());
                        m.put("collectedBy",    tx.getCollectedBy());
                        m.put("createdAt",      tx.getCreatedAt());
                        return m;
                    }).toList();
            return ResponseEntity.ok(ApiResponse.success(txList, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────
    private Map<String, Object> _toOrderResponseMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            o.getId());
        m.put("orderCode",     o.getOrderCode());
        m.put("customerName",  o.getCustomerName());
        m.put("customerPhone", o.getCustomerPhone());
        m.put("customerType",  o.getCustomerType());
        m.put("warehouseName", o.getWarehouseName());
        m.put("status",        o.getStatus());
        m.put("paymentStatus", o.getPaymentStatus());
        m.put("paymentMethod", o.getPaymentMethod());

        BigDecimal finalAmt = o.getFinalAmount() != null
                ? o.getFinalAmount().setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal paidAmt = o.getPaidAmount() != null
                ? o.getPaidAmount().setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal remainingAmt = finalAmt.subtract(paidAmt).max(BigDecimal.ZERO);

        m.put("finalAmount",     finalAmt);
        m.put("paidAmount",      paidAmt);
        m.put("remainingAmount", remainingAmt);   // <-- field mới

        m.put("discountAmount",    o.getDiscountAmount());
        m.put("vatAmount",         o.getVatAmount());
        m.put("surcharge",         o.getSurcharge());
        m.put("notes",             o.getNotes());
        m.put("orderedByName",     o.getUser().getFullName());
        m.put("createdAt",         o.getCreatedAt());
        m.put("updatedAt",         o.getUpdatedAt());
        m.put("debtDays",          o.getDebtDays());
        m.put("pendingPaymentAt",  o.getPendingPaymentAt());
        m.put("receiptFileUrl",    o.getReceiptFileUrl());
        m.put("invoiceNumber",     o.getInvoiceNumber());
        return m;
    }

    private Map<String, Object> _toOrderMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            o.getId());
        m.put("orderCode",     o.getOrderCode());
        m.put("customerName",  o.getCustomerName());
        m.put("customerPhone", o.getCustomerPhone());
        m.put("customerType",  o.getCustomerType());
        m.put("warehouseName", o.getWarehouseName());
        m.put("status",        o.getStatus());
        m.put("paymentStatus", o.getPaymentStatus());
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("finalAmount",   o.getFinalAmount());
        m.put("discountAmount",o.getDiscountAmount());
        m.put("vatAmount",     o.getVatAmount());
        m.put("surcharge",     o.getSurcharge());
        m.put("notes",         o.getNotes());
        m.put("orderedByName", o.getUser().getFullName());
        m.put("createdAt",     o.getCreatedAt());
        m.put("updatedAt",     o.getUpdatedAt());
        m.put("paidAmount",     o.getPaidAmount() != null ? o.getPaidAmount() : BigDecimal.ZERO);
        m.put("debtDays",       o.getDebtDays());
        m.put("pendingPaymentAt", o.getPendingPaymentAt());
        m.put("receiptFileUrl", o.getReceiptFileUrl());
        m.put("invoiceNumber",  o.getInvoiceNumber());
        return m;
    }

    @PatchMapping("/orders/{id}/waive-remainder")
    public ResponseEntity<ApiResponse<Object>> waiveRemainder(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        try {
            Object amtRaw = body.get("actualPaid");
            if (amtRaw == null)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Thiếu trường actualPaid"));
            BigDecimal actualPaid   = new BigDecimal(amtRaw.toString());
            String paymentMethod    = body.get("paymentMethod") instanceof String s ? s : null;
            String bankName         = body.get("bankName")      instanceof String s ? s : null;
            String txRef            = body.get("transactionRef") instanceof String s ? s : null;

            User actor = (User) auth.getPrincipal();
            orderService.confirmWaiveRemainder(id, actualPaid, getActorName(auth),
                    actor.getId(), paymentMethod, bankName, txRef);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xác nhận bỏ số lẻ, đơn hoàn thành"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] waiveRemainder error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/orders/bulk-complete")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkComplete(
            @RequestBody Map<String, Object> body, Authentication auth) {
        try {
            String actorName = getActorName(auth);
            User actor = (User) auth.getPrincipal();
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) body.get("orderIds");
            String mode = body.get("mode") instanceof String s ? s : "DELIVERED_PAID";
            boolean finalPartial = Boolean.TRUE.equals(body.get("finalPartialPayment"));
            if (rawIds == null || rawIds.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Thiếu danh sách đơn hàng"));
            List<String> errors = new ArrayList<>();
            int successCount = 0;
            for (Integer rawId : rawIds) {
                Long orderId = rawId.longValue();
                try {
                    if ("DELIVERED_UNPAID".equals(mode) && !finalPartial) {
                        // Kiểm tra đơn phải đang DELIVERING mới cho chuyển sang PENDING_PAYMENT
                        Order order = orderRepository.findById(orderId)
                                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn: " + orderId));
                        if (order.getStatus() != OrderStatus.DELIVERING) {
                            errors.add(orderId + ": Đơn phải ở trạng thái 'Đang giao' mới có thể cập nhật");
                            continue;
                        }
                        orderService.markAsPendingPayment(orderId, actorName, actor.getId());
                    } else {
                        orderService.markAsCompleted(orderId, actorName, actor.getId());
                    }
                    successCount++;
                } catch (Exception e) { errors.add(orderId + ": " + e.getMessage()); }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", successCount); result.put("errors", errors);
            return ResponseEntity.ok(ApiResponse.success(result, successCount + " đơn đã cập nhật"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] bulkComplete error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping(value = "/orders/{id}/receipt-file", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Object>> uploadReceiptFile(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        try {
            if (file == null || file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "File không được rỗng"));
            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + id));
            String savedPath = fileStorageService.saveReceiptFile(file);
            order.setReceiptFileUrl(savedPath);
            order.setUpdatedAt(System.currentTimeMillis());
            orderRepository.save(order);
            return ResponseEntity.ok(ApiResponse.success(Map.of("receiptFileUrl", savedPath), "Đã cập nhật phiếu nhận hàng"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] uploadReceiptFile id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/orders/{id}/logs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getOrderLogs(@PathVariable Long id) {
        try {
            List<Map<String, Object>> logs = orderLogRepository.findByOrderIdOrderByCreatedAtAsc(id)
                    .stream().map(l -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", l.getId()); m.put("action", l.getAction());
                        m.put("actorName", l.getActorName()); m.put("actorRole", l.getActorRole());
                        m.put("note", l.getNote()); m.put("createdAt", l.getCreatedAt());
                        return m;
                    }).toList();
            return ResponseEntity.ok(ApiResponse.success(logs, "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getOrderLogs id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private String getActorName(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return "Accountant";
        Object principal = auth.getPrincipal();
        if (principal instanceof User u)
            return u.getFullName() != null && !u.getFullName().isBlank()
                    ? u.getFullName() : u.getUsername();
        return auth.getName();
    }

    /** Change 3: Danh sách sản phẩm để filter đơn hàng */
    @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getProducts() {
        try {
            List<Map<String, Object>> products = productRepository.findByIsActiveTrue()
                    .stream().map(p -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("id",   p.getId());
                        m.put("name", p.getName());
                        return m;
                    }).toList();
            return ResponseEntity.ok(ApiResponse.success(products, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(com.nhatnam.server.enumtype.StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/dashboard/debt-orders")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDebtOrders(
            @RequestParam(defaultValue = "NEARING") String type) {
        try {
            LocalDate today    = LocalDate.now(VN);
            long todayMs       = today.atStartOfDay(VN).toInstant().toEpochMilli();
            long in7DaysMs     = today.plusDays(7).atStartOfDay(VN).toInstant().toEpochMilli();

            List<Order> debtOrders = orderRepository
                    .findByStatusOrderByCreatedAtDesc(OrderStatus.PENDING_PAYMENT)
                    .stream()
                    .filter(o -> "DEBT".equalsIgnoreCase(
                            o.getPaymentMethod() != null ? o.getPaymentMethod() : ""))
                    .toList();

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

            List<Map<String, Object>> result = new ArrayList<>();
            for (Order o : debtOrders) {
                if (o.getPendingPaymentAt() == null || o.getPendingPaymentAt() <= 0) continue;
                if (o.getDebtDays() <= 0) continue;

                LocalDate pendingDate = Instant.ofEpochMilli(o.getPendingPaymentAt())
                        .atZone(VN).toLocalDate();
                LocalDate deadline    = pendingDate.plusDays(1).plusDays(o.getDebtDays());
                long deadlineMs       = deadline.atStartOfDay(VN).toInstant().toEpochMilli();

                boolean isOverdue  = deadlineMs < todayMs;
                boolean isNearing  = !isOverdue && deadlineMs <= in7DaysMs;

                boolean include = "OVERDUE".equalsIgnoreCase(type) ? isOverdue : isNearing;
                if (!include) continue;

                // Số ngày quá hạn (dương = quá hạn, âm = còn bao nhiêu ngày)
                long daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(deadline, today);

                BigDecimal finalAmt = o.getFinalAmount()  != null ? o.getFinalAmount()  : BigDecimal.ZERO;
                BigDecimal paidAmt  = o.getPaidAmount()   != null ? o.getPaidAmount()   : BigDecimal.ZERO;
                BigDecimal remaining = finalAmt.subtract(paidAmt);

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",                  o.getId());
                m.put("orderCode",           o.getOrderCode());
                m.put("customerName",        o.getCustomerName());
                m.put("customerPhone",       o.getCustomerPhone());
                m.put("customerType",        o.getCustomerType());
                m.put("warehouseName",       o.getWarehouseName());
                m.put("orderedByName",       o.getOrderedByName());
                m.put("finalAmount",         finalAmt);
                m.put("paidAmount",          paidAmt);
                m.put("remainingAmount",     remaining);
                m.put("debtDays",            o.getDebtDays());
                m.put("pendingPaymentAt",    o.getPendingPaymentAt());
                m.put("paymentDeadline",     deadline.format(fmt));
                m.put("paymentDeadlineMillis", deadlineMs);
                m.put("daysOverdue",         daysOverdue);   // >0 = quá hạn, <0 = còn X ngày
                m.put("createdAt",           o.getCreatedAt());
                m.put("updatedAt",           o.getUpdatedAt());
                m.put("paymentMethod",       o.getPaymentMethod());
                m.put("paymentStatus",       o.getPaymentStatus());
                m.put("status",              o.getStatus());
                result.add(m);
            }

            // Sort: OVERDUE → nhiều ngày nhất trước; NEARING → sắp đến hạn nhất trước
            result.sort((a, b) -> {
                long da = (Long) a.get("paymentDeadlineMillis");
                long db = (Long) b.get("paymentDeadlineMillis");
                return "OVERDUE".equalsIgnoreCase(type)
                        ? Long.compare(da, db)   // quá hạn lâu nhất lên đầu
                        : Long.compare(da, db);  // sắp đến hạn nhất lên đầu
            });

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getDebtOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}