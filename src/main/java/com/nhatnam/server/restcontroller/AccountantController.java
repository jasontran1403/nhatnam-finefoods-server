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
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.service.DashboardService;
import com.nhatnam.server.service.OrderService;
import com.nhatnam.server.utils.AuthRoleUtil;
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
import java.time.temporal.ChronoUnit;
import com.nhatnam.server.entity.IncomeVoucher;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import com.nhatnam.server.service.FileStorageService;
import com.nhatnam.server.repository.OrderLogRepository;
import com.nhatnam.server.repository.IncomeVoucherRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/accountant")
@RequiredArgsConstructor
@Log4j2
public class AccountantController {

    private final ProductRepository productRepository;
    private final DashboardService   dashboardService;
    private final OrderRepository    orderRepository;
    private final OrderService       orderService;
    /** Quy tắc thu tiền trước nằm ở impl, không có trên interface OrderService. */
    private final com.nhatnam.server.service.serviceimpl.OrderServiceImpl orderServiceImpl;
    private final CustomerRepository customerRepository;
    private final OrderExcelExporter exporter;
    private final InvoicePdf         invoicePdf;
    private final OrderLogRepository  orderLogRepository;
    private final FileStorageService   fileStorageService;
    private final IncomeVoucherRepository incomeVoucherRepository;
    private final ObjectMapper objectMapper;
    private final com.nhatnam.server.service.DebtStatsService debtStatsService;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @GetMapping("/orders/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(@PathVariable Long id) {
        try {
            OrderResponse order = orderService.getOrderById(id);
            order.setReceiptNumbers(buildReceiptNumbersByOrderCode(Set.of(order.getOrderCode()))
                    .getOrDefault(order.getOrderCode(), List.of()));
            return ResponseEntity.ok(ApiResponse.success(order, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/orders/{id}/detail")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderDetail(@PathVariable Long id) {
        try {
            OrderResponse order = orderService.getOrderById(id);
            order.setReceiptNumbers(buildReceiptNumbersByOrderCode(Set.of(order.getOrderCode()))
                    .getOrDefault(order.getOrderCode(), List.of()));
            return ResponseEntity.ok(ApiResponse.success(order, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    /**
     * Tra đơn theo MÃ — dùng khi SỬA phiếu thu để nạp lại các đơn đã liên kết.
     * Phiếu chỉ lưu mã đơn, không lưu id, nên cần đường tra theo mã.
     */
    @GetMapping("/orders/by-code/{code}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderByCode(@PathVariable String code) {
        try {
            OrderResponse order = orderService.getOrderByCode(code);
            order.setReceiptNumbers(buildReceiptNumbersByOrderCode(Set.of(order.getOrderCode()))
                    .getOrDefault(order.getOrderCode(), List.of()));
            return ResponseEntity.ok(ApiResponse.success(order, "OK"));
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
                    .provinceName(order.getProvinceName())
                    .wardName(order.getWardName())
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
            // Đơn CHỜ THANH TOÁN (đã giao, còn nợ)
            List<Order> orders = new java.util.ArrayList<>(
                    orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.PENDING_PAYMENT)
            );

            // ── THÊM: đơn THU TRƯỚC KHI GIAO ─────────────────────────────────
            // Khách bị owner yêu cầu "thanh toán trước" → kế toán phải tạo được phiếu thu
            // NGAY KHI đơn còn đang chuẩn bị (kho chưa được phép giao cho tới khi thu đủ).
            for (OrderStatus st : new OrderStatus[]{
                    OrderStatus.PENDING, OrderStatus.CONFIRMED,
                    OrderStatus.PREPARING, OrderStatus.READY}) {
                orderRepository.findByStatusOrderByCreatedAtDesc(st).stream()
                        // Lambda thay cho method reference: isPrepaymentRequired giờ là method
                        // của bean (cần AddressCatalogService), không còn static để tham chiếu.
                        .filter(o -> orderServiceImpl.isPrepaymentRequired(o))
                        .filter(o -> o.getPaymentStatus() != com.nhatnam.server.enumtype.PaymentStatus.PAID)
                        .filter(o -> o.getFinalAmount() != null
                                && o.getFinalAmount().compareTo(BigDecimal.ZERO) > 0)
                        .forEach(orders::add);
            }

            // Filter search (accent-insensitive)
            if (search != null && !search.isBlank()) {
                String kw = removeAccents(search.toLowerCase().trim());
                final BigDecimal amountKw = parseAmountKeyword(search);
                orders = orders.stream()
                        .filter(o -> matchesKeyword(o.getOrderCode(),    kw)
                                || matchesKeyword(o.getCustomerName(), kw)
                                || matchesKeyword(o.getCustomerPhone(),kw)
                                || matchesAmount(o.getFinalAmount(), amountKw))
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
            if (from != null && to != null) {
                // Đã chọn khoảng ngày → luôn filter theo ngày, kể cả có keyword
                orders = new java.util.ArrayList<>(
                        orderRepository.findByCreatedAtBetween(from, to)
                );
            } else if (hasKeyword) {
                // Chưa chọn ngày + có keyword → search toàn bộ, không filter ngày
                orders = new java.util.ArrayList<>(
                        orderRepository.findAll(Sort.by("createdAt").descending())
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
                // Tìm theo SỐ TIỀN — giữ ĐỒNG BỘ với danh sách trên màn hình
                final BigDecimal amountKw = parseAmountKeyword(keyword);
                orders = orders.stream()
                        .filter(o ->
                                matchesKeyword(o.getOrderCode(),     kw)
                                        || matchesKeyword(o.getCustomerName(),  kw)
                                        || matchesKeyword(o.getCustomerPhone(), kw)
                                        || matchesKeyword(o.getOrderedByName(), kw)
                                        || (o.getUser() != null && (
                                        matchesKeyword(o.getUser().getFullName(), kw)
                                                || matchesKeyword(o.getUser().getUsername(), kw)))
                                        || matchesAmount(o.getFinalAmount(), amountKw)
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
            var s = debtStatsService.compute();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nearingDeadline",       s.nearingCount());
            result.put("overdueCount",          s.overdueCount());
            result.put("totalDebtOrders",       s.totalDebtOrders());
            result.put("nearingDeadlineAmount", s.nearingAmount());
            result.put("overdueAmount",         s.overdueAmount());
            result.put("totalDebtAmount",       s.totalDebtAmount());
            result.put("aging0to30",            s.aging0to30());
            result.put("aging31to60",           s.aging31to60());
            result.put("aging61to90",           s.aging61to90());
            result.put("aging90plus",           s.aging90plus());
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
            @RequestParam(required = false) String receiptNumber,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            boolean hasKeyword = keyword != null && !keyword.isBlank();
            boolean hasReceiptNumber = receiptNumber != null && !receiptNumber.isBlank();

// Nếu có from/to (người dùng đã chọn khoảng ngày) → luôn filter theo ngày,
// kể cả khi có keyword.
// Nếu không có from/to:
//   - có keyword hoặc receiptNumber → tìm toàn bộ, không filter ngày
//   - không có gì → mặc định hôm nay
            List<Order> orders;
            if (from != null && to != null) {
                orders = new java.util.ArrayList<>(
                        orderRepository.findByCreatedAtBetween(from, to)
                );
            } else if (hasKeyword || hasReceiptNumber) {
                orders = new java.util.ArrayList<>(
                        orderRepository.findAll(Sort.by("createdAt").descending())
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

            // Filter theo số phiếu thu — tìm các phiếu thu có receiptNumber khớp,
            // rồi chỉ giữ lại các đơn nằm trong linkedOrderCodes của các phiếu đó
            // (dùng để "tìm các đơn có cùng phiếu thu").
            if (hasReceiptNumber) {
                Set<String> orderCodesWithReceipt = incomeVoucherRepository
                        .findByReceiptNumberContainingWithLinkedOrders(receiptNumber.trim())
                        .stream()
                        .flatMap(v -> parseOrderCodes(v.getLinkedOrderCodes()).stream())
                        .collect(Collectors.toSet());
                orders = orders.stream()
                        .filter(o -> orderCodesWithReceipt.contains(o.getOrderCode()))
                        .collect(Collectors.toList());
            }

            // Map orderCode -> receiptNumbers, tính 1 lần dùng cho cả lọc keyword và enrich kết quả trả về
            Map<String, List<String>> allReceiptsByOrderCode = (hasKeyword)
                    ? buildReceiptNumbersByOrderCode(orders.stream().map(Order::getOrderCode).collect(Collectors.toSet()))
                    : null;

            // Filter keyword — accent-insensitive (gõ không dấu vẫn ra)
            // Cũng khớp theo số phiếu thu liên kết với đơn.
            if (hasKeyword) {
                String kw = removeAccents(keyword.toLowerCase().trim());
                // Tìm theo SỐ TIỀN đơn hàng (final_amount) — null nếu keyword không phải số
                final BigDecimal amountKw = parseAmountKeyword(keyword);
                orders = orders.stream()
                        .filter(o ->
                                matchesKeyword(o.getOrderCode(),     kw)
                                        || matchesKeyword(o.getCustomerName(),  kw)
                                        || matchesKeyword(o.getCustomerPhone(), kw)
                                        || matchesKeyword(o.getOrderedByName(), kw)
                                        || (o.getUser() != null && (
                                        matchesKeyword(o.getUser().getFullName(), kw)
                                                || matchesKeyword(o.getUser().getUsername(), kw)))
                                        || allReceiptsByOrderCode.getOrDefault(o.getOrderCode(), List.of())
                                        .stream().anyMatch(rn -> matchesKeyword(rn, kw))
                                        || matchesAmount(o.getFinalAmount(), amountKw)
                        )
                        .collect(Collectors.toList());
            }

            int total = orders.size();
            int start = page * size;
            int end   = Math.min(start + size, total);
            List<Order> paged = start >= total ? List.of() : orders.subList(start, end);

            Set<String> pageOrderCodes = paged.stream().map(Order::getOrderCode).collect(Collectors.toSet());
            Map<String, List<String>> receiptsForPage = (allReceiptsByOrderCode != null)
                    ? allReceiptsByOrderCode.entrySet().stream()
                    .filter(e -> pageOrderCodes.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                    : buildReceiptNumbersByOrderCode(pageOrderCodes);
            List<Map<String, Object>> content = paged.stream()
                    .map(o -> _toOrderMap(o, receiptsForPage.getOrDefault(o.getOrderCode(), List.of())))
                    .toList();

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

    // ── Receipt number helpers (liên kết đơn hàng ↔ phiếu thu) ─────────────────
    @SuppressWarnings("unchecked")
    private List<String> parseOrderCodes(String linkedOrderCodesJson) {
        if (linkedOrderCodesJson == null || linkedOrderCodesJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(linkedOrderCodesJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Với 1 tập orderCode, trả về map orderCode -> danh sách receiptNumber của
     * các phiếu thu có liên kết tới đơn đó (1 đơn có thể được thu nhiều lần →
     * nhiều phiếu thu).
     */
    private Map<String, List<String>> buildReceiptNumbersByOrderCode(Set<String> orderCodes) {
        if (orderCodes == null || orderCodes.isEmpty()) return Map.of();
        Map<String, List<String>> result = new HashMap<>();
        for (IncomeVoucher v : incomeVoucherRepository.findAllWithLinkedOrders()) {
            if (v.getReceiptNumber() == null || v.getReceiptNumber().isBlank()) continue;
            for (String code : parseOrderCodes(v.getLinkedOrderCodes())) {
                if (orderCodes.contains(code)) {
                    result.computeIfAbsent(code, k -> new ArrayList<>()).add(v.getReceiptNumber());
                }
            }
        }
        return result;
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

    /**
     * TÁCH SỐ TIỀN từ ô tìm kiếm — hỗ trợ định dạng người dùng thật sự gõ.
     *
     * <pre>
     *   "1.972.000"      → 1972000    (dấu chấm = phân cách nghìn, chuẩn VN)
     *   "1.972.000 đ"    → 1972000    (bỏ ký hiệu tiền tệ)
     *   "1972000"        → 1972000
     *   "1972000.40"     → 1972000    (nhóm cuối 1–2 số ⇒ dấu chấm là thập phân)
     *   "1.972.000,40"   → 1972000    (dấu phẩy = thập phân, chuẩn VN)
     *   "NCC-2024"       → null       (có chữ ⇒ không phải số tiền)
     * </pre>
     *
     * <p>Quy tắc phân biệt dấu chấm là NGHÌN hay THẬP PHÂN: nhìn nhóm chữ số
     * cuối cùng — đúng 3 chữ số thì coi là phân cách nghìn, 1–2 chữ số thì coi
     * là phần thập phân. Đây là cách duy nhất đoán đúng cả "1.972.000" lẫn
     * "1972000.40" khi hệ thống nhận cả 2 kiểu gõ.
     *
     * @return phần ĐỒNG của số tiền, hoặc {@code null} nếu keyword không phải số
     */
    private static BigDecimal parseAmountKeyword(String raw) {
        if (raw == null) return null;

        String s = raw.trim().toLowerCase();
        s = s.replaceAll("\\s*(vnđ|vnd|₫|đ)\\s*$", "");   // bỏ hậu tố tiền tệ
        s = s.replaceAll("\\s+", "");                     // bỏ mọi khoảng trắng
        if (s.isEmpty()) return null;

        // Chỉ chấp nhận chuỗi gồm chữ số và dấu phân cách, và phải có ít nhất 1 chữ số
        if (!s.matches("[0-9.,]+") || !s.matches(".*[0-9].*")) return null;

        String intPart;
        int comma = s.indexOf(',');
        if (comma >= 0) {
            intPart = s.substring(0, comma);            // dấu phẩy = thập phân
        } else {
            int lastDot = s.lastIndexOf('.');
            if (lastDot < 0) {
                intPart = s;
            } else {
                String tail = s.substring(lastDot + 1);
                // 3 chữ số ⇒ phân cách nghìn (giữ cả chuỗi); 1–2 chữ số ⇒ thập phân
                intPart = (tail.length() == 3) ? s : s.substring(0, lastDot);
            }
        }
        intPart = intPart.replace(".", "").replace(",", "");
        if (intPart.isEmpty()) return null;

        try {
            return new BigDecimal(intPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * SO KHỚP SỐ TIỀN ĐƠN HÀNG theo phần ĐỒNG, bỏ qua số lẻ thập phân.
     *
     * <p>Tìm {@code 1.972.000} sẽ khớp cả đơn có {@code finalAmount = 1972000}
     * lẫn {@code 1972000.40} — đúng như số tiền hiển thị trên màn hình (giao
     * diện luôn làm tròn về đồng).
     *
     * <p>Chấp nhận cả FLOOR và HALF_UP để không bỏ sót trường hợp biên: đơn
     * {@code 1971999.60} hiển thị là 1.972.000đ nên tìm 1.972.000 vẫn ra.
     */
    private static boolean matchesAmount(BigDecimal amount, BigDecimal target) {
        if (amount == null || target == null) return false;
        return amount.setScale(0, RoundingMode.FLOOR).compareTo(target) == 0
                || amount.setScale(0, RoundingMode.HALF_UP).compareTo(target) == 0;
    }

    /**
     * THU TIỀN TRƯỚC KHI GIAO — đơn còn ở PENDING/CONFIRMED/PREPARING/READY.
     *
     * <p>Khác {@code /partial-payment}: KHÔNG đổi trạng thái đơn. Đơn vẫn ở "Đang chuẩn
     * bị" để kho tiếp tục soạn hàng; chỉ {@code paymentStatus} chuyển sang PAID, và đó
     * là điều kiện để kho được bấm "Bắt đầu giao".
     *
     * <p>Bắt buộc thu ĐỦ trong một lần. Khách trả thiếu do làm tròn thì gửi
     * {@code waiveRemainder = true} (trần 50.000đ, kiểm ở service).
     *
     * <p>Chỉ kế toán được gọi — kể cả OWNER/ADMIN cũng không, theo phân công hiện tại:
     * phiếu thu là chứng từ của kế toán, mở rộng cho vai trò khác sẽ làm mất dấu ai là
     * người chịu trách nhiệm đối soát.
     */
    @PatchMapping("/orders/{id}/prepayment")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT')")
    public ResponseEntity<ApiResponse<Object>> recordPrepayment(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        try {
            Object amtRaw = body.get("paidAmount");
            if (amtRaw == null)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Thiếu trường paidAmount"));

            BigDecimal paidAmount = new BigDecimal(amtRaw.toString());
            boolean waiveRemainder = Boolean.TRUE.equals(body.get("waiveRemainder"));
            String paymentMethod = body.get("paymentMethod") instanceof String s ? s : null;
            String bankName      = body.get("bankName") instanceof String s ? s : null;
            String txRef         = body.get("transactionRef") instanceof String s ? s : null;

            User actor = (User) auth.getPrincipal();
            orderService.recordPrepayment(id, paidAmount, waiveRemainder, getActorName(auth),
                    paymentMethod, bankName, txRef, actor.getId());
            return ResponseEntity.ok(ApiResponse.success(null, "Đã ghi nhận thu tiền trước — kho có thể giao hàng"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] recordPrepayment error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
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
                    getActorRole(auth, user), reason);
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
            @RequestParam(required = false) String status,   // ACTIVE | LOCKED (null = tất cả)
            @RequestParam(required = false) String sort,      // "debtAsc" = công nợ tăng dần
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            // LẤY CẢ KHÁCH ĐÃ KHOÁ, chỉ loại khách ĐÃ XOÁ.
            //
            //   Kế toán vẫn phải tra cứu và thu hồi công nợ của khách bị khoá —
            //   khoá là ngừng BÁN HÀNG, không phải xoá khỏi sổ. Lọc theo
            //   isActive = true khiến tìm "The Hill" không ra gì trong khi
            //   OWNER/SELLER vẫn thấy, và công nợ của họ biến mất khỏi màn hình.
            //
            //   Đổi sang mốc deletedAt còn VÁ một lỗ rò có sẵn: softDelete chỉ set
            //   deletedAt chứ KHÔNG set isActive = false, nên
            //   findByIsActiveTrue... trước đây vẫn trả về khách đã xoá mềm.
            List<Customer> all = customerRepository
                    .findAllByDeletedAtIsNullOrderByCustomerCodeAscNameAsc();

            if (q != null && !q.isBlank()) {
                String lower = q.toLowerCase();
                all = all.stream().filter(c ->
                        (c.getName()         != null && c.getName().toLowerCase().contains(lower))        ||
                                (c.getCompanyName()  != null && c.getCompanyName().toLowerCase().contains(lower)) ||
                                (c.getPhone()        != null && c.getPhone().contains(lower))                     ||
                                (c.getCustomerCode() != null && c.getCustomerCode().toLowerCase().contains(lower))
                ).toList();
            }

            // ── Lọc theo trạng thái khoá/hoạt động ──────────────────────────────
            //   LOCKED = đang khoá (isActive == false)
            //   ACTIVE = đang hoạt động (isActive khác false, coi null như đang hoạt động)
            //   null/blank = tất cả
            if (status != null && !status.isBlank()) {
                String st = status.trim().toUpperCase();
                if ("LOCKED".equals(st)) {
                    all = all.stream()
                            .filter(c -> Boolean.FALSE.equals(c.getIsActive()))
                            .toList();
                } else if ("ACTIVE".equals(st)) {
                    all = all.stream()
                            .filter(c -> !Boolean.FALSE.equals(c.getIsActive()))
                            .toList();
                }
            }

            // ── Công nợ + số ngày công nợ đơn cũ nhất — tính theo lô (1 query đơn) ──
            // Công nợ = đơn PENDING_PAYMENT có paymentStatus ∈ {UNPAID, PARTIAL}.
            //   UNPAID  → finalAmount ; PARTIAL → finalAmount − paidAmount.
            //   Làm tròn LÊN từng đơn TRƯỚC khi cộng.
            List<Long> custIds = all.stream().map(Customer::getId).toList();
            List<Order> debtOrders = custIds.isEmpty()
                    ? List.of()
                    : orderRepository.findByCustomerIdIn(custIds);

            Map<Long, Long> debtMap   = new HashMap<>(); // customerId → tổng công nợ
            Map<Long, Long> oldestMap = new HashMap<>(); // customerId → createdAt đơn công nợ cũ nhất

            for (Order o : debtOrders) {
                if (o.getCustomer() == null || o.getCustomer().getId() == null) continue;

                // Chỉ tính đơn PENDING_PAYMENT với UNPAID hoặc PARTIAL
                if (o.getStatus() == OrderStatus.PENDING_PAYMENT) {
                    PaymentStatus ps = o.getPaymentStatus();
                    if (ps == PaymentStatus.UNPAID || ps == PaymentStatus.PARTIAL) {
                        long v = unpaidDebtOf(o);
                        if (v > 0) {
                            Long cid = o.getCustomer().getId();
                            debtMap.merge(cid, v, Long::sum);
                            if (o.getCreatedAt() != null) {
                                oldestMap.merge(cid, o.getCreatedAt(), Math::min);
                            }
                        }
                    }
                }
            }

            LocalDate today = LocalDate.now(VN);

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

                // Cột "Công nợ": tổng công nợ chưa thanh toán (đã làm tròn)
                m.put("unpaidDebt", debtMap.getOrDefault(c.getId(), 0L));

                // Cột "Note": số ngày công nợ của đơn công nợ cũ nhất (tới hôm nay)
                Long oldest = oldestMap.get(c.getId());
                Integer oldestDebtDays = null;
                if (oldest != null) {
                    LocalDate created = Instant.ofEpochMilli(oldest).atZone(VN).toLocalDate();
                    oldestDebtDays = (int) Math.max(0, ChronoUnit.DAYS.between(created, today));
                }
                m.put("oldestDebtDays", oldestDebtDays);
                return m;
            }).toList();

            // ── Sắp xếp theo công nợ tăng dần (nếu yêu cầu) ─────────────────────
            //   Vẫn giữ nguyên trạng thái đang lọc ở trên (status), vì sort chỉ
            //   sắp xếp lại danh sách ĐÃ lọc chứ không đụng tới bộ lọc.
            if (sort != null && ("debtAsc".equalsIgnoreCase(sort.trim())
                    || "debt_asc".equalsIgnoreCase(sort.trim()))) {
                content = content.stream()
                        .sorted(java.util.Comparator.comparingLong(
                                m -> toLongSafe(m.get("unpaidDebt"))))
                        .toList();
            }

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

            List<Order> orders = orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);

            // ── Thống kê ──────────────────────────────────────────────────────────
            long totalOrders = orders.size();

            // Đơn hoàn thành: status = COMPLETED VÀ paymentStatus = PAID
            long completedOrders = orders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.COMPLETED
                            && o.getPaymentStatus() == PaymentStatus.PAID)
                    .count();

            long activeOrders = orders.stream().filter(o ->
                    o.getStatus() != OrderStatus.COMPLETED &&
                            o.getStatus() != OrderStatus.CANCELLED &&
                            o.getStatus() != OrderStatus.FAILED).count();

            BigDecimal totalAmount = orders.stream().map(Order::getFinalAmount)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal completedAmount = orders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.COMPLETED
                            && o.getPaymentStatus() == PaymentStatus.PAID)
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
                m.put("paidAmount",    o.getPaidAmount() != null ? o.getPaidAmount() : BigDecimal.ZERO);
                m.put("orderedByName", o.getOrderedByName());
                m.put("createdAt",     o.getCreatedAt());

                // ── Lấy danh sách phiếu thu ──────────────────────────────────────
                // Sử dụng method receiptNumbersOf đã có
                List<String> receiptNumbers = receiptNumbersOf(o);
                m.put("receiptNumbers", receiptNumbers);

                // Nếu có receiptNumbers thì lấy paymentStatus để biết màu
                if (!receiptNumbers.isEmpty()) {
                    m.put("receiptPaymentStatus", o.getPaymentStatus().name());
                } else {
                    m.put("receiptPaymentStatus", null);
                }

                // ── Hạn thanh toán ───────────────────────────────────────────────
                int debtDays = o.getDebtDays();
                if (debtDays > 0 && "DEBT".equals(o.getPaymentMethod())
                        && o.getStatus() == OrderStatus.PENDING_PAYMENT
                        && o.getPendingPaymentAt() != null) {
                    LocalDate base = Instant.ofEpochMilli(o.getPendingPaymentAt())
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

    /**
     * Công nợ chưa thanh toán của 1 đơn (đã làm tròn LÊN tới đồng).
     * Chỉ tính đơn ĐÃ GIAO chờ thu: status == PENDING_PAYMENT và
     * paymentStatus ∈ {UNPAID, PARTIAL}. UNPAID → finalAmount ;
     * PARTIAL → finalAmount − paidAmount.
     */
    private static long unpaidDebtOf(Order o) {
        if (o == null || o.getStatus() != OrderStatus.PENDING_PAYMENT) return 0L;
        PaymentStatus ps = o.getPaymentStatus();
        if (ps != PaymentStatus.UNPAID && ps != PaymentStatus.PARTIAL) return 0L;

        BigDecimal fin  = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal paid = o.getPaidAmount()  != null ? o.getPaidAmount()  : BigDecimal.ZERO;
        BigDecimal amt  = (ps == PaymentStatus.PARTIAL) ? fin.subtract(paid) : fin;

        if (amt.signum() <= 0) return 0L;
        return amt.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /** Đọc an toàn 1 giá trị số (Long/Integer/BigDecimal...) về long; null → 0. */
    private static long toLongSafe(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return 0L; }
    }

    /**
     * Số phiếu thu (receiptNumber) của các IncomeVoucher liên kết tới đơn hàng.
     * Chỉ trả khi đơn PAID hoặc PARTIAL; frontend tô màu theo paymentStatus
     * (PAID → xanh lá, PARTIAL → xanh dương).
     */
    private List<String> receiptNumbersOf(Order o) {
        PaymentStatus ps = o.getPaymentStatus();
        if (ps != PaymentStatus.PAID && ps != PaymentStatus.PARTIAL) return List.of();
        if (o.getOrderCode() == null || o.getOrderCode().isBlank()) return List.of();
        return incomeVoucherRepository.findByLinkedOrderCode(o.getOrderCode()).stream()
                .map(IncomeVoucher::getReceiptNumber)
                .filter(rn -> rn != null && !rn.isBlank())
                .distinct()
                .sorted()
                .toList();
    }
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

        // Phần khách trả dư + phiếu chi hoàn (nếu đã lập) — cho FE hiện nút hoàn dư.
        BigDecimal overpaid = o.getOverpaidAmount() != null
                ? o.getOverpaidAmount().setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        m.put("overpaidAmount",             overpaid);
        m.put("overpaidRefundVoucherCode",  o.getOverpaidRefundVoucherCode());

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

        // Cờ cho FE: đơn này bắt buộc thu tiền TRƯỚC khi giao?
        boolean prepay = orderServiceImpl.isPrepaymentRequired(o);
        boolean preDelivery = com.nhatnam.server.service.serviceimpl.OrderServiceImpl.isPreDelivery(o);
        m.put("requirePrepayment", prepay);
        // true → phiếu thu chỉ ghi nhận ĐÃ THU, KHÔNG chuyển đơn sang Hoàn thành
        m.put("prepaymentOrder",   prepay && preDelivery);
        return m;
    }

    private Map<String, Object> _toOrderMap(Order o) {
        return _toOrderMap(o, buildReceiptNumbersByOrderCode(Set.of(o.getOrderCode())).getOrDefault(o.getOrderCode(), List.of()));
    }

    private Map<String, Object> _toOrderMap(Order o, List<String> receiptNumbers) {
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
        m.put("receiptNumbers", receiptNumbers);
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

    /**
     * Thứ tự ưu tiên khi tài khoản kiêm nhiều role mà JWT KHÔNG có claim
     * {@code selected_role} (authorities lúc đó chứa tất cả role của user).
     */
    private static final List<Role> ACTOR_ROLE_PRIORITY = List.of(
            Role.SUPER_ACCOUNTANT, Role.ACCOUNTANT,
            Role.SUPER_SELLER,     Role.SELLER,
            Role.SUPER_WAREHOUSE,  Role.WAREHOUSE,
            Role.OWNER, Role.ADMIN, Role.SUPERADMIN);

    /**
     * Role ĐANG THAO TÁC của request — đọc từ authorities của JWT (tôn trọng
     * claim {@code selected_role} khi tài khoản có nhiều vai trò).
     *
     * <p>KHÔNG dùng {@code user.getRole()}: cột {@code _user.role} có thể NULL
     * với tài khoản multi-role (role thật nằm ở bảng {@code _user_roles}), gây
     * NPE và routing thông báo sai vai trò.</p>
     */
    private String getActorRole(Authentication auth, User user) {
        Set<Role> acting = AuthRoleUtil.rolesOf(auth);
        if (!acting.isEmpty()) {
            if (acting.size() == 1) return acting.iterator().next().name();
            for (Role r : ACTOR_ROLE_PRIORITY) if (acting.contains(r)) return r.name();
            return acting.iterator().next().name();
        }
        // Fallback khi authorities rỗng (token cũ / role lạ)
        if (user != null) {
            if (user.getRole() != null) return user.getRole().name();
            Set<Role> owned = user.getAllRoles();
            for (Role r : ACTOR_ROLE_PRIORITY) if (owned.contains(r)) return r.name();
            if (!owned.isEmpty()) return owned.iterator().next().name();
        }
        return "UNKNOWN";
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