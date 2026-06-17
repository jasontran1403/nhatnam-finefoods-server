package com.nhatnam.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.request.CreateOrderRequest;
import com.nhatnam.server.dto.request.SuperSellerUpdateOrderRequest;
import com.nhatnam.server.dto.response.OrderResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service riêng cho SUPER_SELLER sửa đơn hàng.
 * - Cho phép sửa mọi trạng thái trừ CANCELLED.
 * - Bắt buộc có requestedBy (người yêu cầu) và editReason (lý do).
 * - Tạo OrderLog với action=ORDER_UPDATED, actorRole=SELLER.
 * - Tính lại subtotal / discount / VAT / surcharge giống tạo mới.
 * - Cập nhật kho (delta) giống updateOrderItems của seller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuperSellerOrderService {

    private static final ZoneId TZ = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ObjectMapper                  objectMapper;
    private final OrderRepository               orderRepository;
    private final ProductRepository             productRepository;
    private final UserRepository                userRepository;
    private final CustomerRepository            customerRepository;
    private final WarehouseRepository           warehouseRepository;
    private final ProductIngredientRepository   productIngredientRepository;
    private final ProductPriceTierRepository    priceTierRepository;
    private final IngredientRepository          ingredientRepository;
    private final IngredientStockRepository     ingredientStockRepository;
    private final WarehouseReceiptRepository    warehouseReceiptRepository;
    private final OrderLogRepository            orderLogRepository;
    private final OrderStockDeductionRepository orderStockDeductionRepository;
    private final FifoDeductService             fifoDeductService;
    private final CartHoldService               cartHoldService;
    private final OrderService                  orderService; // dùng mapToResponse qua getOrderById
    private final NotificationService           notificationService;

    // ── Search nhân viên theo keyword (tên / username) ────────────────────────

    public List<Map<String, Object>> searchStaff(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String q = keyword.trim().toLowerCase();

        Set<Role> ALLOWED_ROLES = Set.of(
                Role.SELLER, Role.SUPER_SELLER,
                Role.ACCOUNTANT, Role.SUPER_ACCOUNTANT,
                Role.WAREHOUSE, Role.SUPER_WAREHOUSE
        );

        return userRepository.findAll().stream()
                // Có ít nhất 1 role thuộc danh sách cho phép
                .filter(u -> u.getAllRoles().stream().anyMatch(ALLOWED_ROLES::contains))
                // Match tên hoặc username
                .filter(u -> {
                    String fn = u.getFullName() != null ? u.getFullName().toLowerCase() : "";
                    String un = u.getUsername() != null ? u.getUsername().toLowerCase() : "";
                    return fn.contains(q) || un.contains(q);
                })
                .limit(20)
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("fullName", u.getFullName() != null ? u.getFullName() : u.getUsername());
                    m.put("username", u.getUsername());
                    // Trả về tất cả roles để frontend hiển thị
                    m.put("roles", u.getAllRoles().stream()
                            .map(Role::name)
                            .sorted()
                            .toList());
                    m.put("role", u.getRole() != null ? u.getRole().name() : "");
                    return m;
                })
                .toList();
    }

    // ── Sửa đơn hàng ──────────────────────────────────────────────────────────

    @Transactional
    public OrderResponse updateOrder(Long orderId, Long editorUserId,
                                     SuperSellerUpdateOrderRequest req) {
        // ── 1. Validate đầu vào ───────────────────────────────────────────
        if (req.getRequestedBy() == null || req.getRequestedBy().isBlank())
            throw new IllegalArgumentException("Vui lòng nhập tên nhân viên yêu cầu sửa");
        if (req.getEditReason() == null || req.getEditReason().isBlank())
            throw new IllegalArgumentException("Vui lòng nhập lý do sửa đơn");
        if (req.getItems() == null || req.getItems().isEmpty())
            throw new IllegalArgumentException("Đơn hàng cần có ít nhất 1 sản phẩm");

        // ── 2. Load & kiểm tra quyền ──────────────────────────────────────
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));

        User editor = userRepository.findById(editorUserId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy user: " + editorUserId));

        Set<Role> roles = editor.getAllRoles();
        boolean isSuperSeller = roles.contains(Role.SUPER_SELLER);
        boolean isOwner = roles.contains(Role.OWNER) || roles.contains(Role.ADMIN) || roles.contains(Role.SUPERADMIN);

        if (!isSuperSeller && !isOwner)
            throw new RuntimeException("Chỉ SUPER_SELLER hoặc OWNER mới có thể dùng API này");

        if (order.getStatus() == OrderStatus.CANCELLED)
            throw new RuntimeException("Không thể sửa đơn hàng đã hủy");

        long now = System.currentTimeMillis();
        Long warehouseId = order.getWarehouseId();
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new RuntimeException("Kho không tồn tại: " + warehouseId));

        // ── 3. Cập nhật thông tin khách hàng (nếu có) ────────────────────
        if (req.getCustomerId() != null) {
            applyCustomerChange(order, req.getCustomerId());
        }

        // ── 4. Delta kho — load deduction cũ ─────────────────────────────
        List<OrderStockDeduction> existingDeductions =
                orderStockDeductionRepository.findByOrderId(orderId);

        Map<Long, BigDecimal> oldUsageMap = existingDeductions.stream()
                .collect(Collectors.groupingBy(
                        d -> d.getIngredientStock().getIngredientId(),
                        Collectors.reducing(BigDecimal.ZERO,
                                OrderStockDeduction::getQuantity, BigDecimal::add)));

        Map<Long, OrderStockDeduction> existingByIngId = existingDeductions.stream()
                .collect(Collectors.toMap(
                        d -> d.getIngredientStock().getIngredientId(), d -> d, (a, b) -> a));

        // ── 5. Build order items mới ──────────────────────────────────────
        Map<Long, BigDecimal> newUsageMap = new LinkedHashMap<>();
        List<Map<Long, BigDecimal>> itemIngUsage = new ArrayList<>();
        List<OrderItem> newItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CreateOrderRequest.OrderItemRequest itemReq : req.getItems()) {
            Map<Long, BigDecimal> usageForItem = new LinkedHashMap<>();
            OrderItem item = buildOrderItem(order, itemReq, usageForItem);
            newItems.add(item);
            subtotal = subtotal.add(item.getSubtotal());
            itemIngUsage.add(usageForItem);
            usageForItem.forEach((ingId, qty) -> newUsageMap.merge(ingId, qty, BigDecimal::add));
        }

        // ── 6. Delta kho & FIFO ───────────────────────────────────────────
        Map<Long, BigDecimal> deltaMap = buildDeltaMap(oldUsageMap, newUsageMap,
                existingDeductions.isEmpty());
        Map<Long, BigDecimal> ingredientUnitCostMap = new LinkedHashMap<>();
        List<WarehouseReceiptItem> receiptItems = new ArrayList<>();

        applyStockDelta(order, warehouse, now, deltaMap, oldUsageMap, existingByIngId,
                ingredientUnitCostMap, receiptItems);

        // ── 7. Cost per item ──────────────────────────────────────────────
        for (int i = 0; i < newItems.size(); i++) {
            BigDecimal cost = BigDecimal.ZERO;
            for (Map.Entry<Long, BigDecimal> u : itemIngUsage.get(i).entrySet())
                cost = cost.add(ingredientUnitCostMap.getOrDefault(u.getKey(), BigDecimal.ZERO)
                        .multiply(u.getValue()));
            newItems.get(i).setCostPrice(cost.setScale(2, RoundingMode.HALF_UP));
        }

        // ── 8. Clear items cũ, lưu items mới ─────────────────────────────
        order.getOrderItems().clear();
        orderRepository.saveAndFlush(order);

        // Xóa deduction của ingredient không còn trong đơn
        for (Long ingId : existingByIngId.keySet()) {
            if (!newUsageMap.containsKey(ingId))
                orderStockDeductionRepository.delete(existingByIngId.get(ingId));
        }

        // Upsert deduction records
        upsertDeductions(orderId, warehouseId, now, newUsageMap, existingByIngId,
                ingredientUnitCostMap);

        // ── 9. Tính lại giá ──────────────────────────────────────────────
        // Item-level discount
        BigDecimal itemDiscountTotal = BigDecimal.ZERO;
        for (OrderItem oi : newItems) {
            int pct = oi.getDiscountPercent() != null ? oi.getDiscountPercent() : 0;
            if (pct == 0) continue;
            BigDecimal lineGross = oi.getUnitPrice().multiply(oi.getQuantity());
            itemDiscountTotal = itemDiscountTotal.add(
                    lineGross.multiply(BigDecimal.valueOf(pct))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }

        BigDecimal subtotalAfterItemDisc = subtotal.subtract(itemDiscountTotal);
        int currentDiscountRate = order.getDiscountRate() != null ? order.getDiscountRate() : 0;
        BigDecimal billDiscountAmt = calcDiscountAmount(subtotalAfterItemDisc,
                req.getDiscountAmount(), req.getDiscountRate(), currentDiscountRate);
        BigDecimal totalDiscount = itemDiscountTotal.add(billDiscountAmt)
                .setScale(2, RoundingMode.HALF_UP);

        // Cập nhật discountRate trên order
        int newDiscountRate = currentDiscountRate;
        if (req.getDiscountAmount() != null && req.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0
                && subtotal.compareTo(BigDecimal.ZERO) > 0) {
            newDiscountRate = totalDiscount.multiply(BigDecimal.valueOf(100))
                    .divide(subtotal, 0, RoundingMode.HALF_UP).intValue();
        } else if (itemDiscountTotal.compareTo(BigDecimal.ZERO) > 0 && req.getDiscountRate() == null) {
            newDiscountRate = totalDiscount.multiply(BigDecimal.valueOf(100))
                    .divide(subtotal, 0, RoundingMode.HALF_UP).intValue();
        } else if (req.getDiscountRate() != null) {
            newDiscountRate = req.getDiscountRate();
        }

        BigDecimal afterDiscount = subtotal.subtract(totalDiscount);
        BigDecimal[] vatResult   = calcVatForItems(newItems, subtotal, afterDiscount);
        BigDecimal exclusiveVat  = vatResult[0];
        BigDecimal totalVat      = vatResult[1];

        // Surcharge
        BigDecimal surcharge;
        String surchargeDetail;
        if (req.getSurchargeItems() != null) {
            surcharge = calcSurchargeTotal(req.getSurchargeItems());
            surchargeDetail = serializeSurchargeItems(req.getSurchargeItems());
        } else if (req.getSurcharge() != null) {
            surcharge = req.getSurcharge();
            surchargeDetail = order.getSurchargeDetail();
        } else {
            surcharge = order.getSurcharge() != null ? order.getSurcharge() : BigDecimal.ZERO;
            surchargeDetail = order.getSurchargeDetail();
        }

        // ── 10. Apply lên order ───────────────────────────────────────────
        order.getOrderItems().addAll(newItems);
        order.setSubtotal(subtotal);
        order.setDiscountRate(newDiscountRate);
        order.setDiscountAmount(totalDiscount);
        order.setVatAmount(totalVat);
        order.setTotalAmount(afterDiscount);
        order.setSurcharge(surcharge);
        order.setSurchargeDetail(surchargeDetail);
        order.setFinalAmount(afterDiscount.add(exclusiveVat).add(surcharge));

        // Thông tin người nhận / đơn
        if (req.getOrderedByName() != null)  order.setOrderedByName(req.getOrderedByName());
        if (req.getReceiverName() != null)   order.setReceiverName(req.getReceiverName());
        if (req.getDeliveryAddress() != null) {
            order.setDeliveryAddress(req.getDeliveryAddress());
            order.setShippingAddress(req.getDeliveryAddress());
        }
        if (req.getDeliveryDatetime() != null) order.setDeliveryDatetime(req.getDeliveryDatetime());
        if (req.getNotes() != null)           order.setNotes(req.getNotes());
        if (req.getShowPrices() != null)      order.setShowPrices(req.getShowPrices());
        if (req.getHideAllPrices() != null) {
            order.setHideAllPrices(req.getHideAllPrices());
            if (req.getHideAllPrices()) order.setShowPrices(true);
        }
        if (req.getPaymentMethod() != null)   order.setPaymentMethod(req.getPaymentMethod());

        order.setUpdatedAt(now);
        Order saved = orderRepository.save(order);

        // ── 11. Warehouse receipt (điều chỉnh kho) ────────────────────────
        if (!receiptItems.isEmpty()) {
            String editorName = editor.getFullName() != null ? editor.getFullName() : editor.getUsername();
            WarehouseReceipt receipt = WarehouseReceipt.builder()
                    .receiptCode(generateReceiptCode())
                    .receiptType(WarehouseReceipt.ReceiptType.EXPORT_ORDER)
                    .order(saved).warehouse(warehouse)
                    .note("Điều chỉnh đơn #" + saved.getOrderCode()
                            + " — yc: " + req.getRequestedBy()
                            + " | lý do: " + req.getEditReason())
                    .createdBy(editor).createdByName(editorName)
                    .costStatus(WarehouseReceipt.CostStatus.CONFIRMED)
                    .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();
            for (WarehouseReceiptItem ri : receiptItems) ri.setReceipt(receipt);
            receipt.getItems().addAll(receiptItems);
            warehouseReceiptRepository.save(receipt);
        }

        // ── 12. Broadcast stock update ────────────────────────────────────
        Set<Long> allChanged = new HashSet<>();
        allChanged.addAll(newUsageMap.keySet());
        allChanged.addAll(oldUsageMap.keySet());
        for (Long ingId : allChanged) {
            ingredientStockRepository.findByIngredientIdAndWarehouseId(ingId, warehouseId)
                    .ifPresent(s -> cartHoldService.broadcastIngredientStockUpdate(
                            warehouseId, ingId, s.getStockQuantity()));
        }

        // ── 13. Cập nhật deliveryInfo (tài xế) nếu có ────────────────────────
        if (req.getDeliveryInfo() != null) {
            try {
                String json = objectMapper.writeValueAsString(req.getDeliveryInfo());
                saved.setDeliveryInfoJson(json);
                saved = orderRepository.save(saved);
            } catch (Exception e) {
                log.warn("[SUPER_SELLER_UPDATE] Lỗi serialize deliveryInfo: {}", e.getMessage());
            }
        }

        // ── 14. Order log ─────────────────────────────────────────────────
        String editorName = editor.getFullName() != null ? editor.getFullName() : editor.getUsername();
        // actorRole: do FE gửi lên (role người yêu cầu sửa), fallback về SELLER
        String actorRole = req.getRequestedByRole() != null && !req.getRequestedByRole().isBlank()
                ? req.getRequestedByRole().toUpperCase()
                : "SELLER";
        String note = String.format("Yêu cầu bởi: %s | Lý do: %s | Người thực hiện: %s",
                req.getRequestedBy(), req.getEditReason(), editorName);
        orderLogRepository.save(OrderLog.builder()
                .order(saved)
                .action("ORDER_UPDATED")
                .actorName(req.getRequestedBy())
                .actorRole(actorRole)
                .note(note)
                .createdAt(now)
                .build());

        log.info("[SUPER_SELLER_UPDATE] orderId={} editor={} requestedBy={} role={} reason={}",
                orderId, editorName, req.getRequestedBy(), actorRole, req.getEditReason());

        // ── 15. WS + Notification ─────────────────────────────────────────────
        // Gửi event ORDER_EDITED — frontend EditOrderModal đang mở sẽ bắt được
        // editorName trong payload để hiện banner "X vừa sửa đơn này"
        String editedPayload = String.format(
                "{\"eventType\":\"ORDER_EDITED\",\"orderId\":%d,\"referenceId\":\"%d\",\"orderCode\":\"%s\",\"editorName\":\"%s\"}",
                saved.getId(), saved.getId(), saved.getOrderCode(), editorName);
        String editedMsg = String.format("[Đơn %s] %s vừa cập nhật đơn này", saved.getOrderCode(), editorName);
        notifyOrderUpdated(saved, editorUserId, editedMsg, editedPayload);

        return orderService.getOrderById(orderId);
    }

    private void notifyOrderUpdated(Order order, Long actorUserId,
                                    String message, String payload) {
        Set<Long> notified = new HashSet<>();
        OrderStatus status = order.getStatus();
        final String EVENT = "ORDER_EDITED";

        log.info("[WS_DEBUG] notifyOrderUpdated — orderId={} orderCode={} status={} actorUserId={} payload={}",
                order.getId(), order.getOrderCode(), status, actorUserId, payload);

        // ── Người tạo đơn — gửi cho TẤT CẢ roles để đảm bảo nhận được
        // dù họ đang login bằng role nào (SELLER, WAREHOUSE, v.v.)
        if (order.getUser() != null) {
            User creator = order.getUser();
            if (actorUserId == null || !actorUserId.equals(creator.getId())) {
                Set<Role> creatorRoles = creator.getAllRoles();
                if (creatorRoles.isEmpty()) {
                    String fallback = creator.getRole() != null ? creator.getRole().name() : "SELLER";
                    log.info("[WS_DEBUG] → gửi cho creator userId={} name={} role={} (fallback)", creator.getId(), creator.getFullName(), fallback);
                    notificationService.sendToUser(creator, fallback, EVENT, message, payload);
                } else {
                    for (Role r : creatorRoles) {
                        log.info("[WS_DEBUG] → gửi cho creator userId={} name={} role={}", creator.getId(), creator.getFullName(), r.name());
                        notificationService.sendToUser(creator, r.name(), EVENT, message, payload);
                    }
                }
                notified.add(creator.getId());
            } else {
                log.info("[WS_DEBUG] → bỏ qua creator userId={} vì là actor", creator.getId());
            }
        } else {
            log.warn("[WS_DEBUG] → order.getUser() = null, không tìm được creator");
        }

        // ── DELIVERING: thêm WAREHOUSE + SUPER_WAREHOUSE ─────────────────────
        if (status == OrderStatus.DELIVERING) {
            for (Role r : List.of(Role.WAREHOUSE, Role.SUPER_WAREHOUSE)) {
                List<User> users = userRepository.findByRoleAndIsLockAccountFalse(r);
                log.info("[WS_DEBUG] → DELIVERING: tìm thấy {} user role={}", users.size(), r);
                users.forEach(u -> {
                    if (!notified.contains(u.getId())
                            && (actorUserId == null || !actorUserId.equals(u.getId()))) {
                        log.info("[WS_DEBUG]   → gửi userId={} name={} role={}", u.getId(), u.getFullName(), r.name());
                        notificationService.sendToUser(u, r.name(), EVENT, message, payload);
                        notified.add(u.getId());
                    }
                });
            }
        }

        // ── PENDING_PAYMENT / COMPLETED: thêm OWNER, ACCOUNTANT, SUPER_ACCOUNTANT ──
        if (status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.COMPLETED) {
            for (Role r : List.of(Role.OWNER, Role.ACCOUNTANT, Role.SUPER_ACCOUNTANT)) {
                List<User> users = userRepository.findByRoleAndIsLockAccountFalse(r);
                log.info("[WS_DEBUG] → PENDING_PAYMENT/COMPLETED: tìm thấy {} user role={}", users.size(), r);
                users.forEach(u -> {
                    if (!notified.contains(u.getId())
                            && (actorUserId == null || !actorUserId.equals(u.getId()))) {
                        log.info("[WS_DEBUG]   → gửi userId={} name={} role={}", u.getId(), u.getFullName(), r.name());
                        notificationService.sendToUser(u, r.name(), EVENT, message, payload);
                        notified.add(u.getId());
                    }
                });
            }
        }

        log.info("[WS_DEBUG] notifyOrderUpdated xong — đã gửi cho {} user: {}", notified.size(), notified);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void applyCustomerChange(Order order, Long customerId) {
        Customer c = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy khách hàng: " + customerId));
        if (!Boolean.TRUE.equals(c.getIsActive()))
            throw new RuntimeException("Khách hàng đã tạm ngưng");

        boolean isCompany = c.getCustomerType() == com.nhatnam.server.entity.Customer.CustomerType.COMPANY;
        order.setCustomer(c);
        order.setCustomerType(isCompany ? "COMPANY" : "RETAIL");
        if (isCompany) {
            order.setCustomerName(firstNonBlank(c.getCompanyName(), c.getContactName(), c.getName()));
            order.setCompanyName(c.getCompanyName());
            order.setTaxCode(c.getTaxCode());
            order.setContactName(c.getContactName());
            order.setCompanyPhone(c.getCompanyPhone());
            order.setCompanyAddress(c.getCompanyAddress());
        } else {
            order.setCustomerName(firstNonBlank(c.getName(), c.getContactName()));
            order.setCompanyName(null); order.setTaxCode(null);
            order.setContactName(null); order.setCompanyPhone(null); order.setCompanyAddress(null);
        }
        order.setCustomerPhone(c.getPhone());
        order.setCustomerEmail(c.getEmail());
        if (c.getDiscountRate() > 0) order.setDiscountRate(c.getDiscountRate());
    }

    private Map<Long, BigDecimal> buildDeltaMap(Map<Long, BigDecimal> oldMap,
                                                Map<Long, BigDecimal> newMap,
                                                boolean noExistingDeductions) {
        if (noExistingDeductions) return new LinkedHashMap<>(newMap);
        Map<Long, BigDecimal> delta = new LinkedHashMap<>();
        Set<Long> all = new HashSet<>();
        all.addAll(oldMap.keySet()); all.addAll(newMap.keySet());
        for (Long id : all) {
            BigDecimal d = newMap.getOrDefault(id, BigDecimal.ZERO)
                    .subtract(oldMap.getOrDefault(id, BigDecimal.ZERO));
            if (d.compareTo(BigDecimal.ZERO) != 0) delta.put(id, d);
        }
        return delta;
    }

    private void applyStockDelta(Order order, Warehouse warehouse, long now,
                                 Map<Long, BigDecimal> deltaMap,
                                 Map<Long, BigDecimal> oldUsageMap,
                                 Map<Long, OrderStockDeduction> existingByIngId,
                                 Map<Long, BigDecimal> costMap,
                                 List<WarehouseReceiptItem> receiptItems) {
        Long warehouseId = warehouse.getId();
        Long orderId = order.getId();

        for (Map.Entry<Long, BigDecimal> e : deltaMap.entrySet()) {
            Long ingId = e.getKey();
            BigDecimal delta = e.getValue();

            Ingredient ing = ingredientRepository.findById(ingId)
                    .orElseThrow(() -> new RuntimeException("Ingredient not found: " + ingId));
            IngredientStock stock = ingredientStockRepository
                    .findByIngredientIdAndWarehouseId(ingId, warehouseId)
                    .orElseThrow(() -> new RuntimeException(
                            "Nguyên liệu '" + ing.getName() + "' chưa cấu hình tồn kho cho kho '" + warehouse.getName() + "'"));

            BigDecimal before = stock.getStockQuantity();
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                // Tăng usage → trừ kho
                if (stock.getStockQuantity().compareTo(delta) < 0)
                    throw new BusinessException(String.format(
                            "Không đủ tồn kho '%s' tại kho '%s' (còn: %s, cần thêm: %s %s)",
                            ing.getName(), warehouse.getName(),
                            stock.getStockQuantity(), delta, ing.getUnit()));

                BigDecimal after = before.subtract(delta).setScale(3, RoundingMode.HALF_UP);
                stock.setStockQuantity(after); stock.setUpdatedAt(now);
                ingredientStockRepository.save(stock);

                BigDecimal cost = fifoDeductService.deduct(orderId, warehouse, ing, delta, now);
                costMap.put(ingId, delta.compareTo(BigDecimal.ZERO) > 0 && cost.compareTo(BigDecimal.ZERO) > 0
                        ? cost.divide(delta, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO);

                receiptItems.add(WarehouseReceiptItem.builder()
                        .ingredientId(ingId).ingredientNameSnapshot(ing.getName())
                        .ingredientUnitSnapshot(ing.getUnit()).ingredientImageUrlSnapshot(ing.getImageUrl())
                        .quantity(delta.negate()).quantityBefore(before)
                        .quantityAfter(after).difference(delta.negate()).build());

            } else {
                // Giảm usage → hoàn kho
                BigDecimal restore = delta.abs();
                BigDecimal after = before.add(restore).setScale(3, RoundingMode.HALF_UP);
                stock.setStockQuantity(after); stock.setUpdatedAt(now);
                ingredientStockRepository.save(stock);
                restoreStock(orderId, ingId, restore, now);
                costMap.put(ingId, BigDecimal.ZERO);

                receiptItems.add(WarehouseReceiptItem.builder()
                        .ingredientId(ingId).ingredientNameSnapshot(ing.getName())
                        .ingredientUnitSnapshot(ing.getUnit()).ingredientImageUrlSnapshot(ing.getImageUrl())
                        .quantity(restore).quantityBefore(before)
                        .quantityAfter(after).difference(restore).build());
            }
        }

        // Cost map cho ingredient không thay đổi (giữ nguyên cost cũ)
        for (Long ingId : oldUsageMap.keySet()) {
            if (!deltaMap.containsKey(ingId)) {
                BigDecimal oldQty = oldUsageMap.get(ingId);
                if (oldQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal oldCost = orderStockDeductionRepository.findByOrderId(orderId).stream()
                            .filter(d -> d.getIngredientStock().getIngredientId().equals(ingId))
                            .map(d -> d.getCostPrice() != null
                                    ? d.getCostPrice().multiply(d.getQuantity()) : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    costMap.put(ingId, oldCost.divide(oldQty, 6, RoundingMode.HALF_UP));
                }
            }
        }
    }

    private void restoreStock(Long orderId, Long ingId, BigDecimal restore, long now) {
        List<OrderStockDeduction> deductions = orderStockDeductionRepository.findByOrderId(orderId)
                .stream()
                .filter(d -> d.getIngredientStock().getIngredientId().equals(ingId))
                .sorted(Comparator.comparingLong(OrderStockDeduction::getId).reversed())
                .collect(Collectors.toList());

        BigDecimal remaining = restore;
        for (OrderStockDeduction d : deductions) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            IngredientStock stock = d.getIngredientStock();
            BigDecimal canRestore = remaining.min(d.getQuantity());
            remaining = remaining.subtract(canRestore);
            BigDecimal newQty = d.getQuantity().subtract(canRestore);
            if (newQty.compareTo(BigDecimal.ZERO) <= 0)
                orderStockDeductionRepository.delete(d);
            else { d.setQuantity(newQty); orderStockDeductionRepository.save(d); }
            stock.setStockQuantity(stock.getStockQuantity().add(canRestore).setScale(3, RoundingMode.HALF_UP));
            stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);
        }
    }

    private void upsertDeductions(Long orderId, Long warehouseId, long now,
                                  Map<Long, BigDecimal> newUsageMap,
                                  Map<Long, OrderStockDeduction> existingByIngId,
                                  Map<Long, BigDecimal> costMap) {
        for (Map.Entry<Long, BigDecimal> e : newUsageMap.entrySet()) {
            Long ingId = e.getKey();
            BigDecimal qty = e.getValue();
            if (qty.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal unitCost = costMap.getOrDefault(ingId, BigDecimal.ZERO);

            if (existingByIngId.containsKey(ingId)) {
                OrderStockDeduction existing = orderStockDeductionRepository
                        .findById(existingByIngId.get(ingId).getId()).orElse(null);
                if (existing != null) {
                    existing.setQuantity(qty);
                    if (unitCost.compareTo(BigDecimal.ZERO) > 0) existing.setCostPrice(unitCost);
                    orderStockDeductionRepository.save(existing);
                }
            } else {
                IngredientStock st = ingredientStockRepository
                        .findByIngredientIdAndWarehouseId(ingId, warehouseId)
                        .orElseThrow(() -> new RuntimeException("Stock not found: " + ingId));
                orderStockDeductionRepository.save(OrderStockDeduction.builder()
                        .orderId(orderId).ingredientStock(st)
                        .quantity(qty).costPrice(unitCost).createdAt(now).build());
            }
        }
    }

    private OrderItem buildOrderItem(Order order, CreateOrderRequest.OrderItemRequest itemReq,
                                     Map<Long, BigDecimal> usageMap) {
        Product product = productRepository.findByIdAndIsActiveTrue(itemReq.getProductId())
                .orElseThrow(() -> new RuntimeException("Sản phẩm không tồn tại: " + itemReq.getProductId()));

        // Resolve price
        BigDecimal unitPrice;
        String priceMode;
        Long tierId = null;
        String tierName = null;
        BigDecimal tierPriceSnap = null;
        Integer discountPct = null;

        if (Boolean.TRUE.equals(itemReq.getIsManualPrice()) && itemReq.getSentUnitPrice() != null) {
            unitPrice = itemReq.getSentUnitPrice();
            priceMode = "MANUAL";
        } else if ("TIER".equals(itemReq.getPriceMode()) && itemReq.getTierId() != null) {
            ProductPriceTier tier = priceTierRepository.findById(itemReq.getTierId())
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy tier: " + itemReq.getTierId()));
            unitPrice = tier.getPrice();
            priceMode = "TIER";
            tierId = tier.getId();
            tierName = tier.getTierName();
            tierPriceSnap = tier.getPrice();
        } else {
            unitPrice = product.getBasePrice();
            priceMode = "BASE";
            discountPct = itemReq.getDiscountPercent();
        }

        boolean isBOX = "BOX".equals(itemReq.getSaleType());
        Integer unitsPerBox = isBOX ? product.getUnitsPerBox() : null;
        BigDecimal effectiveMultiplier = (unitsPerBox != null && unitsPerBox > 0)
                ? itemReq.getQuantity().multiply(BigDecimal.valueOf(unitsPerBox))
                : itemReq.getQuantity();
        BigDecimal subtotal = unitPrice.multiply(effectiveMultiplier).setScale(2, RoundingMode.HALF_UP);

        int vatRatePct = itemReq.getVatRate() != null ? itemReq.getVatRate()
                : (product.getVatRate() != null ? product.getVatRate().getPercentage() : 0);

        List<ProductIngredient> ings = productIngredientRepository.findByProductId(product.getId());
        String unit = (!ings.isEmpty() && ings.get(0).getIngredientUnitSnapshot() != null)
                ? ings.get(0).getIngredientUnitSnapshot()
                : (product.getUnit() != null ? product.getUnit() : "kg");

        OrderItem item = OrderItem.builder()
                .order(order)
                .productId(product.getId()).productName(product.getName())
                .productImageUrl(product.getImageUrl()).unit(unit)
                .categorySnapshot(product.getCategory()).skuSnapshot(product.getSku())
                .packagingDescriptionSnapshot(product.getPackagingDescription())
                .maxDiscountRateSnapshot(product.getMaxDiscountRate())
                .saleType(itemReq.getSaleType() != null ? itemReq.getSaleType() : "RETAIL")
                .unitsPerBox(unitsPerBox)
                .basePrice(product.getBasePrice()).unitPrice(unitPrice).priceMode(priceMode)
                .tierId(tierId).tierName(tierName).tierPriceSnapshot(tierPriceSnap)
                .discountPercent(discountPct)
                .vatRate(vatRatePct)
                .vatMode(product.getVatMode() != null ? product.getVatMode().name() : "INCLUSIVE")
                .vatAmount(BigDecimal.ZERO)
                .quantity(itemReq.getQuantity()).subtotal(subtotal)
                .notes(itemReq.getNotes())
                .orderItemIngredients(new ArrayList<>())
                .build();

        // Collect ingredient usage
        BigDecimal effQty = (unitsPerBox != null && unitsPerBox > 0)
                ? itemReq.getQuantity().multiply(BigDecimal.valueOf(unitsPerBox))
                : itemReq.getQuantity();
        List<OrderItemIngredient> oiIngredients = new ArrayList<>();
        for (ProductIngredient pi : ings) {
            Long ingId = pi.getIngredientId();
            BigDecimal qtyPerUnit = pi.getQty() != null ? pi.getQty() : BigDecimal.ONE;
            BigDecimal usage = Boolean.TRUE.equals(pi.getCanOverride())
                    ? effQty.multiply(qtyPerUnit).setScale(3, RoundingMode.HALF_UP)
                    : effQty.setScale(0, RoundingMode.CEILING).multiply(qtyPerUnit).setScale(3, RoundingMode.HALF_UP);
            usageMap.merge(ingId, usage, BigDecimal::add);
            oiIngredients.add(OrderItemIngredient.builder()
                    .orderItem(item).ingredientId(ingId)
                    .ingredientName(pi.getIngredientNameSnapshot())
                    .ingredientImageUrl(pi.getIngredientImageUrlSnapshot())
                    .unit(pi.getIngredientUnitSnapshot())
                    .quantityUsed(usage).qtyPerUnit(qtyPerUnit).build());
        }
        item.setOrderItemIngredients(oiIngredients);
        return item;
    }

    private BigDecimal calcDiscountAmount(BigDecimal subtotal, BigDecimal amtInput,
                                          Integer rateInput, int rateCurrent) {
        if (amtInput != null && amtInput.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal max = subtotal.multiply(BigDecimal.valueOf(10))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            return amtInput.min(max).setScale(2, RoundingMode.HALF_UP);
        }
        int rate = rateInput != null ? rateInput : rateCurrent;
        return rate > 0
                ? subtotal.multiply(BigDecimal.valueOf(rate))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
    }

    private BigDecimal[] calcVatForItems(List<OrderItem> items,
                                         BigDecimal subtotal, BigDecimal afterDiscount) {
        BigDecimal exclusiveVat = BigDecimal.ZERO, totalVat = BigDecimal.ZERO;
        for (OrderItem item : items) {
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
            List<CreateOrderRequest.SurchargeItem> f = items.stream()
                    .filter(i -> i.getAmount() != null && i.getAmount().compareTo(BigDecimal.ZERO) > 0
                            && i.getName() != null && !i.getName().isBlank())
                    .toList();
            return f.isEmpty() ? null : objectMapper.writeValueAsString(f);
        } catch (Exception e) { return null; }
    }

    private String generateReceiptCode() {
        String ts = LocalDateTime.now(TZ).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "ADJ-SS-" + ts;
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}