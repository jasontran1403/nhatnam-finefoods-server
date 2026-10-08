package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.order.OrderReturnRequest;
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
import java.util.*;

/**
 * Xử lý trả hàng do feedback xấu.
 *
 * Trường hợp 1 — trả phần chưa sử dụng:
 * - Phần đã sử dụng giữ trong đơn, đánh dấu "Tặng khách hàng", giá = 0.
 * - Phần trả lại → hoàn kho (ingredient stock + expiry lot).
 * - Tính lại finalAmount.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderReturnService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderLogRepository orderLogRepository;
    private final OrderStockDeductionRepository orderStockDeductionRepository;
    private final IngredientStockRepository ingredientStockRepository;
    private final IngredientExpiryRepository ingredientExpiryRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final CartHoldService cartHoldService;
    // BUG FIX 4.4 (race lost update return)
    private final StockMutationService stockMutationService;

    @Transactional
    public OrderResponse processReturn(Long orderId, Long actorUserId, OrderReturnRequest req) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy đơn hàng"));

        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng"));

        // Kiểm tra quyền
        Set<Role> roles = actor.getAllRoles();
        boolean allowed = roles.contains(Role.OWNER) || roles.contains(Role.ADMIN)
                || roles.contains(Role.SUPERADMIN) || roles.contains(Role.SUPER_SELLER)
                || roles.contains(Role.SUPER_ACCOUNTANT);
        if (!allowed) {
            throw new BusinessException("Bạn không có quyền trả hàng");
        }

        // Kiểm tra trạng thái đơn
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT
                && order.getStatus() != OrderStatus.COMPLETED) {
            throw new BusinessException("Chỉ có thể trả hàng khi đơn ở trạng thái Chờ thanh toán hoặc Hoàn thành");
        }

        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BusinessException("Phải có ít nhất 1 sản phẩm trả");
        }

        long now = System.currentTimeMillis();
        String actorName = actor.getFullName() != null ? actor.getFullName() : actor.getUsername();
        String actorRole = roles.contains(Role.OWNER) ? "OWNER"
                : roles.contains(Role.ADMIN) ? "ADMIN"
                : roles.contains(Role.SUPER_SELLER) ? "SUPER_SELLER"
                : roles.contains(Role.SUPER_ACCOUNTANT) ? "SUPER_ACCOUNTANT"
                : "ADMIN";

        StringBuilder logNotes = new StringBuilder();
        logNotes.append("Trả hàng do feedback xấu. Lý do: ").append(req.getReason() != null ? req.getReason() : "—");

        for (OrderReturnRequest.ReturnItemRequest returnItem : req.getItems()) {
            OrderItem orderItem = orderItemRepository.findById(returnItem.getOrderItemId())
                    .orElseThrow(() -> new BusinessException("Không tìm thấy sản phẩm #" + returnItem.getOrderItemId()));

            if (!orderItem.getOrder().getId().equals(orderId)) {
                throw new BusinessException("Sản phẩm không thuộc đơn hàng này");
            }

            BigDecimal usedQty = returnItem.getUsedQuantity();
            BigDecimal returnQty = returnItem.getReturnQuantity();

            if (usedQty == null || usedQty.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("Số lượng đã dùng không hợp lệ");
            }
            if (returnQty == null || returnQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("Số lượng trả phải lớn hơn 0");
            }

            BigDecimal totalQty = usedQty.add(returnQty);
            if (totalQty.compareTo(orderItem.getQuantity()) > 0) {
                throw new BusinessException(String.format(
                        "Tổng (đã dùng + trả lại) = %s vượt quá số lượng đơn = %s cho %s",
                        totalQty.toPlainString(),
                        orderItem.getQuantity().toPlainString(),
                        orderItem.getProductName()));
            }

            // ── Cập nhật OrderItem: giữ phần đã dùng, giá = 0, note tặng khách ──
            orderItem.setQuantity(usedQty);
            orderItem.setUnitPrice(BigDecimal.ZERO);
            orderItem.setSubtotal(BigDecimal.ZERO);
            orderItem.setVatAmount(BigDecimal.ZERO);
            String existingNote = orderItem.getNotes() != null ? orderItem.getNotes() + " | " : "";
            orderItem.setNotes(existingNote + "Tặng khách hàng (feedback), không tính tiền. Trả kho: "
                    + returnQty.toPlainString() + " " + (orderItem.getUnit() != null ? orderItem.getUnit() : ""));
            orderItemRepository.save(orderItem);

            // ── Hoàn kho phần trả lại ──
            restoreStockForItem(orderId, orderItem, returnQty, now);

            logNotes.append(String.format(" | %s: đã dùng %s, trả kho %s %s",
                    orderItem.getProductName(),
                    usedQty.toPlainString(),
                    returnQty.toPlainString(),
                    orderItem.getUnit() != null ? orderItem.getUnit() : ""));
        }

        // ── Tính lại tổng tiền đơn hàng ──
        recalcOrderTotals(order);
        order.setUpdatedAt(now);
        orderRepository.save(order);

        // ── Ghi log ──
        orderLogRepository.save(OrderLog.builder()
                .order(order)
                .action("ITEM_RETURNED")
                .actorName(actorName)
                .actorRole(actorRole)
                .note(logNotes.toString())
                .createdAt(now)
                .build());

        log.info("[ORDER_RETURN] orderId={} actor={} items={}", orderId, actorName, req.getItems().size());

        return orderService.getOrderById(orderId);
    }

    /**
     * Hoàn lại tồn kho theo tỷ lệ từ deduction records.
     *
     * <p>BUG FIX 4.2 (rounding tích luỹ):
     * Trước: mỗi deduction làm tròn restoreQty riêng → tổng có thể không khớp
     *         với returnQty (VD trả 1kg từ 3 deduction × 1kg → mỗi cái restore
     *         0.333, tổng 0.999, thiếu 0.001).
     * Sau: track accumulated. Ở deduction CUỐI CÙNG, dùng phần còn lại
     *      = returnQty - accumulated → tổng khớp chính xác returnQty.
     *
     * <p>BUG FIX 4.4 (race lost update):
     * Trước: read stock → add restoreQty → save. 2 return song song cùng
     *         ingredient → mất số hoàn của 1 request.
     * Sau: atomic increase + addCostValue.
     *
     * <p>Deadlock guard: sort deductions theo ingredientId ASC.
     */
    private void restoreStockForItem(Long orderId, OrderItem orderItem, BigDecimal returnQty, long now) {
        if (returnQty == null || returnQty.compareTo(BigDecimal.ZERO) <= 0) return;

        List<OrderStockDeduction> allDeductions = orderStockDeductionRepository.findByOrderId(orderId);
        if (allDeductions.isEmpty()) {
            log.warn("[ORDER_RETURN_STOCK] Không có deduction cho orderId={}", orderId);
            return;
        }

        // Group theo ingredient để tính tỷ lệ return CHO TỪNG NGUYÊN LIỆU riêng.
        // Vì 1 orderItem có thể dùng nhiều ingredient (theo công thức), returnQty là
        // của SẢN PHẨM — ta phải quy ra "tỷ lệ trả" (returnQty / originalItemQty) rồi
        // áp lên tổng deduction của từng ingredient.
        BigDecimal originalItemQty = returnQty.add(orderItem.getQuantity());
        if (originalItemQty.compareTo(BigDecimal.ZERO) <= 0) return;
        BigDecimal returnRatio = returnQty.divide(originalItemQty, 6, RoundingMode.HALF_UP);

        // ── Sort để guard deadlock ──
        // Ưu tiên 1: ingredientId ASC (khớp guard ở createOrder/cancel/update).
        // Ưu tiên 2: id DESC (reverse FIFO trong cùng ingredient).
        List<OrderStockDeduction> sortedDeductions = allDeductions.stream()
                .sorted(Comparator
                        .comparing((OrderStockDeduction d) -> d.getIngredientStock().getIngredientId())
                        .thenComparing(OrderStockDeduction::getId, Comparator.reverseOrder()))
                .collect(java.util.stream.Collectors.toList());

        // Group after sort — LinkedHashMap giữ nguyên thứ tự đã sort
        Map<Long, List<OrderStockDeduction>> byIngredient = new LinkedHashMap<>();
        for (OrderStockDeduction d : sortedDeductions) {
            byIngredient.computeIfAbsent(d.getIngredientStock().getIngredientId(),
                    k -> new ArrayList<>()).add(d);
        }

        for (Map.Entry<Long, List<OrderStockDeduction>> group : byIngredient.entrySet()) {
            List<OrderStockDeduction> ingDeductions = group.getValue();   // đã sort id DESC

            // Tổng đã trừ cho ingredient này trong đơn
            BigDecimal totalDeductedForIng = ingDeductions.stream()
                    .map(d -> d.getQuantity() != null ? d.getQuantity() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Số cần hoàn cho ingredient này. Làm tròn 3 chữ số như đơn vị lưu ở DB.
            BigDecimal remainingToRestore = totalDeductedForIng.multiply(returnRatio)
                    .setScale(3, RoundingMode.HALF_UP);

            // ── PEEL REVERSE FIFO ──
            // Đi từ deduction mới nhất (id lớn nhất) trở về cũ nhất, hoàn tối đa
            // = min(số còn phải hoàn, số đã trừ ở lô đó). Khi hết, thoát.
            for (OrderStockDeduction d : ingDeductions) {
                if (remainingToRestore.compareTo(BigDecimal.ZERO) <= 0) break;

                BigDecimal deductedHere = d.getQuantity() != null ? d.getQuantity() : BigDecimal.ZERO;
                if (deductedHere.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal restoreHere = remainingToRestore.min(deductedHere)
                        .setScale(3, RoundingMode.HALF_UP);
                remainingToRestore = remainingToRestore.subtract(restoreHere);

                IngredientStock stock = d.getIngredientStock();
                Long ingId = stock.getIngredientId();
                Long whId  = stock.getWarehouse().getId();

                // Cộng tồn tổng — atomic
                stockMutationService.increase(ingId, whId, restoreHere, now);

                // Cộng cost value — dùng ĐÚNG giá vốn của deduction (không phải giá vốn
                // trung bình). Đây là điểm mấu chốt: lô cuối trả trước → giá vốn lô cuối
                // được hoàn về totalCostValue trước → khớp thực tế.
                if (d.getCostPrice() != null && d.getCostPrice().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal costToRestore = d.getCostPrice().multiply(restoreHere)
                            .setScale(2, RoundingMode.HALF_UP);
                    stockMutationService.addCostValue(ingId, whId, costToRestore, now);
                }

                // Hoàn vào ĐÚNG lô đã trừ. Nếu lô đã bị xoá (hiếm, có thể do adjust), tạo
                // lô mới với đúng HSD + giá vốn từ deduction.
                IngredientExpiry lot = d.getIngredientExpiry() != null
                        ? ingredientExpiryRepository.findById(d.getIngredientExpiry().getId()).orElse(null)
                        : null;
                if (lot != null) {
                    lot.setQuantity(lot.getQuantity().add(restoreHere));
                    lot.setUpdatedAt(now);
                    ingredientExpiryRepository.save(lot);
                } else {
                    ingredientExpiryRepository.save(IngredientExpiry.builder()
                            .warehouse(stock.getWarehouse())
                            .ingredientId(ingId)
                            .expiryDate(d.getExpiryDate())
                            .costPrice(d.getCostPrice() != null ? d.getCostPrice() : BigDecimal.ZERO)
                            .quantity(restoreHere)
                            .createdAt(now).updatedAt(now).build());
                }

                // Cập nhật deduction: hoàn hết → xoá, hoàn 1 phần → giảm quantity.
                // Việc này rất quan trọng: nếu sau này khách trả tiếp, hoặc cancel đơn,
                // deduction phản ánh đúng số còn "gắn" với đơn.
                if (restoreHere.compareTo(deductedHere) >= 0) {
                    orderStockDeductionRepository.delete(d);
                } else {
                    d.setQuantity(deductedHere.subtract(restoreHere));
                    orderStockDeductionRepository.save(d);
                }

                // Broadcast tồn kho mới cho các FE đang mở giỏ hàng
                ingredientStockRepository.findByIngredientIdAndWarehouseId(ingId, whId)
                        .ifPresent(reload -> cartHoldService.broadcastIngredientStockUpdate(
                                whId, ingId, reload.getStockQuantity()));
            }

            if (remainingToRestore.compareTo(new BigDecimal("0.001")) > 0) {
                // Không đủ deduction để hoàn — dữ liệu bất thường, log cảnh báo.
                log.warn("[ORDER_RETURN_STOCK] Còn {}kg chưa hoàn được cho orderId={} ingredientId={} "
                                + "— tổng deduction ({}) không đủ so với số cần hoàn.",
                        remainingToRestore, orderId, group.getKey(), totalDeductedForIng);
            }
        }

        log.info("[ORDER_RETURN_STOCK] Reverse-FIFO restored ratio={} for orderId={} product={}",
                returnRatio, orderId, orderItem.getProductName());
    }

    /**
     * Tính lại subtotal, discountAmount, finalAmount sau khi thay đổi items.
     */
    private void recalcOrderTotals(Order order) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;

        for (OrderItem item : items) {
            subtotal = subtotal.add(item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO);
            vatTotal = vatTotal.add(item.getVatAmount() != null ? item.getVatAmount() : BigDecimal.ZERO);
        }

        order.setSubtotal(subtotal);
        order.setVatAmount(vatTotal);

        BigDecimal surcharge = order.getSurcharge() != null ? order.getSurcharge() : BigDecimal.ZERO;
        BigDecimal discount = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;

        // finalAmount = subtotal - discount + surcharge (VAT inclusive model)
        BigDecimal finalAmount = subtotal.subtract(discount).add(surcharge);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) finalAmount = BigDecimal.ZERO;
        order.setFinalAmount(finalAmount);
    }
}