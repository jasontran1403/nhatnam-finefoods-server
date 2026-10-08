package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.expense.CreateExpenseVoucherRequest;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.serviceimpl.OrderServiceImpl;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Xử lý Hoàn/Đổi sản phẩm.
 *
 * <p>Flow gồm 2 bước độc lập (gọi riêng 2 API):
 * <ol>
 *   <li><b>Bước 1 — Hoàn/Đổi:</b>
 *       <ul>
 *         <li>Hoàn tiền: ghi note lên đơn gốc, trả về số tiền cần hoàn.</li>
 *         <li>Đổi SP: tạo đơn mới, set creditedFromSource = tổng tiền SP đổi,
 *             tính trạng thái đơn mới (COMPLETED/PARTIAL/PENDING).</li>
 *       </ul>
 *   </li>
 *   <li><b>Bước 2 — Xử lý hàng nhận về:</b>
 *       <ul>
 *         <li>DESTROY: nhập kho trước, sau đó xuất tiêu hủy, ghi lịch sử đầy đủ.</li>
 *         <li>RESTOCK: cộng lại vào lô đã xuất (hoặc tạo lô mới), tạo phiếu nhập kho.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Các fix trong phiên bản này</h3>
 * <ul>
 *   <li><b>Bug 1 (E5):</b> Kiểm tra tồn kho đủ trước khi tạo đơn đổi.</li>
 *   <li><b>Bug 3:</b> {@link #createRefundDisbursement} dùng ExpenseVoucherService.</li>
 *   <li><b>Bug RESTOCK-NPE (mới):</b> Bọc try-catch quanh mọi WebSocket broadcast để
 *       không rollback transaction; null-guard cho {@code OrderStockDeduction.quantity};
 *       log chi tiết từng bước restore.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExchangeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderLogRepository orderLogRepository;
    private final OrderStockDeductionRepository deductionRepository;
    private final IngredientStockRepository stockRepository;
    private final IngredientExpiryRepository expiryRepository;
    private final UserRepository userRepository;
    private final CartHoldService cartHoldService;
    private final OrderServiceImpl orderService;
    private final ProductIngredientRepository productIngredientRepository;
    private final IngredientRepository ingredientRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseReceiptRepository warehouseReceiptRepository;
    private final FifoDeductService fifoDeductService;
    // BUG FIX 4.2/4.4 (rounding + race exchange restore)
    private final StockMutationService stockMutationService;
    private final ExpenseVoucherService expenseVoucherService;

    // ─────────────────────────────────────────────────────────────────────────
    // REQUEST / RESPONSE DTOs (inner classes)
    // ─────────────────────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ReturnItemLine {
        private Long orderItemId;
        /** Số lượng cần xử lý (≤ originalQty). */
        private BigDecimal quantity;
    }

    /** Đơn sản phẩm mới khi đổi. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class NewOrderItemLine {
        private Long   productId;
        private String productName;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal vatRate;       // 0.0, 0.05, 0.08, 0.1 (đã chia 100)
        /** INCLUSIVE | EXCLUSIVE — FE gửi kèm từ product.vatMode */
        private String vatMode;
        private BigDecimal discountPct;   // % giảm giá (0–100)
        // Quy cách
        private String  saleType;         // RETAIL | BOX
        private Integer unitsPerBox;
        // Khung giá
        private Long   tierId;
        private String tierName;
    }

    /** Body cho bước 1 — Hoàn tiền. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RefundRequest {
        private List<ReturnItemLine> items;
        private String note;
    }

    /** Body cho bước 1 — Đổi sản phẩm. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExchangeRequest {
        /** Các sản phẩm trong đơn gốc cần đổi. */
        private List<ReturnItemLine> sourceItems;
        /** Sản phẩm mới. */
        private List<NewOrderItemLine> newItems;
        private BigDecimal discount;
        private String note;
    }

    /** Body cho bước 2 — Xử lý hàng nhận về. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RestockRequest {
        private Long sourceOrderId;
        private List<ReturnItemLine> items;
        /** DESTROY | RESTOCK */
        private String action;
        private String note;
    }

    /** Kết quả bước 1 Hoàn tiền. */
    @Data @Builder
    public static class RefundResult {
        private Long orderId;
        private String orderCode;
        /** Số tiền cần hoàn lại cho khách. */
        private BigDecimal refundAmount;
        private String note;
    }

    /** Request tạo phiếu chi hoàn tiền — hỗ trợ nhiều đơn. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class BulkRefundDisbursementRequest {
        private List<Long> orderIds;
        private String paymentMethod;
        private String bankName;
        private String transactionRef;
        private String receiverName;
        private String note;
    }

    /** Kết quả tạo phiếu chi bulk. */
    @Data @Builder
    public static class BulkRefundDisbursementResult {
        private String voucherCode;
        private BigDecimal totalAmount;
        private int orderCount;
        private String message;
        private List<String> orderCodes;
    }

    /** Request tạo phiếu chi hoàn tiền — 1 đơn EXCHANGE overpaid (backward compat). */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RefundDisbursementRequest {
        private java.math.BigDecimal amount;
        private String note;
        private Long orderVersion;
        private String paymentMethod;
        private String bankName;
        private String bankAccount;
        private String bankHolder;
    }

    /** Kết quả tạo phiếu chi. */
    @Data @Builder
    public static class RefundDisbursementResult {
        private String voucherCode;
        private java.math.BigDecimal amount;
        private String message;
    }

    /** Kết quả bước 1 Đổi sản phẩm. */
    @Data @Builder
    public static class ExchangeResult {
        private Long newOrderId;
        private String newOrderCode;
        private Long sourceOrderId;
        private String sourceOrderCode;
        private BigDecimal newTotal;
        private BigDecimal credited;
        private BigDecimal balance;
        private String newOrderStatus;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BƯỚC 1A — HOÀN TIỀN
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public RefundResult processRefund(Long orderId, Long actorId, RefundRequest req) {
        Order order = requireCompleted(orderId);
        User actor = requireUser(actorId);

        if (req.getItems() == null || req.getItems().isEmpty())
            throw new BusinessException("Phải chọn ít nhất 1 sản phẩm để hoàn");

        long now = System.currentTimeMillis();
        BigDecimal refundTotal = BigDecimal.ZERO;
        List<Map<String, Object>> noteLines = new ArrayList<>();

        BigDecimal orderSubtotal = order.getSubtotal() != null ? order.getSubtotal() : BigDecimal.ZERO;
        BigDecimal billDiscount  = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal discountRatio = (billDiscount.compareTo(BigDecimal.ZERO) > 0
                && orderSubtotal.compareTo(BigDecimal.ZERO) > 0)
                ? billDiscount.divide(orderSubtotal, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        for (ReturnItemLine line : req.getItems()) {
            OrderItem item = requireItem(line.getOrderItemId(), orderId);
            BigDecimal qty = line.getQuantity();
            validateQty(qty, item);

            BigDecimal prevReturned = item.getReturnedQty() != null ? item.getReturnedQty() : BigDecimal.ZERO;
            item.setReturnedQty(prevReturned.add(qty).min(item.getQuantity()));
            orderItemRepository.save(item);

            BigDecimal origQty = item.getQuantity();
            BigDecimal itemSubtotal = item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO;
            BigDecimal unitItemSubtotal = origQty.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : itemSubtotal.divide(origQty, 10, RoundingMode.HALF_UP);
            BigDecimal lineBeforeDiscount = unitItemSubtotal.multiply(qty);
            BigDecimal lineDiscount = lineBeforeDiscount.multiply(discountRatio);
            BigDecimal lineRefund = lineBeforeDiscount.subtract(lineDiscount)
                    .setScale(0, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO);
            refundTotal = refundTotal.add(lineRefund);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "REFUND");
            m.put("product", item.getProductName());
            m.put("qty", qty);
            m.put("unit", item.getUnit());
            m.put("amount", lineRefund);
            m.put("note", req.getNote());
            m.put("by", actor.getFullName());
            m.put("at", now);
            noteLines.add(m);
        }

        appendReturnNote(order, noteLines);

        BigDecimal prevPending = order.getPendingRefundAmount() != null
                ? order.getPendingRefundAmount() : BigDecimal.ZERO;
        order.setPendingRefundAmount(prevPending.add(refundTotal));
        order.setUpdatedAt(now);
        orderRepository.save(order);

        orderLogRepository.save(OrderLog.builder()
                .order(order).action("REFUND")
                .actorName(actor.getFullName()).actorRole("SELLER")
                .note("Hoàn tiền %,d đ cho %d sản phẩm. %s".formatted(
                        refundTotal.longValue(), req.getItems().size(),
                        req.getNote() != null ? req.getNote() : ""))
                .createdAt(now).build());

        return RefundResult.builder()
                .orderId(order.getId()).orderCode(order.getOrderCode())
                .refundAmount(refundTotal)
                .note(req.getNote())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BƯỚC 1B — ĐỔI SẢN PHẨM
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public ExchangeResult processExchange(Long orderId, Long actorId, ExchangeRequest req) {
        Order source = requireCompleted(orderId);
        User actor   = requireUser(actorId);

        if (req.getSourceItems() == null || req.getSourceItems().isEmpty())
            throw new BusinessException("Phải chọn ít nhất 1 sản phẩm từ đơn gốc");
        if (req.getNewItems() == null || req.getNewItems().isEmpty())
            throw new BusinessException("Phải chọn ít nhất 1 sản phẩm mới");

        long now = System.currentTimeMillis();

        BigDecimal orderSubtotal = source.getSubtotal() != null ? source.getSubtotal() : BigDecimal.ZERO;
        BigDecimal billDiscount  = source.getDiscountAmount() != null ? source.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal discountRatio = (billDiscount.compareTo(BigDecimal.ZERO) > 0
                && orderSubtotal.compareTo(BigDecimal.ZERO) > 0)
                ? billDiscount.divide(orderSubtotal, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal credited = BigDecimal.ZERO;
        List<Map<String, Object>> noteLines = new ArrayList<>();

        for (ReturnItemLine line : req.getSourceItems()) {
            OrderItem item = requireItem(line.getOrderItemId(), orderId);
            validateQty(line.getQuantity(), item);

            BigDecimal prevReturned = item.getReturnedQty() != null ? item.getReturnedQty() : BigDecimal.ZERO;
            item.setReturnedQty(prevReturned.add(line.getQuantity()).min(item.getQuantity()));
            orderItemRepository.save(item);

            BigDecimal itemSub = item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO;
            BigDecimal lineCredit = itemSub.subtract(itemSub.multiply(discountRatio))
                    .setScale(0, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO);
            credited = credited.add(lineCredit);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "EXCHANGE_SRC");
            m.put("product", item.getProductName());
            m.put("qty", line.getQuantity());
            m.put("unit", item.getUnit());
            m.put("credited", lineCredit);
            m.put("at", now);
            noteLines.add(m);
        }

        BigDecimal newSubtotal   = BigDecimal.ZERO;
        BigDecimal exclusiveVat  = BigDecimal.ZERO;
        for (NewOrderItemLine nl : req.getNewItems()) {
            BigDecimal qty       = nl.getQuantity() != null ? nl.getQuantity() : BigDecimal.ONE;
            BigDecimal unitPrice = nl.getUnitPrice() != null ? nl.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal discPct   = nl.getDiscountPct() != null ? nl.getDiscountPct() : BigDecimal.ZERO;

            BigDecimal effectiveQty = ("BOX".equals(nl.getSaleType()) && nl.getUnitsPerBox() != null && nl.getUnitsPerBox() > 0)
                    ? qty.multiply(BigDecimal.valueOf(nl.getUnitsPerBox()))
                    : qty;

            BigDecimal lineGross  = unitPrice.multiply(effectiveQty);
            BigDecimal lineDisc   = lineGross.multiply(discPct).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            BigDecimal afterDisc  = lineGross.subtract(lineDisc);

            BigDecimal vatRate = nl.getVatRate() != null ? nl.getVatRate() : BigDecimal.ZERO;
            if (vatRate.compareTo(BigDecimal.ZERO) > 0) {
                String vatMode = nl.getVatMode() != null ? nl.getVatMode() : "INCLUSIVE";
                if ("EXCLUSIVE".equals(vatMode)) {
                    exclusiveVat = exclusiveVat.add(afterDisc.multiply(vatRate).setScale(4, RoundingMode.HALF_UP));
                }
            }
            newSubtotal = newSubtotal.add(afterDisc);
        }

        BigDecimal extraDiscount = req.getDiscount() != null ? req.getDiscount() : BigDecimal.ZERO;
        BigDecimal newTotal = newSubtotal.subtract(extraDiscount).add(exclusiveVat)
                .max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP);
        BigDecimal balance = credited.subtract(newTotal);

        Warehouse newWarehouse = warehouseRepository.findById(source.getWarehouseId())
                .orElseThrow(() -> new BusinessException("Kho không tồn tại: " + source.getWarehouseId()));

        Map<Long, BigDecimal> ingredientUsageMap = new LinkedHashMap<>();
        for (NewOrderItemLine nl : req.getNewItems()) {
            if (nl.getProductId() == null) continue;
            BigDecimal qty     = nl.getQuantity() != null ? nl.getQuantity() : BigDecimal.ONE;
            String saleType    = nl.getSaleType() != null ? nl.getSaleType() : "RETAIL";
            Integer upb        = nl.getUnitsPerBox();
            BigDecimal effQty  = ("BOX".equals(saleType) && upb != null && upb > 0)
                    ? qty.multiply(BigDecimal.valueOf(upb))
                    : qty;

            List<ProductIngredient> ings = productIngredientRepository.findByProductId(nl.getProductId());
            for (ProductIngredient pi : ings) {
                BigDecimal ingQty = pi.getQty() != null ? pi.getQty() : BigDecimal.ONE;
                ingredientUsageMap.merge(pi.getIngredientId(),
                        effQty.multiply(ingQty).setScale(3, RoundingMode.HALF_UP),
                        BigDecimal::add);
            }
        }

        List<String> stockErrors = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : ingredientUsageMap.entrySet()) {
            Long ingId    = entry.getKey();
            BigDecimal needed = entry.getValue();
            if (needed.compareTo(BigDecimal.ZERO) <= 0) continue;

            Ingredient ing = ingredientRepository.findById(ingId).orElse(null);
            String ingName = ing != null ? ing.getName() : ("ingId=" + ingId);

            IngredientStock stock = stockRepository
                    .findByIngredientIdAndWarehouseId(ingId, newWarehouse.getId())
                    .orElse(null);

            if (stock == null) {
                stockErrors.add("'%s': không tồn tại trong kho (cần %s)".formatted(ingName, needed.toPlainString()));
                continue;
            }

            BigDecimal available = stock.getStockQuantity() != null ? stock.getStockQuantity() : BigDecimal.ZERO;
            if (available.compareTo(needed) < 0) {
                stockErrors.add("'%s': cần %s, tồn kho chỉ còn %s"
                        .formatted(ingName, needed.stripTrailingZeros().toPlainString(),
                                available.stripTrailingZeros().toPlainString()));
            }
        }
        if (!stockErrors.isEmpty()) {
            throw new BusinessException("Không đủ tồn kho để tạo đơn đổi sản phẩm:\n• "
                    + String.join("\n• ", stockErrors));
        }

        String newCode = orderService.generateOrderCodePublic();

        BigDecimal paidAmount = credited.min(newTotal);
        BigDecimal overpaid   = credited.subtract(newTotal).max(BigDecimal.ZERO);

        OrderStatus newStatus = OrderStatus.PREPARING;

        PaymentStatus paymentStatus;
        if (balance.compareTo(BigDecimal.ZERO) >= 0) {
            paymentStatus = PaymentStatus.PAID;
        } else {
            paymentStatus = paidAmount.compareTo(BigDecimal.ZERO) > 0
                    ? PaymentStatus.PARTIAL
                    : PaymentStatus.UNPAID;
        }

        Order newOrder = Order.builder()
                .orderCode(newCode)
                .user(source.getUser())
                .customer(source.getCustomer())
                .customerName(source.getCustomerName())
                .customerPhone(source.getCustomerPhone())
                .warehouseName(source.getWarehouseName())
                .warehouseId(source.getWarehouseId())
                .type(source.getType())
                .paymentMethod(source.getPaymentMethod())
                .status(newStatus)
                .paymentStatus(paymentStatus)
                .pendingPaymentAt(now)
                .discountRate(0)
                .vatRate(com.nhatnam.server.enumtype.VatRate.ZERO)
                .subtotal(newSubtotal)
                .discountAmount(extraDiscount)
                .vatAmount(exclusiveVat.setScale(2, RoundingMode.HALF_UP))
                .totalAmount(newSubtotal.subtract(extraDiscount).max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP))
                .finalAmount(newTotal)
                .paidAmount(paidAmount)
                .overpaidAmount(overpaid)
                .sourceOrderId(source.getId())
                .sourceOrderCode(source.getOrderCode())
                .linkType("EXCHANGE")
                .creditedFromSource(credited)
                .createdAt(now)
                .updatedAt(now)
                .build();
        newOrder = orderRepository.save(newOrder);

        List<WarehouseReceiptItem> exportReceiptItems = new ArrayList<>();

        for (NewOrderItemLine nl : req.getNewItems()) {
            BigDecimal unitPrice = nl.getUnitPrice() != null ? nl.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal qty       = nl.getQuantity()  != null ? nl.getQuantity()  : BigDecimal.ONE;
            BigDecimal discPct   = nl.getDiscountPct() != null ? nl.getDiscountPct() : BigDecimal.ZERO;

            BigDecimal afterDisc = unitPrice.multiply(qty).multiply(
                    BigDecimal.ONE.subtract(discPct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)));

            BigDecimal vatRate   = nl.getVatRate() != null ? nl.getVatRate() : BigDecimal.ZERO;
            BigDecimal vatAmount = afterDisc.multiply(vatRate).setScale(4, RoundingMode.HALF_UP);

            String vatMode    = nl.getVatMode() != null ? nl.getVatMode()
                    : (vatRate.compareTo(BigDecimal.ZERO) > 0 ? "EXCLUSIVE" : "INCLUSIVE");
            int vatRatePct    = vatRate.multiply(BigDecimal.valueOf(100)).intValue();
            String  saleType  = nl.getSaleType() != null ? nl.getSaleType() : "RETAIL";
            Integer upb       = nl.getUnitsPerBox();

            OrderItem ni = OrderItem.builder()
                    .order(newOrder)
                    .productId(nl.getProductId())
                    .productName(nl.getProductName())
                    .unit(nl.getUnit() != null ? nl.getUnit() : "SP")
                    .quantity(qty)
                    .basePrice(unitPrice)
                    .unitPrice(unitPrice)
                    .priceMode(nl.getTierId() != null ? "TIER" : "BASE")
                    .tierId(nl.getTierId())
                    .tierName(nl.getTierName())
                    .discountPercent(discPct.intValue())
                    .vatRate(vatRatePct)
                    .vatMode(vatMode)
                    .vatAmount(vatAmount)
                    .saleType(saleType)
                    .unitsPerBox(upb)
                    .subtotal(afterDisc)
                    .build();
            orderItemRepository.save(ni);
        }

        List<Map.Entry<Long, BigDecimal>> sortedUsage = ingredientUsageMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        for (Map.Entry<Long, BigDecimal> entry : sortedUsage) {
            Long ingId    = entry.getKey();
            BigDecimal needed = entry.getValue();
            if (needed.compareTo(BigDecimal.ZERO) <= 0) continue;

            Ingredient ing = ingredientRepository.findById(ingId).orElse(null);
            String ingName = ing != null ? ing.getName() : "ingId=" + ingId;

            StockMutationResult mut = stockMutationService.decrease(
                    ingId, newWarehouse.getId(), needed, now);
            BigDecimal before = mut.getBefore();
            BigDecimal after  = mut.getAfter();

            fifoDeductService.deductById(newOrder.getId(), newWarehouse, ingId, ingName, needed, now);

            exportReceiptItems.add(WarehouseReceiptItem.builder()
                    .ingredientId(ingId)
                    .ingredientNameSnapshot(ingName)
                    .ingredientUnitSnapshot(ing != null ? ing.getUnit() : "")
                    .ingredientImageUrlSnapshot(ing != null ? ing.getImageUrl() : null)
                    .quantity(needed.negate())
                    .quantityBefore(before)
                    .quantityAfter(after)
                    .difference(after.subtract(before))
                    .build());
        }

        if (!exportReceiptItems.isEmpty()) {
            String expCode = generateReceiptCode("EXP");
            WarehouseReceipt exportReceipt = WarehouseReceipt.builder()
                    .receiptCode(expCode)
                    .receiptType(WarehouseReceipt.ReceiptType.EXPORT_ORDER)
                    .costStatus(WarehouseReceipt.CostStatus.CONFIRMED)
                    .warehouse(newWarehouse)
                    .order(newOrder)
                    .note("Xuất kho cho đơn đổi SP " + newCode)
                    .createdBy(actor)
                    .createdByName(actor.getFullName())
                    .createdAt(now).updatedAt(now)
                    .build();
            exportReceipt.setItems(exportReceiptItems);
            exportReceiptItems.forEach(ri -> ri.setReceipt(exportReceipt));
            warehouseReceiptRepository.save(exportReceipt);
        }

        noteLines.add(Map.of("newOrderCode", newCode, "newTotal", newTotal,
                "credited", credited, "balance", balance, "at", now, "by", actor.getFullName()));
        appendReturnNote(source, noteLines);
        source.setUpdatedAt(now);
        orderRepository.save(source);

        orderLogRepository.save(OrderLog.builder()
                .order(source).action("EXCHANGE")
                .actorName(actor.getFullName()).actorRole("SELLER")
                .note("Đổi SP → đơn mới %s. Khấu trừ: %,d. Tổng mới: %,d. Cân bằng: %,d".formatted(
                        newCode, credited.longValue(), newTotal.longValue(), balance.longValue()))
                .createdAt(now).build());
        orderLogRepository.save(OrderLog.builder()
                .order(newOrder).action("CREATED_EXCHANGE")
                .actorName(actor.getFullName()).actorRole("SELLER")
                .note("Tạo từ đổi SP đơn %s".formatted(source.getOrderCode()))
                .createdAt(now).build());

        return ExchangeResult.builder()
                .newOrderId(newOrder.getId()).newOrderCode(newCode)
                .sourceOrderId(source.getId()).sourceOrderCode(source.getOrderCode())
                .newTotal(newTotal).credited(credited).balance(balance)
                .newOrderStatus(newStatus.name())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BƯỚC 2 — XỬ LÝ HÀNG NHẬN VỀ
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void processRestock(Long actorId, RestockRequest req) {
        User actor = requireUser(actorId);
        Order source = orderRepository.findById(req.getSourceOrderId())
                .orElseThrow(() -> new BusinessException("Không tìm thấy đơn hàng gốc"));

        long now = System.currentTimeMillis();
        boolean isRestock = "RESTOCK".equalsIgnoreCase(req.getAction());
        boolean isDestroy = "DESTROY".equalsIgnoreCase(req.getAction());

        log.info("[EXCHANGE] processRestock START orderId={} action={} itemCount={} by={}",
                req.getSourceOrderId(), req.getAction(),
                req.getItems() != null ? req.getItems().size() : 0,
                actor.getFullName());

        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BusinessException("Phải chọn ít nhất 1 sản phẩm để xử lý hàng nhận về");
        }
        if (!isRestock && !isDestroy) {
            throw new BusinessException("Action không hợp lệ: " + req.getAction()
                    + " (chỉ chấp nhận RESTOCK hoặc DESTROY)");
        }

        StringBuilder logNote = new StringBuilder();
        logNote.append(isRestock ? "Nhập kho hàng nhận về" : "Tiêu hủy hàng nhận về");
        if (req.getNote() != null) logNote.append(": ").append(req.getNote());

        for (ReturnItemLine line : req.getItems()) {
            OrderItem item = requireItem(line.getOrderItemId(), req.getSourceOrderId());
            BigDecimal qty = line.getQuantity();
            logNote.append(" | %s %s %s".formatted(item.getProductName(), qty, item.getUnit()));

            try {
                if (isRestock) {
                    restoreStock(req.getSourceOrderId(), item, qty, now, actor, req.getNote(), false);
                } else {
                    restoreStock(req.getSourceOrderId(), item, qty, now, actor, req.getNote(), true);
                }
            } catch (Exception e) {
                log.error("[EXCHANGE] restoreStock FAILED orderId={} orderItemId={} productId={} " +
                                "productName={} qty={} unit={} action={}",
                        req.getSourceOrderId(), item.getId(), item.getProductId(),
                        item.getProductName(), qty, item.getUnit(), req.getAction(), e);
                throw e;
            }
        }

        orderLogRepository.save(OrderLog.builder()
                .order(source).action(isRestock ? "RESTOCK" : "DESTROY")
                .actorName(actor.getFullName()).actorRole("SELLER")
                .note(logNote.toString())
                .createdAt(now).build());

        log.info("[EXCHANGE] processRestock DONE orderId={} action={} items={} by={}",
                req.getSourceOrderId(), req.getAction(), req.getItems().size(), actor.getFullName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TẠO PHIẾU CHI HOÀN TIỀN — NHIỀU ĐƠN (BULK)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public BulkRefundDisbursementResult createBulkRefundDisbursement(
            Long actorId, BulkRefundDisbursementRequest req) {

        User actor = requireUser(actorId);
        Set<Role> roles = actor.getAllRoles();
        boolean allowed = roles.contains(Role.OWNER)
                || roles.contains(Role.SUPERADMIN)
                || roles.contains(Role.SUPER_ACCOUNTANT)
                || roles.contains(Role.ACCOUNTANT);
        if (!allowed) throw new BusinessException("Bạn không có quyền tạo phiếu chi hoàn tiền");

        if (req.getOrderIds() == null || req.getOrderIds().isEmpty())
            throw new BusinessException("Phải chọn ít nhất 1 đơn để tạo phiếu chi");

        long now = System.currentTimeMillis();

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<String> orderCodes = new ArrayList<>();
        List<Order> validOrders = new ArrayList<>();
        String customerName = null;

        for (Long orderId : req.getOrderIds()) {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy đơn hàng #" + orderId));

            BigDecimal pending = order.getPendingRefundAmount() != null
                    ? order.getPendingRefundAmount() : BigDecimal.ZERO;
            if (pending.compareTo(BigDecimal.ZERO) <= 0)
                throw new BusinessException("Đơn " + order.getOrderCode() + " không có tiền cần hoàn");

            if (order.getRefundVoucherCode() != null)
                throw new BusinessException("Đơn " + order.getOrderCode() + " đã có phiếu chi hoàn: "
                        + order.getRefundVoucherCode());

            totalAmount = totalAmount.add(pending);
            orderCodes.add(order.getOrderCode());
            validOrders.add(order);

            if (customerName == null) {
                customerName = order.getCustomerName();
                if ((customerName == null || customerName.isBlank()) && order.getCustomer() != null) {
                    String comp = order.getCustomer().getCompanyName();
                    customerName = (comp != null && !comp.isBlank()) ? comp : order.getCustomer().getName();
                }
                if (customerName == null || customerName.isBlank()) customerName = "Khách hàng";
            }
        }

        String orderCodesStr = String.join(", ", orderCodes);
        String receiverName = req.getReceiverName() != null && !req.getReceiverName().isBlank()
                ? req.getReceiverName().trim() : customerName;
        String noteText = req.getNote() != null && !req.getNote().isBlank()
                ? req.getNote()
                : "Hoàn tiền cho đơn hàng " + orderCodesStr;

        CreateExpenseVoucherRequest expReq = new CreateExpenseVoucherRequest();
        expReq.setVendorName(receiverName);
        expReq.setVendorId(null);
        expReq.setReason(noteText);
        expReq.setPaymentType("BANK_TRANSFER".equalsIgnoreCase(req.getPaymentMethod())
                ? "BANK_TRANSFER" : "CASH");

        if ("BANK_TRANSFER".equalsIgnoreCase(req.getPaymentMethod())) {
            String bankName = req.getBankName() != null ? req.getBankName().trim() : "";
            String bankRef  = req.getTransactionRef() != null ? req.getTransactionRef().trim() : "";
            if (bankRef.isBlank()) bankRef = "REFUND-" + orderCodesStr.replace(", ", "-");
            expReq.setBankName(bankName.isBlank() ? "Chuyển khoản" : bankName);
            expReq.setBankRef(bankRef);
        }

        var item = new CreateExpenseVoucherRequest.ExpenseItemRequest();
        item.setItemName("Hoàn tiền cho đơn hàng " + orderCodesStr);
        item.setAmount(totalAmount);
        expReq.setItems(java.util.List.of(item));

        com.nhatnam.server.dto.expense.ExpenseVoucherDto expenseDto =
                expenseVoucherService.create(actorId, expReq);

        String voucherCode = expenseDto.getVoucherCode();

        for (Order order : validOrders) {
            BigDecimal pending = order.getPendingRefundAmount();
            BigDecimal currentPaid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
            BigDecimal newPaid = currentPaid.subtract(pending).max(BigDecimal.ZERO);
            BigDecimal currentRefunded = order.getRefundedAmount() != null
                    ? order.getRefundedAmount() : BigDecimal.ZERO;

            order.setPaidAmount(newPaid);
            order.setRefundedAmount(currentRefunded.add(pending));
            order.setRefundVoucherCode(voucherCode);
            order.setUpdatedAt(now);
            orderRepository.save(order);

            orderLogRepository.save(OrderLog.builder()
                    .order(order)
                    .action("REFUND_DISBURSED")
                    .actorName(actor.getFullName())
                    .actorRole(roles.contains(Role.OWNER) ? "OWNER" : "SUPER_ACCOUNTANT")
                    .note("Đã hoàn %,d đ cho khách qua phiếu chi %s. %s".formatted(
                            pending.longValue(), voucherCode,
                            req.getNote() != null ? req.getNote() : ""))
                    .createdAt(now)
                    .build());

            log.info("[REFUND_DISBURSE] orderId={} code={} amount={} voucher={}",
                    order.getId(), order.getOrderCode(), pending, voucherCode);
        }

        return BulkRefundDisbursementResult.builder()
                .voucherCode(voucherCode)
                .totalAmount(totalAmount)
                .orderCount(orderCodes.size())
                .orderCodes(orderCodes)
                .message("Đã tạo phiếu chi " + voucherCode
                        + " (số " + expenseDto.getPaymentNumber() + ")"
                        + " hoàn tổng " + String.format("%,d đ", totalAmount.longValue())
                        + " cho " + orderCodes.size() + " đơn hàng")
                .build();
    }

    @Transactional
    public RefundDisbursementResult createRefundDisbursement(Long orderId, Long actorId,
                                                             RefundDisbursementRequest req) {
        User actor = requireUser(actorId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy đơn hàng"));

        if (!"EXCHANGE".equals(order.getLinkType()))
            throw new BusinessException("Đơn không phải đổi SP");

        if (order.getOverpaidAmount() == null
                || order.getOverpaidAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("Đơn không có tiền cần hoàn lại cho khách");

        if (req.getOrderVersion() != null
                && !req.getOrderVersion().equals(order.getVersion())) {
            throw new com.nhatnam.server.common.ConflictException(
                    "Đơn hàng đã được cập nhật bởi người khác. Vui lòng tải lại trang.");
        }

        if (order.getOverpaidRefundVoucherCode() != null) {
            throw new BusinessException(
                    "Phiếu chi đã được tạo: " + order.getOverpaidRefundVoucherCode());
        }

        String paymentType = req.getPaymentMethod() != null ? req.getPaymentMethod() : "CASH";
        com.nhatnam.server.dto.expense.ExpenseVoucherDto dto =
                expenseVoucherService.createOverpayRefund(
                        actorId,
                        order.getOrderCode(),
                        paymentType,
                        req.getBankName(),
                        req.getBankAccount(),
                        req.getBankHolder());

        long now = System.currentTimeMillis();
        orderLogRepository.save(OrderLog.builder()
                .order(order).action("REFUND_DISBURSEMENT")
                .actorName(actor.getFullName()).actorRole("ACCOUNTANT")
                .note("Tạo phiếu chi %s hoàn %,d đ cho khách (đơn đổi SP).%s".formatted(
                        dto.getVoucherCode(),
                        order.getOverpaidAmount().longValue(),
                        req.getNote() != null && !req.getNote().isBlank()
                                ? " " + req.getNote() : ""))
                .createdAt(now).build());

        return RefundDisbursementResult.builder()
                .voucherCode(dto.getVoucherCode())
                .amount(order.getOverpaidAmount())
                .message("Đã tạo phiếu chi " + dto.getVoucherCode()
                        + " (số " + dto.getPaymentNumber() + ")")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Order requireCompleted(Long orderId) {
        Order o = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy đơn hàng"));
        if (o.getStatus() != OrderStatus.COMPLETED)
            throw new BusinessException("Chỉ xử lý hoàn/đổi với đơn đã hoàn thành");
        return o;
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng"));
    }

    private OrderItem requireItem(Long itemId, Long orderId) {
        OrderItem it = orderItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy sản phẩm #" + itemId));
        if (!it.getOrder().getId().equals(orderId))
            throw new BusinessException("Sản phẩm không thuộc đơn hàng này");
        return it;
    }

    private void validateQty(BigDecimal qty, OrderItem item) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("Số lượng phải lớn hơn 0");
        if (qty.compareTo(item.getQuantity()) > 0)
            throw new BusinessException("Số lượng vượt quá số lượng đơn cho " + item.getProductName());
    }

    /**
     * Hoàn kho cho sản phẩm nhận về.
     * Lọc deductions theo ingredientIds của product trong item để tránh nhập/xuất sai SP.
     *
     * <p><b>DEBUG phiên bản này:</b> log rất chi tiết ở từng bước để bắt NPE.
     */
    private void restoreStock(Long orderId, OrderItem item, BigDecimal qty,
                              long now, User actor, String extraNote, boolean isDestroy) {

        log.info("[EXCHANGE][restore] START orderId={} itemId={} productId={} productName={} qty={} isDestroy={}",
                orderId, item.getId(), item.getProductId(), item.getProductName(), qty, isDestroy);

        List<OrderStockDeduction> allDeductions = deductionRepository.findByOrderId(orderId);
        log.info("[EXCHANGE][restore] findByOrderId({}) -> {} deductions", orderId, allDeductions.size());

        // DEBUG: in chi tiết từng deduction để biết cái nào null
        for (OrderStockDeduction d : allDeductions) {
            log.debug("[EXCHANGE][restore]   deduction id={} ingStockId={} quantity={} ingExpiryId={} costPrice={}",
                    d.getId(),
                    d.getIngredientStock() != null ? d.getIngredientStock().getId() : "NULL",
                    d.getQuantity(),
                    d.getIngredientExpiry() != null ? d.getIngredientExpiry().getId() : "NULL",
                    d.getCostPrice());
        }

        Set<Long> itemIngredientIds = new java.util.HashSet<>();
        if (item.getProductId() != null) {
            productIngredientRepository.findByProductId(item.getProductId())
                    .forEach(pi -> itemIngredientIds.add(pi.getIngredientId()));
        }
        log.info("[EXCHANGE][restore] product {} uses ingredientIds={}",
                item.getProductId(), itemIngredientIds);

        List<OrderStockDeduction> deductions = allDeductions.stream()
                .filter(d -> {
                    if (itemIngredientIds.isEmpty()) return false;
                    Long ingId = d.getIngredientStock() != null
                            ? d.getIngredientStock().getIngredientId() : null;
                    return ingId != null && itemIngredientIds.contains(ingId);
                })
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));   // ← MUTABLE

        log.info("[EXCHANGE][restore] after filter -> {} deductions", deductions.size());

        if (deductions.isEmpty()) {
            log.warn("[EXCHANGE][restore] KHÔNG có deduction nào khớp productId={} orderId={} " +
                            "itemIngredientIds={} totalDeductions={}",
                    item.getProductId(), orderId, itemIngredientIds, allDeductions.size());
            return;
        }

        BigDecimal origQty = item.getQuantity();
        if (origQty == null || origQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[EXCHANGE][restore] item.getQuantity()={} không hợp lệ, bỏ qua", origQty);
            return;
        }

        BigDecimal ratio = qty.divide(origQty, 6, RoundingMode.HALF_UP);
        log.info("[EXCHANGE][restore] origQty={} returnQty={} ratio={}", origQty, qty, ratio);

        List<WarehouseReceiptItem> importItems = new ArrayList<>();
        Warehouse importWarehouse = null;
        List<LotRestore> lotRestores = new ArrayList<>();

        // Deadlock guard: sort deductions theo ingredientId ASC
        deductions.sort(java.util.Comparator.comparing(
                d -> d.getIngredientStock().getIngredientId()));

        // Group theo ingredient để track accumulated + bù ở record cuối
        java.util.Map<Long, java.util.List<OrderStockDeduction>> deductionsByIng = new java.util.LinkedHashMap<>();
        for (OrderStockDeduction d : deductions) {
            Long ingId = d.getIngredientStock().getIngredientId();
            deductionsByIng.computeIfAbsent(ingId, k -> new ArrayList<>()).add(d);
        }
        log.info("[EXCHANGE][restore] group by ingredient -> {} groups: {}",
                deductionsByIng.size(), deductionsByIng.keySet());

        for (java.util.Map.Entry<Long, java.util.List<OrderStockDeduction>> group : deductionsByIng.entrySet()) {
            Long groupIngId = group.getKey();
            java.util.List<OrderStockDeduction> ingDeductions = group.getValue();

            BigDecimal totalOrigForIng = ingDeductions.stream()
                    .map(OrderStockDeduction::getQuantity)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal expectedRestoreForIng = totalOrigForIng.multiply(ratio)
                    .setScale(3, RoundingMode.HALF_UP);
            BigDecimal accumulatedForIng = BigDecimal.ZERO;

            log.info("[EXCHANGE][restore]   ingredientId={} lots={} totalOrig={} expectedRestore={}",
                    groupIngId, ingDeductions.size(), totalOrigForIng, expectedRestoreForIng);

            for (int i = 0; i < ingDeductions.size(); i++) {
                OrderStockDeduction d = ingDeductions.get(i);
                boolean isLast = (i == ingDeductions.size() - 1);

                // ── DEBUG + NULL GUARD ─────────────────────────────────────
                BigDecimal origQtyOfDed = d.getQuantity();
                if (origQtyOfDed == null) {
                    log.error("[EXCHANGE][restore]   deduction id={} ingredientId={} có quantity=NULL. SKIP.",
                            d.getId(), groupIngId);
                    continue;
                }
                if (origQtyOfDed.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("[EXCHANGE][restore]   deduction id={} ingredientId={} quantity={} <= 0. SKIP.",
                            d.getId(), groupIngId, origQtyOfDed);
                    continue;
                }

                BigDecimal restoreQty;
                if (isLast) {
                    restoreQty = expectedRestoreForIng.subtract(accumulatedForIng)
                            .setScale(3, RoundingMode.HALF_UP);
                } else {
                    restoreQty = origQtyOfDed.multiply(ratio).setScale(3, RoundingMode.HALF_UP);
                    accumulatedForIng = accumulatedForIng.add(restoreQty);
                }

                log.debug("[EXCHANGE][restore]     lot i={}/{} deductionId={} origQty={} restoreQty={} isLast={}",
                        i + 1, ingDeductions.size(), d.getId(), origQtyOfDed, restoreQty, isLast);

                if (restoreQty.compareTo(BigDecimal.ZERO) <= 0) {
                    log.debug("[EXCHANGE][restore]     restoreQty={} <= 0, SKIP.", restoreQty);
                    continue;
                }

                IngredientStock stock = d.getIngredientStock();
                Long ingId = stock.getIngredientId();
                Long whId  = stock.getWarehouse().getId();
                if (importWarehouse == null) importWarehouse = stock.getWarehouse();

                // Atomic increase
                StockMutationResult mut = stockMutationService.increase(ingId, whId, restoreQty, now);
                BigDecimal before = mut.getBefore();
                BigDecimal after  = mut.getAfter();

                if (d.getCostPrice() != null && d.getCostPrice().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal cost = d.getCostPrice().multiply(restoreQty).setScale(2, RoundingMode.HALF_UP);
                    stockMutationService.addCostValue(ingId, whId, cost, now);
                }

                IngredientExpiry lot = d.getIngredientExpiry() != null
                        ? expiryRepository.findById(d.getIngredientExpiry().getId()).orElse(null) : null;
                if (lot != null) {
                    lot.setQuantity(lot.getQuantity().add(restoreQty).setScale(3, RoundingMode.HALF_UP));
                    lot.setUpdatedAt(now);
                    expiryRepository.save(lot);
                } else {
                    lot = IngredientExpiry.builder()
                            .warehouse(stock.getWarehouse())
                            .ingredientId(stock.getIngredientId())
                            .expiryDate(d.getExpiryDate())
                            .costPrice(d.getCostPrice() != null ? d.getCostPrice() : BigDecimal.ZERO)
                            .quantity(restoreQty)
                            .createdAt(now).updatedAt(now).build();
                    lot = expiryRepository.save(lot);
                }

                Ingredient ing = ingredientRepository.findById(stock.getIngredientId()).orElse(null);
                String ingName = ing != null ? ing.getName()
                        : (stock.getIngredientNameSnapshot() != null
                        ? stock.getIngredientNameSnapshot() : "ingId=" + stock.getIngredientId());
                String ingUnit = ing != null ? ing.getUnit() : "";

                importItems.add(WarehouseReceiptItem.builder()
                        .ingredientId(stock.getIngredientId())
                        .ingredientNameSnapshot(ingName)
                        .ingredientUnitSnapshot(ingUnit)
                        .quantity(restoreQty)
                        .quantityBefore(before)
                        .quantityAfter(after)
                        .difference(restoreQty)
                        .costPrice(d.getCostPrice())
                        .build());

                lotRestores.add(new LotRestore(
                        stock.getIngredientId(), ingName, ingUnit,
                        stock, lot, restoreQty,
                        d.getCostPrice() != null ? d.getCostPrice() : BigDecimal.ZERO));

                // ── BROADCAST: BỌC TRY-CATCH để không phá transaction ──────
                try {
                    cartHoldService.broadcastIngredientStockUpdate(
                            stock.getWarehouse() != null ? stock.getWarehouse().getId() : null,
                            stock.getIngredientId(), stock.getStockQuantity());
                } catch (Exception ex) {
                    log.warn("[EXCHANGE][restore] broadcast (restore) failed ingId={} whId={}: {}",
                            stock.getIngredientId(), whId, ex.getMessage(), ex);
                }
            }   // end inner for
        }   // end outer for group

        Order sourceOrder = orderRepository.findById(orderId).orElse(null);
        String orderCode = sourceOrder != null ? sourceOrder.getOrderCode() : String.valueOf(orderId);

        log.info("[EXCHANGE][restore] prepared importItems={} lotRestores={} importWarehouse={}",
                importItems.size(), lotRestores.size(),
                importWarehouse != null ? importWarehouse.getId() : "NULL");

        if (!importItems.isEmpty() && importWarehouse != null) {
            String impNoteText = isDestroy
                    ? "Nhập lại kho từ hoàn/đổi đơn " + orderCode
                    + (extraNote != null && !extraNote.isBlank() ? " - " + extraNote : "")
                    : "Nhập kho hàng hoàn trả từ đơn " + orderCode
                    + (extraNote != null && !extraNote.isBlank() ? " - " + extraNote : "");

            String impCode = generateReceiptCode("IMP");
            WarehouseReceipt importReceipt = WarehouseReceipt.builder()
                    .receiptCode(impCode)
                    .receiptType(WarehouseReceipt.ReceiptType.IMPORT)
                    .costStatus(WarehouseReceipt.CostStatus.CONFIRMED)
                    .warehouse(importWarehouse)
                    .order(sourceOrder)
                    .note(impNoteText)
                    .createdBy(actor)
                    .createdByName(actor.getFullName())
                    .createdAt(now).updatedAt(now)
                    .build();
            importReceipt.setItems(importItems);
            importItems.forEach(ri -> ri.setReceipt(importReceipt));
            warehouseReceiptRepository.save(importReceipt);
            log.info("[EXCHANGE][restore] saved IMPORT receipt {} with {} items",
                    impCode, importItems.size());
        }

        if (isDestroy && !lotRestores.isEmpty() && importWarehouse != null) {
            List<WarehouseReceiptItem> destroyItems = new ArrayList<>();

            for (LotRestore lr : lotRestores) {
                Long ingId = lr.stock.getIngredientId();
                Long whId  = lr.stock.getWarehouse().getId();

                StockMutationResult mut = stockMutationService.decreaseRespectingHolds(
                        ingId, whId, lr.qty, true, now);
                BigDecimal before = mut.getBefore();
                BigDecimal after  = mut.getAfter();

                if (lr.costPrice.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal costToRemove = lr.costPrice.multiply(lr.qty).setScale(2, RoundingMode.HALF_UP);
                    stockMutationService.subCostValue(ingId, whId, costToRemove, now);
                }

                lr.lot.setQuantity(lr.lot.getQuantity().subtract(lr.qty).max(BigDecimal.ZERO)
                        .setScale(3, RoundingMode.HALF_UP));
                lr.lot.setUpdatedAt(now);
                expiryRepository.save(lr.lot);

                destroyItems.add(WarehouseReceiptItem.builder()
                        .ingredientId(lr.ingredientId)
                        .ingredientNameSnapshot(lr.ingName)
                        .ingredientUnitSnapshot(lr.ingUnit)
                        .quantity(lr.qty.negate())
                        .quantityBefore(before)
                        .quantityAfter(after)
                        .difference(after.subtract(before))
                        .costPrice(lr.costPrice)
                        .build());

                // ── BROADCAST: BỌC TRY-CATCH ───────────────────────────────
                try {
                    cartHoldService.broadcastIngredientStockUpdate(
                            lr.stock.getWarehouse() != null ? lr.stock.getWarehouse().getId() : null,
                            lr.ingredientId, lr.stock.getStockQuantity());
                } catch (Exception ex) {
                    log.warn("[EXCHANGE][restore] broadcast (destroy) failed ingId={} whId={}: {}",
                            lr.ingredientId, whId, ex.getMessage(), ex);
                }
            }

            String expCode = generateReceiptCode("EXP-HUY");
            WarehouseReceipt destroyReceipt = WarehouseReceipt.builder()
                    .receiptCode(expCode)
                    .receiptType(WarehouseReceipt.ReceiptType.EXPORT_OTHER)
                    .costStatus(WarehouseReceipt.CostStatus.CONFIRMED)
                    .warehouse(importWarehouse)
                    .order(sourceOrder)
                    .note("Tiêu hủy hàng hoàn/đổi từ đơn " + orderCode
                            + (extraNote != null && !extraNote.isBlank() ? " - " + extraNote : ""))
                    .createdBy(actor)
                    .createdByName(actor.getFullName())
                    .createdAt(now + 1)
                    .updatedAt(now + 1)
                    .build();
            destroyReceipt.setItems(destroyItems);
            destroyItems.forEach(ri -> ri.setReceipt(destroyReceipt));
            warehouseReceiptRepository.save(destroyReceipt);
            log.info("[EXCHANGE][restore] saved DESTROY receipt {} with {} items",
                    expCode, destroyItems.size());
        }

        log.info("[EXCHANGE][restore] DONE orderId={} itemId={}", orderId, item.getId());
    }

    @AllArgsConstructor
    private static class LotRestore {
        Long ingredientId;
        String ingName;
        String ingUnit;
        IngredientStock stock;
        IngredientExpiry lot;
        BigDecimal qty;
        BigDecimal costPrice;
    }

    private String generateReceiptCode(String prefix) {
        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String rand = String.format("%04d", new java.util.Random().nextInt(10000));
        String code = prefix + "-" + date + "-" + rand;
        int attempts = 0;
        while (warehouseReceiptRepository.existsByReceiptCode(code)) {
            rand = String.format("%04d", new java.util.Random().nextInt(10000));
            code = prefix + "-" + date + "-" + rand;
            attempts++;
            if (attempts > 50) {
                // fallback: dùng UUID để chắc chắn không trùng
                code = prefix + "-" + date + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                break;
            }
        }
        return code;
    }

    private void appendReturnNote(Order order, List<Map<String, Object>> lines) {
        try {
            List<Map<String, Object>> existing = new ArrayList<>();
            if (order.getReturnExchangeNote() != null && !order.getReturnExchangeNote().isBlank()) {
                existing = MAPPER.readValue(order.getReturnExchangeNote(), new TypeReference<>() {});
            }
            existing.addAll(lines);
            order.setReturnExchangeNote(MAPPER.writeValueAsString(existing));
        } catch (Exception e) {
            log.warn("[EXCHANGE] Không ghi được returnExchangeNote: {}", e.getMessage());
        }
    }
}