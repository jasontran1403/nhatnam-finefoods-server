package com.nhatnam.server.restcontroller;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.InvoiceDTO;
import com.nhatnam.server.dto.WarehouseDTO.*;
import com.nhatnam.server.dto.driver.DriverOdometerReportDto;
import com.nhatnam.server.dto.request.CreateIngredientRequest;
import com.nhatnam.server.dto.response.*;
import com.nhatnam.server.entity.Driver;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.*;
import com.nhatnam.server.service.serviceimpl.WarehouseService;
import com.nhatnam.server.utils.InvoicePdf;
import com.nhatnam.server.utils.TransportSlipPdf;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
@Log4j2
public class WarehouseController {

    private final WarehouseService warehouseService;
    /**
     * Quy tắc "đơn này có phải thu tiền trước không" nằm ở impl chứ không trên interface
     * OrderService, và giờ cần AddressCatalogService để tra vùng COD — nên không còn static.
     */
    private final com.nhatnam.server.service.serviceimpl.OrderServiceImpl orderServiceImpl;
    private final TransportSlipPdf transportSlipPdf;
    /** Báo cáo PDF chi tiết một phiếu kho — dùng cho nút "Xuất báo cáo" ở trang Lịch sử. */
    private final com.nhatnam.server.utils.ReceiptReportPdf receiptReportPdf;
    private final InvoicePdf invoicePdf;
    private final DriverRepository driverRepository;
    private final com.nhatnam.server.service.DriverUserSyncService driverUserSyncService;
    private final com.nhatnam.server.repository.DriverAttendanceRepository driverAttendanceRepository;
    private final FileStorageService fileStorageService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final DriverOdometerReportService service;
    private final WarehouseInventoryExportService exportService;
    private final com.nhatnam.server.service.IngredientWarehouseService ingredientWarehouseService;

    @GetMapping("/driver-odometer")
    public ResponseEntity<ApiResponse<List<DriverOdometerReportDto>>> report(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    service.report(from, to, includeInactive), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[DRIVER_ODO] report error from={} to={}", from, to, e);
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.INTERNAL_SERVER_ERROR, "Không tải được báo cáo ODO"));
        }
    }

    @PostMapping("/export-inventory-check")
    public ResponseEntity<?> exportInventoryCheck(
            @RequestBody ExportInventoryRequest req,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.status(401).build();

            // Luôn dùng thời điểm hiện tại làm ngày kiểm kho
            LocalDateTime checkDateTime = LocalDateTime.now();

            // Chỉ giữ nguyên liệu CÒN được gán cho kho đang chọn.
            //   Sau khi gỡ nguyên liệu khỏi kho, bảng gán (ingredient_warehouse)
            //   đã xoá mapping nhưng dòng tồn (IngredientStock) vẫn còn, khiến
            //   nguyên liệu vẫn lọt vào phiếu. Lọc lại theo assignment ở đây để
            //   đảm bảo đúng dù client gửi gì. Item không kèm ingredientId sẽ
            //   được giữ (tương thích ngược với client cũ).
            List<ItemRequest> srcItems = req.items() != null ? req.items() : List.of();
            if (req.warehouseId() != null) {
                java.util.Set<Long> assignedIds = new java.util.HashSet<>(
                        ingredientWarehouseService.getIngredientIdsByWarehouse(req.warehouseId()));
                srcItems = srcItems.stream()
                        .filter(it -> it.ingredientId() == null || assignedIds.contains(it.ingredientId()))
                        .toList();
            }

            // Map request DTOs → service DTOs
            List<WarehouseInventoryExportService.InventoryItem> items = srcItems.stream().map(it ->
                    new WarehouseInventoryExportService.InventoryItem(
                            it.ingredientName(),
                            it.spec(),
                            it.unit(),
                            it.stockQuantity(),
                            it.expiryList() == null ? List.of()
                                    : it.expiryList().stream().map(e ->
                                    new WarehouseInventoryExportService.ExpiryEntry(
                                            e.manufacturingDate(),
                                            e.expiryDate(),
                                            e.quantity()
                                    )
                            ).toList(),
                            it.categoryId(),
                            it.categoryName(),
                            it.subCategoryId(),
                            it.subCategoryName()
                    )
            ).toList();

            byte[] excelBytes = exportService.generate(req.warehouseName(), checkDateTime, items);

            String safeDate = checkDateTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm"));
            String filename  = "phieu-kiem-kho_" + safeDate + ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(excelBytes);

        } catch (Exception e) {
            log.error("[WAREHOUSE] exportInventoryCheck error", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Request records ─────────────────────────────────────────────────────

    public record ExportInventoryRequest(
            Long   warehouseId,
            String warehouseName,
            List<ItemRequest> items
    ) {}

    public record ItemRequest(
            Long   ingredientId,
            String ingredientName,
            String spec,
            String unit,
            Double stockQuantity,
            List<ExpiryRequest> expiryList,
            Long   categoryId,
            String categoryName,
            Long   subCategoryId,
            String subCategoryName
    ) {}

    public record ExpiryRequest(
            String manufacturingDate,
            String expiryDate,
            Double quantity           // SL của lô này — từ IngredientExpiry.quantity
    ) {}

    @GetMapping("/orders/{id}/detail")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderDetail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(orderService.getOrderById(id), "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @PatchMapping(value = "/orders/{id}/receipt-file", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Object>> uploadReceiptFile(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        try {
            if (file == null || file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "File không được rỗng"));

            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + id));

            String savedPath = fileStorageService.saveReceiptFile(file);
            order.setReceiptFileUrl(savedPath);
            order.setUpdatedAt(System.currentTimeMillis());
            orderRepository.save(order);

            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("receiptFileUrl", savedPath),
                    "Đã cập nhật chứng từ nhận hàng"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("[WAREHOUSE] uploadReceiptFile id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{id}/confirm-delivered")
    public ResponseEntity<?> confirmDelivered(@PathVariable Long id, Authentication auth) {
        try {
            User actor = (User) auth.getPrincipal();
            String actorName = getActorName(auth);

            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + id));

            if (order.getStatus() != OrderStatus.DELIVERING) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Chỉ được xác nhận đã giao khi đơn đang ở trạng thái Đang giao"));
            }

            // markAsPendingPayment: DELIVERING → PENDING_PAYMENT.
            // NGOẠI LỆ, service tự chuyển thẳng sang COMPLETED khi:
            //   1. Đơn ĐÃ THU ĐỦ TIỀN từ trước (thanh toán bắt buộc trước).
            //   2. Đơn 0đ (toàn khuyến mãi hoặc phí = 0) — không có gì để chờ thu.
            var result = orderService.markAsPendingPayment(id, actorName, actor.getId());

            boolean completed = result != null
                    && OrderStatus.COMPLETED.name().equals(String.valueOf(result.getStatus()));

            // TRẢ VỀ TRẠNG THÁI THẬT thay vì null.
            //
            // Đơn được service chuyển thẳng sang COMPLETED (thu đủ trước hoặc 0đ),
            // nhưng FE trước đây tự gán cứng PENDING_PAYMENT nên hiển thị sai cho tới
            // khi F5. Có dữ liệu trả về thì FE dùng đúng cái server quyết định.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id",            result != null ? result.getId() : id);
            data.put("status",        result != null ? result.getStatus() : null);
            data.put("paymentStatus", result != null ? result.getPaymentStatus() : null);
            data.put("paidAmount",    result != null ? result.getPaidAmount() : null);
            data.put("completed",     completed);

            return ResponseEntity.ok(ApiResponse.success(data, completed
                    ? "Đã xác nhận giao hàng — đơn không cần chờ thanh toán nên được HOÀN THÀNH luôn"
                    : "Đã xác nhận giao hàng thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[WAREHOUSE] confirmDelivered id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/orders/delivery")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDeliveryOrders(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String keyword,
            Authentication authentication) {
        try {
            java.time.ZoneId VN = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

            // ── Tính khoảng thời gian ─────────────────────────────────────────
            // Nếu có keyword → bỏ qua filter ngày, tìm toàn bộ
            final boolean hasKeyword = keyword != null && !keyword.isBlank();

            List<Order> orders;

            if (hasKeyword) {
                // Search toàn bộ, không giới hạn ngày
                orders = orderRepository.findAllByOrderByCreatedAtDesc();
            } else {
                final long fromMs;
                final long toMs;

                if (from != null && to != null) {
                    java.time.LocalDate fromDate = java.time.Instant.ofEpochMilli(from)
                            .atZone(VN).toLocalDate();
                    java.time.LocalDate toDate = java.time.Instant.ofEpochMilli(to)
                            .atZone(VN).toLocalDate();
                    fromMs = fromDate.atStartOfDay(VN).toInstant().toEpochMilli();
                    toMs   = toDate.atTime(23, 59, 59, 999_000_000).atZone(VN).toInstant().toEpochMilli();
                } else {
                    java.time.LocalDate today = java.time.LocalDate.now(VN);
                    fromMs = today.atStartOfDay(VN).toInstant().toEpochMilli();
                    toMs   = today.atTime(23, 59, 59, 999_000_000).atZone(VN).toInstant().toEpochMilli();
                }

                orders = orderRepository.findByCreatedAtBetween(fromMs, toMs);
            }

            // ── Lấy danh sách kho được phân công ─────────────────────────────
            User currentUser = (User) authentication.getPrincipal();
            User fullUser = userRepository.findById(currentUser.getId()).orElse(null);

            final java.util.Set<Long> allowedWarehouseIds;
            if (fullUser == null) {
                allowedWarehouseIds = java.util.Collections.emptySet();
            } else {
                String role = fullUser.getRole() != null ? fullUser.getRole().name() : "";
                boolean isAdminLevel = "ADMIN".equals(role) || "OWNER".equals(role) || "SUPERADMIN".equals(role);
                allowedWarehouseIds = isAdminLevel ? null : fullUser.getAllWarehouses().stream()
                        .map(com.nhatnam.server.entity.Warehouse::getId)
                        .collect(java.util.stream.Collectors.toSet());
            }

            orders = orders.stream()
                    // Filter kho
                    .filter(o -> {
                        if (allowedWarehouseIds == null) return true;
                        if (allowedWarehouseIds.isEmpty()) return false;
                        return o.getWarehouseId() != null && allowedWarehouseIds.contains(o.getWarehouseId());
                    })
                    // Filter keyword
                    .filter(o -> {
                        if (!hasKeyword) return true;
                        String kw = removeAccents(keyword.toLowerCase().trim());
                        return matchesKeyword(o.getOrderCode(),     kw)
                                || matchesKeyword(o.getCustomerName(),  kw)
                                || matchesKeyword(o.getCustomerPhone(), kw)
                                || matchesKeyword(o.getOrderedByName(), kw)
                                || (o.getUser() != null && (
                                matchesKeyword(o.getUser().getFullName(), kw)
                                        || matchesKeyword(o.getUser().getUsername(), kw)));
                    })
                    .sorted(java.util.Comparator.comparingLong(
                            o -> o.getCreatedAt() != null ? -o.getCreatedAt() : 0L))
                    .collect(Collectors.toList());

            // ── Map sang response ──────────────────────────────────────────────
            List<Map<String, Object>> result = orders.stream().map(o -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id",             o.getId());
                m.put("orderCode",      o.getOrderCode());
                m.put("customerName",   o.getCustomerName() != null && !o.getCustomerName().isBlank()
                        ? o.getCustomerName() : "Khách lẻ/Khách vãng lai");
                m.put("customerPhone",  o.getCustomerPhone());
                m.put("warehouseName",  o.getWarehouseName());
                m.put("warehouseId",    o.getWarehouseId());
                m.put("status",         o.getStatus());
                m.put("paymentStatus",  o.getPaymentStatus());
                m.put("paymentMethod",  o.getPaymentMethod());
                m.put("finalAmount",    o.getFinalAmount());
                m.put("notes",          o.getNotes());
                m.put("orderedByName",  o.getOrderedByName());
                m.put("deliveryAddress",  o.getDeliveryAddress());
                m.put("shippingAddress",  o.getShippingAddress());
                m.put("provinceName",     o.getProvinceName());
                m.put("wardName",         o.getWardName());
                m.put("receiverName",     o.getReceiverName());
                m.put("deliveryDatetime", o.getDeliveryDatetime());
                m.put("createdByName",  o.getUser() != null
                        ? (o.getUser().getFullName() != null && !o.getUser().getFullName().isBlank()
                        ? o.getUser().getFullName() : o.getUser().getUsername())
                        : null);
                m.put("createdAt",      o.getCreatedAt());
                m.put("receiptFileUrl", o.getReceiptFileUrl());
                m.put("debtDays",       o.getDebtDays());
                m.put("paidAmount",     o.getPaidAmount() != null
                        ? o.getPaidAmount() : java.math.BigDecimal.ZERO);

                // ── YÊU CẦU THANH TOÁN TRƯỚC ─────────────────────────────────
                boolean prepay2 = orderServiceImpl.isPrepaymentRequired(o);
                java.math.BigDecimal fin2  = o.getFinalAmount() != null
                        ? o.getFinalAmount() : java.math.BigDecimal.ZERO;
                java.math.BigDecimal paid2 = o.getPaidAmount() != null
                        ? o.getPaidAmount() : java.math.BigDecimal.ZERO;
                boolean fullyPaid2 = o.getPaymentStatus() == com.nhatnam.server.enumtype.PaymentStatus.PAID
                        || paid2.compareTo(fin2.subtract(java.math.BigDecimal.ONE)) >= 0;
                m.put("requirePrepayment", prepay2);
                m.put("remainingAmount",   fin2.subtract(paid2).max(java.math.BigDecimal.ZERO)
                        .setScale(0, java.math.RoundingMode.HALF_UP));
                // false → FE khoá nút "Bắt đầu giao hàng"
                m.put("canDeliver",        !prepay2 || fullyPaid2);
                return m;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));

        } catch (Exception e) {
            log.error("[WAREHOUSE] getDeliveryOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private static String removeAccents(String s) {
        if (s == null) return "";
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("đ", "d").replaceAll("Đ", "D");
    }

    /** So sánh keyword không dấu với field */
    private static boolean matchesKeyword(String field, String kwNoAccent) {
        if (field == null || field.isBlank()) return false;
        String fieldNoAccent = removeAccents(field.toLowerCase());
        return fieldNoAccent.contains(kwNoAccent);
    }

    // ── Categories (dùng cho warehouse tree view) ─────────────────────────────
    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.success(categoryService.getAllCategories(), "OK"));
    }

    @GetMapping("/subcategories")
    public ResponseEntity<ApiResponse<List<SubCategoryResponse>>> getSubCategories() {
        return ResponseEntity.ok(ApiResponse.success(subCategoryService.getAll(), "OK"));
    }

    // ── Kho ──────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<WarehouseResponse>> getAllWarehouses() {
        return ResponseEntity.ok(warehouseService.getAllWarehouses());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WarehouseResponse> createWarehouse(
            @RequestBody CreateWarehouseRequest req) {
        return ResponseEntity.ok(warehouseService.createWarehouse(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<WarehouseResponse> updateWarehouse(
            @PathVariable Long id,
            @RequestBody CreateWarehouseRequest req) {
        return ResponseEntity.ok(warehouseService.updateWarehouse(id, req));
    }

    /**
     * Danh mục nguyên liệu.
     *
     * <p>Truyền {@code warehouseId} để chỉ lấy nguyên liệu ĐÃ GÁN cho kho đó —
     * màn Quản lý kho dùng tham số này. Bỏ trống thì vẫn trả toàn bộ danh mục
     * như trước, để các màn dùng chung (chọn nguyên liệu khi tạo phiếu, đối chiếu
     * công thức…) không bị đổi hành vi.
     */
    @GetMapping("/all-ingredients")
    public ResponseEntity<ApiResponse<List<IngredientResponse>>> getAllIngredients(
            @RequestParam(required = false) Long warehouseId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    ingredientService.getAllIngredientsOfWarehouse(warehouseId),
                    "Ingredients retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get ingredients", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private final IngredientService ingredientService;

    @GetMapping("/ingredients")
    public ResponseEntity<ApiResponse<List<IngredientResponse>>> getPaginationIngredients(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    ingredientService.getPaginationIngredients(page, size),
                    "Ingredients retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get ingredients", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<IngredientResponse>> getIngredientById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    ingredientService.getIngredientById(id), "Ingredient retrieved successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to get ingredient ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/ingredients")
    public ResponseEntity<ApiResponse<IngredientResponse>> createIngredient(
            @Valid @RequestBody CreateIngredientRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    ingredientService.createIngredient(request), "Ingredient created successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to create ingredient", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Tồn kho ─────────────────────────────────────────────────────────────

    @GetMapping("/{warehouseId}/stock")
    public ResponseEntity<List<StockResponse>> getStock(@PathVariable Long warehouseId) {
        return ResponseEntity.ok(warehouseService.getStockByWarehouse(warehouseId));
    }

    // ── Nhập kho ─────────────────────────────────────────────────────────────

    @PostMapping("/import")
    public ResponseEntity<ReceiptResponse> importStock(
            @RequestBody ImportRequest req,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(warehouseService.importStock(req, user.getId()));
    }

    // ── Xuất kho ─────────────────────────────────────────────────────────────

    @PostMapping("/export")
    public ResponseEntity<ReceiptResponse> exportStock(
            @RequestBody ExportRequest req,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(warehouseService.exportStock(req, user.getId()));
    }

    // ── Điều chỉnh ───────────────────────────────────────────────────────────

    @PostMapping("/adjust")
    public ResponseEntity<ReceiptResponse> adjustStock(
            @RequestBody AdjustRequest req,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();

        // Whitelist tài khoản được phép điều chỉnh tồn kho.
        // Điều chỉnh tồn theo lô ảnh hưởng trực tiếp giá vốn + HSD → chỉ một số
        // tài khoản đặc biệt được phép. Danh sách này có thể mở rộng về sau.
        final Set<String> ADJUST_ALLOWED_USERNAMES = Set.of("nguyenhai");

        String username = user.getUsername();

        if (username == null || !ADJUST_ALLOWED_USERNAMES.contains(username)) {
            // Ném lỗi nghiệp vụ → GlobalExceptionHandler trả JSON có message
            // → Frontend catch và hiển thị toast "Bạn không được phép..."
            throw new BusinessException(
                    "Bạn không được phép thực hiện chức năng điều chỉnh tồn kho");
        }

        return ResponseEntity.ok(warehouseService.adjustStock(req, user.getId()));
    }

    // ── Chuyển kho ───────────────────────────────────────────────────────────

    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> transferStock(
            @RequestBody TransferRequest req,
            Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(warehouseService.transferStock(req, user.getId()));
    }

    // ── Mục 4.2: Chuyển kho sang KHO SẢN XUẤT (xưởng) ─────────────────────────
    @GetMapping("/{warehouseId}/registered-ingredient-ids")
    public ResponseEntity<java.util.List<Long>> registeredIngredientIds(@PathVariable Long warehouseId) {
        return ResponseEntity.ok(warehouseService.getRegisteredIngredientIds(warehouseId));
    }

    @GetMapping("/production-factories")
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> listProductionFactories() {
        return ResponseEntity.ok(warehouseService.listProductionFactoriesForTransfer());
    }

    @GetMapping("/production-factories/{id}/material-names")
    public ResponseEntity<java.util.List<String>> factoryMaterialNames(@PathVariable Long id) {
        return ResponseEntity.ok(warehouseService.getFactoryMaterialNames(id));
    }

    // Mục 1 — chuyển kho hàng → kho THÀNH PHẨM xưởng
    @GetMapping("/finished-goods-factories")
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> listFinishedGoodsFactories() {
        return ResponseEntity.ok(warehouseService.listFinishedGoodsFactoriesForTransfer());
    }

    @GetMapping("/finished-goods-factories/{id}/product-names")
    public ResponseEntity<java.util.List<String>> finishedGoodsProductNames(@PathVariable Long id) {
        return ResponseEntity.ok(warehouseService.getFinishedGoodsProductNames(id));
    }

    // ── Lịch sử ──────────────────────────────────────────────────────────────
    // FIX #4: Lọc lịch sử theo warehouseId của user nếu không phải ADMIN/OWNER
    @GetMapping("/history")
    public ResponseEntity<Page<ReceiptResponse>> getHistory(
            @RequestParam String tab,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        // Nếu là WAREHOUSE user → chỉ xem kho của mình
        Long effectiveWarehouseId = resolveWarehouseIdForUser(authentication, warehouseId);

        List<ReceiptType> types = switch (tab.toUpperCase()) {
            case "IMPORT"   -> List.of(ReceiptType.IMPORT);
            case "EXPORT"   -> List.of(ReceiptType.EXPORT_ORDER, ReceiptType.EXPORT_OTHER);
            case "ADJUST"   -> List.of(ReceiptType.ADJUST);
            case "TRANSFER" -> List.of(ReceiptType.TRANSFER_IN, ReceiptType.TRANSFER_OUT);
            default         -> List.of(ReceiptType.IMPORT, ReceiptType.EXPORT_ORDER,
                    ReceiptType.EXPORT_OTHER, ReceiptType.ADJUST,
                    ReceiptType.TRANSFER_IN, ReceiptType.TRANSFER_OUT);
        };

        return ResponseEntity.ok(warehouseService.getHistory(types, effectiveWarehouseId, page, size));
    }

    @GetMapping("/receipt/{receiptId}")
    public ResponseEntity<ReceiptResponse> getReceiptDetail(@PathVariable Long receiptId) {
        return ResponseEntity.ok(warehouseService.getReceiptDetail(receiptId));
    }

    /**
     * In PHIẾU ĐI ĐƯỜNG (Giấy thông tin nguồn gốc động vật) cho một phiếu chuyển kho ra.
     * Trả về file PDF để tải/in trực tiếp.
     */
    @GetMapping("/receipt/{receiptId}/transport-slip")
    public ResponseEntity<byte[]> getTransportSlip(@PathVariable Long receiptId) {
        try {
            var data = warehouseService.buildTransportSlip(receiptId);
            byte[] pdf = transportSlipPdf.generate(data);
            String filename = "phieu-di-duong-" + data.receiptCode() + ".pdf";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (com.nhatnam.server.common.BusinessException e) {
            return ResponseEntity.badRequest()
                    .body(e.getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Lỗi tạo phiếu đi đường cho phiếu {}", receiptId, e);
            return ResponseEntity.status(500)
                    .body("Không tạo được phiếu đi đường".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * BÁO CÁO PDF chi tiết một phiếu kho — dùng cho NÚT "Xuất báo cáo" ở trang Lịch sử.
     *
     * <p>Hoạt động cho MỌI loại phiếu (IMPORT / EXPORT_ORDER / EXPORT_OTHER / ADJUST /
     * TRANSFER_IN / TRANSFER_OUT). Khác với endpoint {@code /transport-slip} bên trên —
     * cái đó chỉ dùng cho TRANSFER_OUT và in theo mẫu Giấy thông tin nguồn gốc động vật.
     *
     * <p>Trả về PDF với {@code inline} để trình duyệt mở tab mới (giữ nguyên hành vi như
     * transport-slip cho nhất quán trên FE). Nếu popup bị chặn FE tự tải file về.
     */
    @GetMapping("/receipt/{receiptId}/report")
    public ResponseEntity<byte[]> getReceiptReport(@PathVariable Long receiptId) {
        try {
            ReceiptResponse data = warehouseService.getReceiptDetail(receiptId);
            byte[] pdf = receiptReportPdf.generate(data);
            String filename = "bao-cao-phieu-" + data.getReceiptCode() + ".pdf";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (com.nhatnam.server.common.BusinessException e) {
            return ResponseEntity.badRequest()
                    .body(e.getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Lỗi tạo báo cáo phiếu {}", receiptId, e);
            return ResponseEntity.status(500)
                    .body("Không tạo được báo cáo phiếu".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // ── Orders ───────────────────────────────────────────────────────────────
    // FIX #5: Chỉ trả đơn hàng của kho mà user quản lý

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    @GetMapping("/orders/preparing")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPreparingOrders(
            @RequestParam(required = false) Long warehouseId,
            Authentication authentication) {
        try {
            // Resolve warehouseId: ưu tiên param FE gửi (kho đang chọn), fallback user.warehouse
            Long effectiveWarehouseId = resolveWarehouseIdForUser(authentication, warehouseId);

            List<Order> orders;
            if (effectiveWarehouseId != null) {
                final Long whId = effectiveWarehouseId;
                orders = orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.PREPARING)
                        .stream()
                        .filter(o -> o.getWarehouseId() != null && o.getWarehouseId().equals(whId))
                        .collect(Collectors.toList());
            } else {
                orders = orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.PREPARING);
            }

            List<Map<String, Object>> result = orders.stream()
                    .map(o -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("id",            o.getId());
                        m.put("orderCode",     o.getOrderCode());
                        // FIX #2: Hiển thị "Khách lẻ/Khách vãng lai" nếu tên trống
                        String customerName = o.getCustomerName();
                        m.put("customerName",  (customerName == null || customerName.isBlank())
                                ? "Khách lẻ/Khách vãng lai" : customerName);
                        m.put("customerPhone", o.getCustomerPhone());
                        m.put("warehouseName", o.getWarehouseName());
                        m.put("status",        o.getStatus());
                        m.put("notes",         o.getNotes());
                        m.put("createdAt",     o.getCreatedAt());

                        // ── THÔNG TIN GIAO HÀNG ──────────────────────────────
                        // Kho cần địa chỉ và người nhận ngay trên màn hình soạn hàng.
                        // Thiếu chúng nên modal "Bắt đầu giao" trước đây hiện địa chỉ "—"
                        // dù đơn có địa chỉ đầy đủ.
                        m.put("deliveryAddress",  o.getDeliveryAddress());
                        m.put("shippingAddress",  o.getShippingAddress());
                        m.put("provinceName",     o.getProvinceName());
                        m.put("wardName",         o.getWardName());
                        m.put("receiverName",     o.getReceiverName());
                        m.put("orderedByName",    o.getOrderedByName());
                        m.put("deliveryDatetime", o.getDeliveryDatetime());

                        // ── YÊU CẦU THANH TOÁN TRƯỚC ─────────────────────────
                        // FE dùng các cờ này để KHOÁ nút "Giao hàng" khi khách bắt buộc
                        // thanh toán trước mà đơn chưa thu đủ tiền. (BE cũng chặn lại
                        // trong OrderServiceImpl.markAsDelivering — đây chỉ là lớp UX.)
                        boolean prepay = orderServiceImpl.isPrepaymentRequired(o);
                        java.math.BigDecimal fin  = o.getFinalAmount() != null
                                ? o.getFinalAmount() : java.math.BigDecimal.ZERO;
                        java.math.BigDecimal paid = o.getPaidAmount() != null
                                ? o.getPaidAmount() : java.math.BigDecimal.ZERO;
                        java.math.BigDecimal remaining = fin.subtract(paid)
                                .max(java.math.BigDecimal.ZERO)
                                .setScale(0, java.math.RoundingMode.HALF_UP);
                        boolean fullyPaid = o.getPaymentStatus() == com.nhatnam.server.enumtype.PaymentStatus.PAID
                                || paid.compareTo(fin.subtract(java.math.BigDecimal.ONE)) >= 0;

                        m.put("requirePrepayment", prepay);
                        m.put("paymentStatus",     o.getPaymentStatus());
                        m.put("finalAmount",       fin.setScale(0, java.math.RoundingMode.HALF_UP));
                        m.put("paidAmount",        paid.setScale(0, java.math.RoundingMode.HALF_UP));
                        m.put("remainingAmount",   remaining);
                        // false → FE khoá nút "Giao hàng"
                        m.put("canDeliver",        !prepay || fullyPaid);

                        m.put("items", o.getOrderItems().stream()
                                .map(item -> {
                                    Map<String, Object> i = new java.util.LinkedHashMap<>();
                                    i.put("productId",      item.getProductId());
                                    i.put("productName",    item.getProductName());
                                    i.put("productImageUrl",item.getProductImageUrl());
                                    i.put("quantity",       item.getQuantity());
                                    i.put("unit",           item.getUnit());
                                    i.put("saleType",       item.getSaleType() != null ? item.getSaleType() : "RETAIL");
                                    i.put("unitsPerBox",    item.getUnitsPerBox());
                                    i.put("notes",          item.getNotes());
                                    i.put("ingredients", item.getOrderItemIngredients().stream()
                                            .map(ing -> {
                                                Map<String, Object> ingMap = new java.util.LinkedHashMap<>();
                                                ingMap.put("ingredientId",   ing.getIngredientId());
                                                ingMap.put("ingredientName", ing.getIngredientName());
                                                ingMap.put("quantityUsed",   ing.getQuantityUsed());
                                                ingMap.put("unit",           ing.getUnit());
                                                return ingMap;
                                            }).collect(Collectors.toList()));
                                    return i;
                                }).collect(Collectors.toList()));
                        return m;
                    }).collect(Collectors.toList());

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[WAREHOUSE] getPreparingOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private final OrderService orderService;

    @PatchMapping("/orders/{id}/deliver")
    public ResponseEntity<?> markDelivering(@PathVariable Long id, Authentication auth) {
        try {
            User actor = (User) auth.getPrincipal();
            orderService.markAsDelivering(id, getActorName(auth), actor.getId());
            return ResponseEntity.ok(ApiResponse.success(null, "Đã chuyển sang Đang giao"));
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
            log.error("[WAREHOUSE] cancelOrder id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String getActorName(Authentication auth) {
        if (auth == null) return "Warehouse";
        Object p = auth.getPrincipal();
        if (p instanceof User u) {
            return u.getFullName() != null && !u.getFullName().isBlank()
                    ? u.getFullName() : u.getUsername();
        }
        return auth.getName();
    }

    /**
     * Resolve warehouseId cho user đang gọi API:
     * - Nếu requestedWarehouseId được gửi lên và user có quyền trên kho đó → dùng nó
     * - Nếu không → fallback về warehouse đơn của user (legacy)
     * - ADMIN/OWNER → dùng requestedWarehouseId hoặc null (all)
     */
    private Long resolveWarehouseIdForUser(Authentication auth, Long requestedWarehouseId) {
        if (auth == null) return requestedWarehouseId;
        Object p = auth.getPrincipal();
        if (!(p instanceof User u)) return requestedWarehouseId;

        String role = u.getRole() != null ? u.getRole().name() : "";
        if (!"WAREHOUSE".equals(role) && !"SUPER_WAREHOUSE".equals(role)) {
            return requestedWarehouseId; // ADMIN/OWNER: dùng param trực tiếp
        }

        // Load user đầy đủ để có warehouses
        User fullUser = userRepository.findById(u.getId()).orElse(null);
        if (fullUser == null) return requestedWarehouseId;

        // Tập hợp tất cả kho user có quyền
        java.util.Set<Long> allowedIds = fullUser.getAllWarehouses().stream()
                .map(com.nhatnam.server.entity.Warehouse::getId)
                .collect(java.util.stream.Collectors.toSet());

        // Nếu FE gửi warehouseId và user có quyền → dùng
        if (requestedWarehouseId != null && allowedIds.contains(requestedWarehouseId)) {
            return requestedWarehouseId;
        }

        // Fallback: warehouse đơn legacy
        if (fullUser.getWarehouse() != null) return fullUser.getWarehouse().getId();

        // Fallback cuối: kho đầu tiên trong danh sách
        return allowedIds.isEmpty() ? null : allowedIds.iterator().next();
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
                    .body(ApiResponse.error(500, "Lỗi khi xử lý hóa đơn: " + e.getMessage()));
        }
    }

    // ── Driver management ─────────────────────────────────────────────────────

    /** GET /api/warehouse/drivers?q=xxx&type=TRUCK|MOTORBIKE — tìm kiếm tài xế theo loại */
    @GetMapping("/drivers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDrivers(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false) String type) {
        try {
            // Build vehicleType filter: if type specified, include that type + BOTH
            List<Driver.VehicleType> typeFilter = null;
            if (type != null && !type.isBlank()) {
                try {
                    Driver.VehicleType vt = Driver.VehicleType.valueOf(type.toUpperCase());
                    typeFilter = java.util.Arrays.asList(vt, Driver.VehicleType.BOTH);
                } catch (IllegalArgumentException ignored) {}
            }

            List<Driver> drivers;
            if (typeFilter != null) {
                drivers = q.isBlank()
                        ? driverRepository.findByActiveTrueAndVehicleTypeInOrderByNameAsc(typeFilter)
                        : driverRepository.findByNameContainingIgnoreCaseAndActiveTrueAndVehicleTypeIn(q, typeFilter);
            } else {
                drivers = q.isBlank()
                        ? driverRepository.findByActiveTrueOrderByNameAsc()
                        : driverRepository.findByNameContainingIgnoreCaseAndActiveTrue(q);
            }

            List<Map<String, Object>> result = drivers.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",          d.getId());
                m.put("name",        d.getName());
                m.put("vehicleType", d.getVehicleType() != null ? d.getVehicleType().name() : "BOTH");
                return m;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** POST /api/warehouse/drivers — tạo tài xế mới (kèm tài khoản, chặn trùng tên) */
    @PostMapping("/drivers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createDriver(
            @RequestBody Map<String, String> body) {
        try {
            String name = body.getOrDefault("name", "").trim();
            if (name.isBlank()) return ResponseEntity.ok(ApiResponse.error(400, "Tên tài xế không được trống"));

            Driver.VehicleType vt = Driver.VehicleType.BOTH;
            try {
                String vtStr = body.get("vehicleType");
                if (vtStr != null && !vtStr.isBlank()) vt = Driver.VehicleType.valueOf(vtStr.toUpperCase());
            } catch (IllegalArgumentException ignored) {}

            // Tạo tài xế + tài khoản; ném IllegalArgumentException nếu trùng tên
            // → FE nhận message và toast cảnh báo.
            Driver d = driverUserSyncService.createDriverWithAccount(name, vt, false);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", d.getId());
            result.put("name", d.getName());
            result.put("vehicleType", d.getVehicleType().name());
            if (d.getUser() != null) {
                result.put("userId", d.getUser().getId());
                result.put("username", d.getUser().getUsername());
            }
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** PATCH /api/warehouse/orders/{id}/drivers — gán tài xế cho đơn */
    @PatchMapping("/orders/{id}/drivers")
    public ResponseEntity<ApiResponse<Object>> setOrderDrivers(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + id));

            Object rawInfo = body.get("deliveryInfo");
            if (rawInfo != null) {
                String json = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(rawInfo);
                order.setDeliveryInfoJson(json);
            }

            orderRepository.save(order);
            return ResponseEntity.ok(ApiResponse.success(rawInfo, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // DRIVER ATTENDANCE — Điểm danh odo tài xế
    // ════════════════════════════════════════════════════════════════

    /**
     * GET /api/warehouse/driver-attendance?date=yyyy-MM-dd
     * Lấy toàn bộ điểm danh của ngày.
     */
    @GetMapping("/driver-attendance")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAttendance(
            @RequestParam(required = false) String date,
            Authentication authentication) {
        try {
            String d = (date != null && !date.isBlank()) ? date
                    : java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

            // Tài xế active VÀ có theo dõi ODO.
            // systemDriver = true nghĩa là "không xử lý": các lựa chọn giao hàng ảo
            // (Grab, Giao tại kho, Khách tự lấy…) — không có công-tơ-mét để điểm danh
            // nên không hiển thị ở màn này.
            List<com.nhatnam.server.entity.Driver> allDrivers =
                    driverRepository.findByActiveTrueAndSystemDriverFalseOrderByNameAsc();

            // Lấy điểm danh của ngày
            List<com.nhatnam.server.entity.DriverAttendance> attList =
                    driverAttendanceRepository.findByAttendanceDateOrderByCreatedAtAsc(d);

            // Build map: driverId-vehicleType → {START: att, END: att}
            Map<String, Map<String, com.nhatnam.server.entity.DriverAttendance>> attMap = new LinkedHashMap<>();
            for (com.nhatnam.server.entity.DriverAttendance a : attList) {
                String key = a.getDriver().getId() + "-" + a.getVehicleType().name();
                attMap.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .put(a.getSessionType().name(), a);
            }

            // Build result: 1 object per driver, vehicles = [{vehicleType, start{}, end{}}]
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (com.nhatnam.server.entity.Driver driver : allDrivers) {
                List<com.nhatnam.server.entity.Driver.VehicleType> types;
                if (driver.getVehicleType() == com.nhatnam.server.entity.Driver.VehicleType.BOTH) {
                    types = java.util.Arrays.asList(
                            com.nhatnam.server.entity.Driver.VehicleType.MOTORBIKE,
                            com.nhatnam.server.entity.Driver.VehicleType.TRUCK);
                } else {
                    types = java.util.Collections.singletonList(driver.getVehicleType());
                }

                List<Map<String, Object>> vehicles = new java.util.ArrayList<>();
                for (com.nhatnam.server.entity.Driver.VehicleType vt : types) {
                    String key = driver.getId() + "-" + vt.name();
                    Map<String, com.nhatnam.server.entity.DriverAttendance> sessions =
                            attMap.getOrDefault(key, java.util.Collections.emptyMap());

                    com.nhatnam.server.entity.DriverAttendance startAtt = sessions.get("START");
                    com.nhatnam.server.entity.DriverAttendance endAtt   = sessions.get("END");

                    // MỐC SÀN: số ODO đã ghi gần nhất ở những ngày TRƯỚC. FE dùng nó
                    // để khoá ô nhập, không cho gõ số nhỏ hơn — công-tơ-mét không
                    // quay ngược, gõ nhầm sẽ làm số km ra âm.
                    List<com.nhatnam.server.entity.DriverAttendance> prevList =
                            driverAttendanceRepository.findPreviousBefore(driver, vt, d);
                    Integer prevOdo = prevList.isEmpty() ? null : prevList.get(0).getOdometer();
                    String prevDate = prevList.isEmpty() ? null : prevList.get(0).getAttendanceDate();

                    Map<String, Object> vMap = new LinkedHashMap<>();
                    vMap.put("vehicleType",     vt.name());
                    vMap.put("startOdometer",   startAtt != null ? startAtt.getOdometer()   : null);
                    vMap.put("startRecordedBy", startAtt != null ? startAtt.getRecordedBy() : null);
                    vMap.put("endOdometer",     endAtt   != null ? endAtt.getOdometer()     : null);
                    vMap.put("endRecordedBy",   endAtt   != null ? endAtt.getRecordedBy()   : null);
                    vMap.put("prevOdometer",    prevOdo);
                    vMap.put("prevOdometerDate", prevDate);
                    vMap.put("startNote",       startAtt != null ? startAtt.getNote() : null);
                    vMap.put("endNote",         endAtt   != null ? endAtt.getNote()   : null);
                    vehicles.add(vMap);
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("driverId",       driver.getId());
                m.put("driverName",     driver.getName());
                m.put("driverType",     driver.getVehicleType().name()); // MOTORBIKE | TRUCK | BOTH
                m.put("attendanceDate", d);
                m.put("vehicles",       vehicles);
                result.add(m);
            }

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * POST /api/warehouse/driver-attendance
     * Upsert 1 record điểm danh (tạo mới hoặc cập nhật nếu đã tồn tại).
     * Body: { driverId, vehicleType, sessionType, odometer, date? }
     */
    @PostMapping("/driver-attendance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upsertAttendance(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        try {
            String recorderName = "NV Kho";
            if (authentication != null && authentication.getPrincipal() instanceof com.nhatnam.server.entity.User u) {
                recorderName = u.getFullName() != null ? u.getFullName() : u.getUsername();
            }

            Long driverId = ((Number) body.get("driverId")).longValue();
            String vtStr  = (String) body.get("vehicleType");
            String stStr  = (String) body.get("sessionType");
            Integer odo   = body.get("odometer") instanceof Number n ? n.intValue() : null;
            String note   = body.get("note") instanceof String s ? s.trim() : null;  // ← MỚI
            String date   = body.get("date") instanceof String s2 && !s2.isBlank() ? s2
                    : java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

            if (odo == null || odo < 0) throw new IllegalArgumentException("ODO không hợp lệ");

            // Resolve driver, vehicleType, sessionType
            com.nhatnam.server.entity.Driver driver = driverRepository.findById(driverId)
                    .orElseThrow(() -> new RuntimeException("Tài xế không tồn tại: " + driverId));
            com.nhatnam.server.entity.Driver.VehicleType vt =
                    com.nhatnam.server.entity.Driver.VehicleType.valueOf(vtStr.toUpperCase());
            com.nhatnam.server.entity.DriverAttendance.SessionType st =
                    com.nhatnam.server.entity.DriverAttendance.SessionType.valueOf(stStr.toUpperCase());

            // Validate: đầu ca ≤ cuối ca (TRONG CÙNG NGÀY — giữ nguyên logic cũ)
            com.nhatnam.server.entity.DriverAttendance.SessionType otherSt =
                    (st == com.nhatnam.server.entity.DriverAttendance.SessionType.START)
                            ? com.nhatnam.server.entity.DriverAttendance.SessionType.END
                            : com.nhatnam.server.entity.DriverAttendance.SessionType.START;

            java.util.Optional<com.nhatnam.server.entity.DriverAttendance> otherAttOpt =
                    driverAttendanceRepository.findByAttendanceDateAndSessionTypeAndDriverAndVehicleType(
                            date, otherSt, driver, vt);

            if (otherAttOpt.isPresent()) {
                int otherOdo = otherAttOpt.get().getOdometer();
                if (st == com.nhatnam.server.entity.DriverAttendance.SessionType.START && odo > otherOdo)
                    throw new IllegalArgumentException("ODO đầu ca không được lớn hơn ODO cuối ca (" + otherOdo + " km)");
                if (st == com.nhatnam.server.entity.DriverAttendance.SessionType.END && odo < otherOdo)
                    throw new IllegalArgumentException("ODO cuối ca không được nhỏ hơn ODO đầu ca (" + otherOdo + " km)");
            } else if (st == com.nhatnam.server.entity.DriverAttendance.SessionType.END) {
                throw new IllegalArgumentException(
                        "Chưa điểm danh đầu ca ngày " + date + " — vui lòng nhập ODO đầu ca trước.");
            }

            // ══════════════════════════════════════════════════════════════
            // THAY ĐỔI: Cho phép ODO nhỏ hơn ngày trước, nhưng BẮT BUỘC ghi chú
            // ══════════════════════════════════════════════════════════════
            boolean odoLowerThanPrev = false;
            if (st == com.nhatnam.server.entity.DriverAttendance.SessionType.START) {
                List<com.nhatnam.server.entity.DriverAttendance> prevList =
                        driverAttendanceRepository.findPreviousBefore(driver, vt, date);
                if (!prevList.isEmpty()) {
                    com.nhatnam.server.entity.DriverAttendance prev = prevList.get(0);
                    if (odo < prev.getOdometer()) {
                        odoLowerThanPrev = true;
                        if (note == null || note.isBlank()) {
                            throw new IllegalArgumentException(
                                    "ODO nhỏ hơn số đã ghi ngày " + prev.getAttendanceDate()
                                            + " (" + prev.getOdometer() + " km)"
                                            + " — vui lòng nhập lý do (VD: đổi xe, sửa đồng hồ…)");
                        }
                    }
                }
            }
            // Tự bù kết ca cho ngày cũ còn thiếu (giữ nguyên logic)
            if (st == com.nhatnam.server.entity.DriverAttendance.SessionType.START) {
                autoFillMissingEnd(driver, vt, date, odo, recorderName);
            }

            // Upsert
            com.nhatnam.server.entity.DriverAttendance att =
                    driverAttendanceRepository.findByAttendanceDateAndSessionTypeAndDriverAndVehicleType(
                                    date, st, driver, vt)
                            .orElse(null);

            if (att == null) {
                att = com.nhatnam.server.entity.DriverAttendance.builder()
                        .driver(driver).attendanceDate(date)
                        .sessionType(st).vehicleType(vt)
                        .odometer(odo).recordedBy(recorderName)
                        .note(odoLowerThanPrev ? note : null)   // ← Chỉ lưu note khi ODO bất thường
                        .build();
            } else {
                att.setOdometer(odo);
                att.setRecordedBy(recorderName);
                att.setNote(odoLowerThanPrev ? note : null);    // ← Cập nhật note
            }
            att = driverAttendanceRepository.save(att);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id",             att.getId());
            res.put("driverId",       att.getDriver().getId());
            res.put("driverName",     att.getDriver().getName());
            res.put("vehicleType",    att.getVehicleType().name());
            res.put("sessionType",    att.getSessionType().name());
            res.put("odometer",       att.getOdometer());
            res.put("recordedBy",     att.getRecordedBy());
            res.put("note",           att.getNote());            // ← Trả note
            res.put("attendanceDate", att.getAttendanceDate());
            res.put("updatedAt",      att.getUpdatedAt());
            return ResponseEntity.ok(ApiResponse.success(res, "Đã lưu điểm danh"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("upsertAttendance error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * BÙ KẾT CA CHO NGÀY GẦN NHẤT CÓ ĐẦU CA MÀ THIẾU KẾT CA.
     *
     * <p>Gọi khi nhập ĐẦU CA của {@code date}. Duyệt ngược các ngày trước đó, gặp
     * ngày đầu tiên có START mà không có END thì ghi END = {@code odoNow}.
     *
     * <p>Nếu ngày gần nhất đã đủ cả hai ca thì không làm gì — dữ liệu đang lành.
     * Nếu ngày đó chỉ có END mà không có START (dữ liệu cũ, trước khi có ràng
     * buộc) cũng bỏ qua: không đoán được đầu ca là bao nhiêu.
     *
     * @param odoNow ODO đầu ca vừa nhập — đã được validate ≥ mọi ODO trước đó
     * @return true nếu có bù, để nơi gọi ghi log nếu cần
     */
    private boolean autoFillMissingEnd(com.nhatnam.server.entity.Driver driver,
                                       com.nhatnam.server.entity.Driver.VehicleType vt,
                                       String date,
                                       int odoNow,
                                       String recorderName) {

        List<com.nhatnam.server.entity.DriverAttendance> prevList =
                driverAttendanceRepository.findPreviousBefore(driver, vt, date);
        if (prevList.isEmpty()) return false;

        // Gom theo ngày, giữ nguyên thứ tự ngày giảm dần của truy vấn.
        Map<String, Map<String, com.nhatnam.server.entity.DriverAttendance>> byDate =
                new LinkedHashMap<>();
        for (com.nhatnam.server.entity.DriverAttendance a : prevList) {
            byDate.computeIfAbsent(a.getAttendanceDate(), k -> new LinkedHashMap<>())
                    .put(a.getSessionType().name(), a);
        }

        // Ngày gần nhất = phần tử đầu tiên.
        Map.Entry<String, Map<String, com.nhatnam.server.entity.DriverAttendance>> latest =
                byDate.entrySet().iterator().next();

        Map<String, com.nhatnam.server.entity.DriverAttendance> sessions = latest.getValue();
        com.nhatnam.server.entity.DriverAttendance startAtt = sessions.get("START");
        if (startAtt == null || sessions.get("END") != null) return false;

        // Không bù ngược: kết ca luôn phải ≥ đầu ca của chính ngày đó.
        if (odoNow < startAtt.getOdometer()) return false;

        driverAttendanceRepository.save(
                com.nhatnam.server.entity.DriverAttendance.builder()
                        .driver(driver)
                        .attendanceDate(latest.getKey())
                        .sessionType(com.nhatnam.server.entity.DriverAttendance.SessionType.END)
                        .vehicleType(vt)
                        .odometer(odoNow)
                        .recordedBy("Tự bù từ đầu ca " + date
                                + (recorderName != null ? " (" + recorderName + ")" : ""))
                        .build());

        log.info("Bù kết ca thiếu: tài xế {} xe {} ngày {} → ODO {}",
                driver.getId(), vt, latest.getKey(), odoNow);
        return true;
    }

    /**
     * GET /api/warehouse/driver-attendance/report?from=yyyy-MM-dd&to=yyyy-MM-dd
     */
    @GetMapping("/reports")
    public ResponseEntity<byte[]> exportDriverReport(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "true") boolean excludeWarehouse,
            @RequestParam(defaultValue = "0") double bikeRatePerKm,
            @RequestParam(defaultValue = "0") double truckRatePerKm,
            Authentication auth) {
        try {
            final boolean hasSalary = bikeRatePerKm > 0 || truckRatePerKm > 0;
            // Tài xế giao tại kho: tên chứa "kho" (không phân biệt hoa/thường)

            java.util.function.Predicate<com.nhatnam.server.entity.Driver> isWarehouseDriver =
                    d -> d.getName() != null && d.getName().toLowerCase().contains("kho");

            List<com.nhatnam.server.entity.DriverAttendance> attList =
                    driverAttendanceRepository.findByDateRange(from, to);

            Map<String, Integer[]> dayOdo = new java.util.LinkedHashMap<>();
            for (com.nhatnam.server.entity.DriverAttendance a : attList) {
                if (excludeWarehouse && isWarehouseDriver.test(a.getDriver())) continue;
                String key = a.getDriver().getId() + "|" + a.getVehicleType().name() + "|" + a.getAttendanceDate();
                dayOdo.computeIfAbsent(key, k -> new Integer[]{null, null});
                Integer[] pair = dayOdo.get(key);
                if (a.getSessionType() == com.nhatnam.server.entity.DriverAttendance.SessionType.START) pair[0] = a.getOdometer();
                else pair[1] = a.getOdometer();
            }

            Map<Long, int[]> stats = new java.util.LinkedHashMap<>();
            Map<Long, String> names = new java.util.LinkedHashMap<>();
            for (com.nhatnam.server.entity.Driver d : driverRepository.findByActiveTrueOrderByNameAsc()) {
                if (excludeWarehouse && isWarehouseDriver.test(d)) continue;
                stats.put(d.getId(), new int[]{0,0,0,0});
                names.put(d.getId(), d.getName());
            }
            dayOdo.forEach((key, pair) -> {
                if (pair[0] == null || pair[1] == null) return;
                String[] parts = key.split("\\|");
                Long dId = Long.parseLong(parts[0]);
                String vt = parts[1];
                int km = Math.max(0, pair[1] - pair[0]);
                int[] s = stats.computeIfAbsent(dId, x -> new int[]{0,0,0,0});
                if ("MOTORBIKE".equals(vt)) { s[0] += km; s[1]++; }
                else if ("TRUCK".equals(vt)) { s[2] += km; s[3]++; }
            });

            long fromMs = java.time.LocalDate.parse(from)
                    .atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli();
            long toMs = java.time.LocalDate.parse(to).plusDays(1)
                    .atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli() - 1;
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Integer> ordersByName = new java.util.HashMap<>();
            orderRepository.findAll().stream()
                    .filter(o -> (o.getStatus() == com.nhatnam.server.enumtype.OrderStatus.PENDING_PAYMENT
                            || o.getStatus() == com.nhatnam.server.enumtype.OrderStatus.COMPLETED)
                            && o.getCreatedAt() >= fromMs && o.getCreatedAt() <= toMs
                            && o.getDeliveryInfoJson() != null && !o.getDeliveryInfoJson().isBlank())
                    .forEach(o -> {
                        try {
                            List<Map<String,Object>> info = om.readValue(o.getDeliveryInfoJson(),
                                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
                            for (Map<String,Object> d : info) {
                                String n = String.valueOf(d.getOrDefault("name","")).trim();
                                if (!n.isBlank()) ordersByName.merge(n, 1, Integer::sum);
                            }
                        } catch (Exception ignored) {}
                    });

            try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

                org.apache.poi.xssf.usermodel.XSSFSheet ws = wb.createSheet("Báo cáo tài xế");
                ws.setDisplayGridlines(false);

                // ── Color palette ────────────────────────────────────────────
                byte[] darkNavy   = {(byte)0x1A,(byte)0x1A,(byte)0x2E};
                byte[] gold       = {(byte)0xC9,(byte)0xA8,(byte)0x4C};
                byte[] lightGold  = {(byte)0xFD,(byte)0xF8,(byte)0xED};
                byte[] altRow     = {(byte)0xF8,(byte)0xF9,(byte)0xFA};
                byte[] totalBg    = {(byte)0x1A,(byte)0x1A,(byte)0x2E};
                byte[] white      = {(byte)0xFF,(byte)0xFF,(byte)0xFF};
                byte[] bikeBlue   = {(byte)0xE3,(byte)0xF2,(byte)0xFD};
                byte[] truckOrng  = {(byte)0xFFF3,(byte)0xE0,(byte)0x00};
                byte[] salaryGrn  = {(byte)0xE8,(byte)0xF5,(byte)0xE9};

                java.time.ZoneId tz = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
                String exportedBy = auth != null ? ((com.nhatnam.server.entity.User) auth.getPrincipal()).getFullName() : "";
                String exportedAt = java.time.LocalDateTime.now(tz)
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));

                // ── Helper: create style ──────────────────────────────────────
                java.util.function.BiFunction<byte[], Boolean, org.apache.poi.ss.usermodel.CellStyle> makeStyle =
                        (bg, bold) -> {
                            org.apache.poi.ss.usermodel.CellStyle s = wb.createCellStyle();
                            if (bg != null) {
                                s.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(bg, null));
                                s.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
                            }
                            org.apache.poi.xssf.usermodel.XSSFFont f = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
                            if (bold) f.setBold(true);
                            f.setFontHeightInPoints((short)10);
                            s.setFont(f);
                            s.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
                            s.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                            s.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                            s.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                            s.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                            return s;
                        };

                // ── Column widths ─────────────────────────────────────────────
                // STT|Tên|Km XM|Phiếu XM|Km XT|Phiếu XT|Đơn giao|(Lương XM)|(Lương XT)|Tổng lương
                int[] colW = hasSalary
                        ? new int[]{5, 22, 11, 11, 11, 11, 11, 14, 14, 14}
                        : new int[]{5, 22, 11, 11, 11, 11, 11};
                for (int i = 0; i < colW.length; i++) ws.setColumnWidth(i, colW[i] * 256);

                int totalCols = colW.length;
                int rowIdx = 0;

                // ── ROW 0: Company header ─────────────────────────────────────
                org.apache.poi.ss.usermodel.Row r0 = ws.createRow(rowIdx++);
                r0.setHeightInPoints(30);
                org.apache.poi.ss.usermodel.CellStyle titleStyle = wb.createCellStyle();
                titleStyle.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(darkNavy, null));
                titleStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
                titleStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
                titleStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
                org.apache.poi.xssf.usermodel.XSSFFont titleFont = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
                titleFont.setBold(true); titleFont.setFontHeightInPoints((short)14);
                titleFont.setColor(new org.apache.poi.xssf.usermodel.XSSFColor(white, null));
                titleStyle.setFont(titleFont);
                org.apache.poi.ss.usermodel.Cell c0 = r0.createCell(0);
                c0.setCellValue("BÁO CÁO TÀI XẾ & BẢNG LƯƠNG  ·  " + from + " — " + to);
                c0.setCellStyle(titleStyle);
                ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, totalCols - 1));

                // ── ROW 1: Meta ───────────────────────────────────────────────
                org.apache.poi.ss.usermodel.Row r1 = ws.createRow(rowIdx++);
                r1.setHeightInPoints(16);
                org.apache.poi.ss.usermodel.CellStyle metaStyle = wb.createCellStyle();
                metaStyle.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(lightGold, null));
                metaStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
                metaStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
                org.apache.poi.xssf.usermodel.XSSFFont metaFont = (org.apache.poi.xssf.usermodel.XSSFFont) wb.createFont();
                metaFont.setItalic(true); metaFont.setFontHeightInPoints((short)9);
                metaStyle.setFont(metaFont);
                org.apache.poi.ss.usermodel.Cell c1 = r1.createCell(0);
                c1.setCellValue("Xuất lúc: " + exportedAt + "   |   Người xuất: " + exportedBy
                        + (hasSalary ? "   |   Lương XM: " + String.format("%,.0f", bikeRatePerKm) + " đ/km"
                        + "   |   Lương XT: " + String.format("%,.0f", truckRatePerKm) + " đ/km" : ""));
                c1.setCellStyle(metaStyle);
                ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 0, totalCols - 1));

                // ── ROW 2: blank ─────────────────────────────────────────────
                ws.createRow(rowIdx++).setHeightInPoints(6);

                // ── ROW 3: Header ─────────────────────────────────────────────
                org.apache.poi.ss.usermodel.Row hr = ws.createRow(rowIdx++);
                hr.setHeightInPoints(22);
                org.apache.poi.ss.usermodel.CellStyle hStyle = makeStyle.apply(darkNavy, true);
                hStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
                ((org.apache.poi.xssf.usermodel.XSSFCellStyle)hStyle).getFont()
                        .setColor(new org.apache.poi.xssf.usermodel.XSSFColor(white, null));

                java.util.List<String> headers = new java.util.ArrayList<>(java.util.Arrays.asList(
                        "STT", "Tài xế", "Km xe máy", "Phiếu XM", "Km xe tải", "Phiếu XT", "Đơn đã giao"
                ));
                if (hasSalary) {
                    headers.add("Lương xe máy"); headers.add("Lương xe tải"); headers.add("Tổng lương");
                }
                for (int i = 0; i < headers.size(); i++) {
                    org.apache.poi.ss.usermodel.Cell c = hr.createCell(i);
                    c.setCellValue(headers.get(i));
                    c.setCellStyle(hStyle);
                }

                // ── Data format ───────────────────────────────────────────────
                org.apache.poi.ss.usermodel.DataFormat dfmt = wb.createDataFormat();
                short numFmt = dfmt.getFormat("#,##0");
                short currFmt = dfmt.getFormat("#,##0\" đ\"");

                // ── Data rows ─────────────────────────────────────────────────
                double grandTotal = 0;
                int stt = 1;
                for (Map.Entry<Long, String> e : names.entrySet()) {
                    int[] s = stats.getOrDefault(e.getKey(), new int[]{0,0,0,0});
                    int ord = ordersByName.getOrDefault(e.getValue(), 0);
                    boolean alt = stt % 2 == 0;

                    double bikeSalary = s[0] * bikeRatePerKm;
                    double truckSalary = s[2] * truckRatePerKm;
                    double totalSalary = bikeSalary + truckSalary;
                    grandTotal += totalSalary;

                    org.apache.poi.ss.usermodel.Row dr = ws.createRow(rowIdx++);
                    dr.setHeightInPoints(18);

                    byte[] rowBg = alt ? altRow : white;
                    org.apache.poi.ss.usermodel.CellStyle dStyle = makeStyle.apply(rowBg, false);
                    org.apache.poi.ss.usermodel.CellStyle numStyle = makeStyle.apply(rowBg, false);
                    numStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                    numStyle.setDataFormat(numFmt);
                    org.apache.poi.ss.usermodel.CellStyle currStyle = makeStyle.apply(rowBg, false);
                    currStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                    currStyle.setDataFormat(currFmt);

                    dr.createCell(0).setCellValue(stt++);
                    dr.getCell(0).setCellStyle(dStyle);

                    dr.createCell(1).setCellValue(e.getValue());
                    dr.getCell(1).setCellStyle(dStyle);

                    for (int ci = 2; ci <= 6; ci++) {
                        int val = switch (ci) { case 2 -> s[0]; case 3 -> s[1]; case 4 -> s[2]; case 5 -> s[3]; case 6 -> ord; default -> 0; };
                        org.apache.poi.ss.usermodel.Cell nc = dr.createCell(ci);
                        nc.setCellValue(val);
                        nc.setCellStyle(numStyle);
                    }

                    if (hasSalary) {
                        // Bike salary
                        org.apache.poi.ss.usermodel.CellStyle bStyle = makeStyle.apply(
                                alt ? new byte[]{(byte)0xE3,(byte)0xF2,(byte)0xFD} : new byte[]{(byte)0xF0,(byte)0xF8,(byte)0xFF}, false);
                        bStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                        bStyle.setDataFormat(currFmt);
                        org.apache.poi.ss.usermodel.Cell bc = dr.createCell(7);
                        bc.setCellValue(bikeSalary); bc.setCellStyle(bStyle);

                        // Truck salary
                        org.apache.poi.ss.usermodel.CellStyle tStyle = makeStyle.apply(
                                alt ? new byte[]{(byte)0xFF,(byte)0xF3,(byte)0xE0} : new byte[]{(byte)0xFF,(byte)0xF8,(byte)0xF0}, false);
                        tStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                        tStyle.setDataFormat(currFmt);
                        org.apache.poi.ss.usermodel.Cell tc = dr.createCell(8);
                        tc.setCellValue(truckSalary); tc.setCellStyle(tStyle);

                        // Total salary
                        org.apache.poi.ss.usermodel.CellStyle tsStyle = makeStyle.apply(
                                totalSalary > 0 ? new byte[]{(byte)0xE8,(byte)0xF5,(byte)0xE9} : rowBg, true);
                        tsStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                        tsStyle.setDataFormat(currFmt);
                        org.apache.poi.ss.usermodel.Cell tsc = dr.createCell(9);
                        tsc.setCellValue(totalSalary); tsc.setCellStyle(tsStyle);
                    }
                }

                // ── Total row ─────────────────────────────────────────────────
                org.apache.poi.ss.usermodel.Row totalRow = ws.createRow(rowIdx);
                totalRow.setHeightInPoints(22);
                org.apache.poi.ss.usermodel.CellStyle totStyle = makeStyle.apply(totalBg, true);
                totStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                totStyle.setDataFormat(currFmt);
                ((org.apache.poi.xssf.usermodel.XSSFCellStyle)totStyle).getFont()
                        .setColor(new org.apache.poi.xssf.usermodel.XSSFColor(white, null));
                org.apache.poi.ss.usermodel.CellStyle totLabelStyle = makeStyle.apply(totalBg, true);
                totLabelStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
                ((org.apache.poi.xssf.usermodel.XSSFCellStyle)totLabelStyle).getFont()
                        .setColor(new org.apache.poi.xssf.usermodel.XSSFColor(white, null));

                org.apache.poi.ss.usermodel.Cell tlCell = totalRow.createCell(0);
                tlCell.setCellValue("TỔNG CỘNG (" + names.size() + " tài xế)");
                tlCell.setCellStyle(totLabelStyle);
                ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, 0, hasSalary ? 8 : 6));
                for (int ci = 1; ci <= (hasSalary ? 8 : 6); ci++) {
                    org.apache.poi.ss.usermodel.Cell blank = totalRow.createCell(ci);
                    blank.setCellStyle(totLabelStyle);
                }
                if (hasSalary) {
                    org.apache.poi.ss.usermodel.Cell sumCell = totalRow.createCell(9);
                    sumCell.setCellValue(grandTotal);
                    sumCell.setCellStyle(totStyle);
                }

                ws.createFreezePane(0, 4);
                wb.write(bos);
                String fn = "bao-cao-tai-xe-" + from + "-" + to + ".xlsx";
                return ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=\"" + fn + "\"")
                        .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .body(bos.toByteArray());
            }
        } catch (Exception e) {
            log.error("exportDriverReport error", e);
            return ResponseEntity.status(500).body(null);
        }
    }
}