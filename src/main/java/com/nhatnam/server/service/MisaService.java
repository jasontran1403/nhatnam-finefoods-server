package com.nhatnam.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.entity.MisaReceipt;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.repository.MisaReceiptRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Build payload đúng format MISA API, lưu vào DB.
 * Khi có tài khoản MISA thật, bật {@code misa.enabled=true} + điền config → tự gửi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MisaService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final MisaReceiptRepository misaReceiptRepository;
    private final ObjectMapper objectMapper;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ── Config (sẽ điền sau khi có tài khoản MISA) ───────────────────────────
    @Value("${misa.enabled:false}")
    private boolean misaEnabled;

    @Value("${misa.app-id:}")
    private String appId;

    @Value("${misa.org-company-code:}")
    private String orgCompanyCode;

    @Value("${misa.base-url:https://openapi.misa.com.vn}")
    private String baseUrl;

    @Value("${misa.default-branch-id:}")
    private String defaultBranchId;

    @Value("${misa.default-stock-code:KPQ}")
    private String defaultStockCode;

    @Value("${misa.default-stock-name:Kho Phổ Quang}")
    private String defaultStockName;

    // ═══════════════════════════════════════════════════════════════════════════
    // ĐƠN ĐẶT HÀNG (sa_order) — reftype 3520
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build payload sa_order từ Order entity.
     *
     * @param order       đơn hàng
     * @param mode        "AUTO" hoặc "MANUAL"
     * @param manualItems nếu MANUAL: danh sách override [{productName, quantity, unit, unitPrice, discount, fee}]
     * @return payload JSON dạng Map, sẵn sàng gửi lên MISA
     */
    @Transactional
    public Map<String, Object> createMisaOrder(Order order, String mode, List<Map<String, Object>> manualItems) {
        if (order.getMisaOrderId() != null) {
            throw new IllegalStateException("Đơn này đã tạo đơn Misa: " + order.getMisaOrderCode());
        }

        // ── Build sa_order payload ───────────────────────────────────────────
        String orgRefId = UUID.randomUUID().toString();
        String refDate = formatDate(order.getCreatedAt());

        Map<String, Object> saOrder = new LinkedHashMap<>();
        saOrder.put("voucher_type", 20);
        saOrder.put("org_refid", orgRefId);
        saOrder.put("org_refno", order.getOrderCode());
        saOrder.put("org_refcode", order.getOrderCode());
        saOrder.put("reftype", 3520);
        saOrder.put("refno", order.getOrderCode());
        saOrder.put("refdate", refDate);

        // Khách hàng
        saOrder.put("account_object_code", resolveCustomerCode(order));
        saOrder.put("account_object_name", resolveCustomerName(order));
        saOrder.put("account_object_address", order.getDeliveryAddress());
        saOrder.put("account_object_tax_code", order.getTaxCode());

        // Chi nhánh
        if (defaultBranchId != null && !defaultBranchId.isBlank()) {
            saOrder.put("branch_id", defaultBranchId);
        }

        // Trạng thái
        saOrder.put("status", mapOrderStatus(order));
        saOrder.put("delivered_status", mapDeliveredStatus(order));

        // Giao hàng
        if (order.getDeliveryDatetime() != null && order.getDeliveryDatetime() > 0) {
            saOrder.put("delivery_date", formatDate(order.getDeliveryDatetime()));
        }
        saOrder.put("shipping_address", order.getDeliveryAddress());
        saOrder.put("receiver", order.getReceiverName());

        // Chiết khấu
        BigDecimal discountAmount = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;
        if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
            saOrder.put("discount_type", 3); // Số tiền trên tổng hóa đơn
            saOrder.put("discount_rate_voucher", discountAmount);
        } else {
            saOrder.put("discount_type", 0);
            saOrder.put("discount_rate_voucher", BigDecimal.ZERO);
        }

        // Công nợ
        saOrder.put("due_day", order.getDebtDays());

        // Nhân viên
        saOrder.put("employee_name", order.getOrderedByName());

        // Ghi chú
        saOrder.put("journal_memo", order.getNotes());

        // Ngày tạo/sửa
        saOrder.put("created_date", refDate);
        saOrder.put("created_by", order.getOrderedByName());

        // ── Chi tiết sản phẩm ────────────────────────────────────────────────
        List<Map<String, Object>> details;
        if ("MANUAL".equalsIgnoreCase(mode) && manualItems != null && !manualItems.isEmpty()) {
            details = buildManualDetails(manualItems);
        } else {
            details = buildAutoDetails(order);
        }
        saOrder.put("detail", details);

        // ── Wrap vào request body ────────────────────────────────────────────
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("app_id", appId);
        requestBody.put("org_company_code", orgCompanyCode);
        requestBody.put("voucher", List.of(saOrder));

        // ── Lưu vào Order ────────────────────────────────────────────────────
        String fakeCode = "DH-MISA-" + order.getOrderCode().replaceAll("[^0-9]", "");
        order.setMisaOrderId(orgRefId);
        order.setMisaOrderCode(fakeCode);
        order.setMisaOrderCreatedAt(System.currentTimeMillis());

        // Lưu payload JSON để review/debug
        try {
            order.setMisaOrderPayload(objectMapper.writeValueAsString(requestBody));
        } catch (Exception e) {
            log.warn("[MISA] Failed to serialize order payload", e);
        }

        order.setUpdatedAt(System.currentTimeMillis());
        orderRepository.save(order);

        // ── Gửi lên MISA (nếu đã bật) ───────────────────────────────────────
        if (misaEnabled) {
            try {
                Map<String, Object> response = callMisaApi("/apir/sync/actopen/save", requestBody);
                order.setMisaOrderResponse(objectMapper.writeValueAsString(response));
                orderRepository.save(order);
                log.info("[MISA] Order {} synced: {}", order.getOrderCode(), response.get("Success"));
            } catch (Exception e) {
                log.error("[MISA] Failed to sync order {}: {}", order.getOrderCode(), e.getMessage());
                // Không throw — đơn vẫn được tạo local, sẽ retry sau
            }
        } else {
            log.info("[MISA-SANDBOX] Order payload built for {}, saved to DB (not sent)", order.getOrderCode());
        }

        // Return cho frontend
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("misaOrderId", orgRefId);
        result.put("misaOrderCode", fakeCode);
        result.put("createdAt", order.getMisaOrderCreatedAt());
        result.put("payload", saOrder); // để FE preview nếu cần
        result.put("sent", misaEnabled);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHIẾU THU (ca_receipt / ba_deposit)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build payload phiếu thu MISA.
     * Tiền mặt → ca_receipt (reftype 1010, voucher_type 5)
     * Chuyển khoản → ba_deposit (reftype 1500, voucher_type 1)
     */
    @Transactional
    public Map<String, Object> createMisaReceipt(Order order, BigDecimal amount,
                                                 String paymentMethod, String bankTransactionRef, String note) {
        if (order.getMisaOrderId() == null) {
            throw new IllegalStateException("Phải tạo đơn Misa trước khi tạo phiếu thu");
        }

        boolean isBankTransfer = "BANK_TRANSFER".equalsIgnoreCase(paymentMethod);
        String orgRefId = UUID.randomUUID().toString();
        long count = misaReceiptRepository.countByOrderId(order.getId());
        String receiptCode = "PT-MISA-" + order.getOrderCode().replaceAll("[^0-9]", "") + "-" + (count + 1);
        String refDate = formatDate(System.currentTimeMillis());

        Map<String, Object> voucher = new LinkedHashMap<>();

        if (isBankTransfer) {
            // ── ba_deposit (thu tiền gửi) ────────────────────────────────────
            voucher.put("voucher_type", 1);
            voucher.put("reftype", 1500);
            voucher.put("org_refid", orgRefId);
            voucher.put("org_refno", receiptCode);
            voucher.put("refdate", refDate);
            voucher.put("posted_date", refDate);

            voucher.put("account_object_name", resolveCustomerName(order));
            voucher.put("account_object_bank_account", bankTransactionRef);

            if (defaultBranchId != null && !defaultBranchId.isBlank()) {
                voucher.put("branch_id", defaultBranchId);
            }

            voucher.put("reason_type_id", 29); // Thu tiền khách hàng
            voucher.put("reason_type_name", "Thu tiền khách hàng");
            voucher.put("journal_memo", buildReceiptMemo(order, note));
            voucher.put("exchange_rate", 1);
            voucher.put("total_amount", amount);
            voucher.put("total_amount_oc", amount);
            voucher.put("currency_id", "VND");
            voucher.put("employee_name", order.getOrderedByName());

            // Detail
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sort_order", 1);
            detail.put("debit_account", "1121");   // Tiền gửi ngân hàng
            detail.put("credit_account", "131");    // Phải thu khách hàng
            detail.put("amount", amount);
            detail.put("amount_oc", amount);
            detail.put("description", "Thu tiền đơn " + order.getOrderCode());
            detail.put("account_object_code", resolveCustomerCode(order));
            voucher.put("detail", List.of(detail));

        } else {
            // ── ca_receipt (thu tiền mặt) ────────────────────────────────────
            voucher.put("voucher_type", 5);
            voucher.put("reftype", 1010);
            voucher.put("org_refid", orgRefId);
            voucher.put("org_refno", receiptCode);
            voucher.put("refdate", refDate);
            voucher.put("posted_date", refDate);

            voucher.put("account_object_name", resolveCustomerName(order));
            voucher.put("account_object_contact_name", resolveCustomerName(order));
            voucher.put("account_object_code", resolveCustomerCode(order));

            if (defaultBranchId != null && !defaultBranchId.isBlank()) {
                voucher.put("branch_id", defaultBranchId);
            }

            voucher.put("reason_type_id", 14); // Thu tiền khách hàng
            voucher.put("journal_memo", buildReceiptMemo(order, note));
            voucher.put("exchange_rate", 1);
            voucher.put("total_amount", amount);
            voucher.put("total_amount_oc", amount);
            voucher.put("currency_id", "VND");
            voucher.put("employee_name", order.getOrderedByName());

            // Detail
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sort_order", 1);
            detail.put("debit_account", "1111");   // Tiền mặt
            detail.put("credit_account", "131");    // Phải thu khách hàng
            detail.put("amount", amount);
            detail.put("amount_oc", amount);
            detail.put("description", "Thu tiền đơn " + order.getOrderCode());
            detail.put("account_object_code", resolveCustomerCode(order));
            voucher.put("detail", List.of(detail));
        }

        // ── Wrap request body ────────────────────────────────────────────────
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("app_id", appId);
        requestBody.put("org_company_code", orgCompanyCode);
        requestBody.put("voucher", List.of(voucher));

        // ── Lưu MisaReceipt ─────────────────────────────────────────────────
        String payloadJson = null;
        try { payloadJson = objectMapper.writeValueAsString(requestBody); }
        catch (Exception ignored) {}

        MisaReceipt receipt = MisaReceipt.builder()
                .orderId(order.getId())
                .misaReceiptCode(receiptCode)
                .misaRefId(orgRefId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .bankTransactionRef(bankTransactionRef)
                .note(note)
                .requestPayload(payloadJson)
                .createdAt(System.currentTimeMillis())
                .build();

        // ── Gửi lên MISA (nếu đã bật) ───────────────────────────────────────
        if (misaEnabled) {
            try {
                Map<String, Object> response = callMisaApi("/apir/sync/actopen/save", requestBody);
                receipt.setResponsePayload(objectMapper.writeValueAsString(response));
                receipt.setSynced(true);
                log.info("[MISA] Receipt {} synced: {}", receiptCode, response.get("Success"));
            } catch (Exception e) {
                log.error("[MISA] Failed to sync receipt {}: {}", receiptCode, e.getMessage());
                receipt.setSynced(false);
            }
        } else {
            receipt.setSynced(false);
            log.info("[MISA-SANDBOX] Receipt payload built for {}, saved to DB (not sent)", receiptCode);
        }

        misaReceiptRepository.save(receipt);

        // Return
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", receipt.getId());
        result.put("misaReceiptCode", receiptCode);
        result.put("amount", amount);
        result.put("paymentMethod", paymentMethod);
        result.put("bankTransactionRef", bankTransactionRef);
        result.put("createdAt", receipt.getCreatedAt());
        result.put("sent", misaEnabled);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Build detail từ OrderItem (chế độ tự động) */
    private List<Map<String, Object>> buildAutoDetails(Order order) {
        List<Map<String, Object>> details = new ArrayList<>();
        List<OrderItem> items = order.getOrderItems();
        if (items == null) return details;

        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            var product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;

            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ONE;
            BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal subtotal = item.getSubtotal() != null ? item.getSubtotal() : qty.multiply(unitPrice);
            BigDecimal vatAmount = item.getVatAmount() != null ? item.getVatAmount() : BigDecimal.ZERO;
            int vatRate = item.getVatRate() != null ? item.getVatRate() : 0;

            // Quy đổi đơn vị nếu có
            String unitName = item.getUnit();
            BigDecimal mainQty = qty;
            BigDecimal mainUnitPrice = unitPrice;
            String mainUnitName = unitName;

            if (product != null && product.getConversionUnit() != null
                    && product.getConversionFactor() != null
                    && product.getConversionFactor().compareTo(BigDecimal.ZERO) > 0) {
                // Quy đổi: qty * factor = số lượng theo đơn vị quy đổi
                mainQty = qty.multiply(product.getConversionFactor()).setScale(3, RoundingMode.HALF_UP);
                mainUnitName = product.getConversionUnit();
                // Giá theo đơn vị quy đổi = unitPrice / factor
                mainUnitPrice = unitPrice.divide(product.getConversionFactor(), 2, RoundingMode.HALF_UP);
            }

            // Xử lý bán theo thùng
            boolean isBox = "BOX".equalsIgnoreCase(item.getSaleType())
                    && item.getUnitsPerBox() != null && item.getUnitsPerBox() > 0;
            if (isBox) {
                // unitPrice trong đơn = giá/đơn vị, thùng = qty thùng × unitsPerBox
                mainQty = qty.multiply(BigDecimal.valueOf(item.getUnitsPerBox()));
                // unitPrice đã là giá per unit, giữ nguyên
            }

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("sort_order", i + 1);
            d.put("inventory_item_code", product != null && product.getSku() != null
                    ? product.getSku() : "SP-" + (item.getProductId() != null ? item.getProductId() : i));
            d.put("inventory_item_id", UUID.randomUUID().toString()); // placeholder
            d.put("inventory_item_name", item.getProductName());
            d.put("description", item.getProductName());

            d.put("unit_name", unitName);
            d.put("unit_id", UUID.randomUUID().toString()); // placeholder
            d.put("quantity", qty);
            d.put("unit_price", unitPrice);

            d.put("main_unit_name", mainUnitName);
            d.put("main_unit_id", UUID.randomUUID().toString()); // placeholder
            d.put("main_quantity", mainQty);
            d.put("main_unit_price", mainUnitPrice);
            d.put("main_convert_rate", product != null && product.getConversionFactor() != null
                    ? product.getConversionFactor() : BigDecimal.ONE);

            d.put("amount", subtotal);
            d.put("amount_oc", subtotal);

            // Chiết khấu
            d.put("discount_rate", BigDecimal.ZERO);
            d.put("discount_amount", BigDecimal.ZERO);
            d.put("discount_amount_oc", BigDecimal.ZERO);

            // VAT
            d.put("vat_rate", vatRate);
            d.put("vat_amount", vatAmount);
            d.put("vat_amount_oc", vatAmount);

            d.put("unit_price_after_tax", unitPrice.add(
                    vatRate > 0 ? unitPrice.multiply(BigDecimal.valueOf(vatRate)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO
            ));

            // Kho
            d.put("stock_code", defaultStockCode);
            d.put("stock_name", defaultStockName);

            d.put("exchange_rate_operator", "*");
            d.put("is_description", false);
            d.put("is_promotion", false);

            details.add(d);
        }
        return details;
    }

    /** Build detail từ manual override items */
    private List<Map<String, Object>> buildManualDetails(List<Map<String, Object>> manualItems) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < manualItems.size(); i++) {
            Map<String, Object> mi = manualItems.get(i);

            String productName = mi.get("productName") instanceof String s ? s : "SP-" + (i + 1);
            BigDecimal qty = toBigDecimal(mi.get("quantity"), BigDecimal.ONE);
            String unit = mi.get("unit") instanceof String s ? s : "Cái";
            BigDecimal unitPrice = toBigDecimal(mi.get("unitPrice"), BigDecimal.ZERO);
            BigDecimal discount = toBigDecimal(mi.get("discount"), BigDecimal.ZERO);
            BigDecimal fee = toBigDecimal(mi.get("fee"), BigDecimal.ZERO);
            BigDecimal amount = qty.multiply(unitPrice).subtract(discount).add(fee);

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("sort_order", i + 1);
            d.put("inventory_item_code", "MANUAL-" + (i + 1));
            d.put("inventory_item_id", UUID.randomUUID().toString());
            d.put("inventory_item_name", productName);
            d.put("description", productName);
            d.put("unit_name", unit);
            d.put("unit_id", UUID.randomUUID().toString());
            d.put("quantity", qty);
            d.put("unit_price", unitPrice);
            d.put("main_unit_name", unit);
            d.put("main_unit_id", UUID.randomUUID().toString());
            d.put("main_quantity", qty);
            d.put("main_unit_price", unitPrice);
            d.put("main_convert_rate", BigDecimal.ONE);
            d.put("amount", amount);
            d.put("amount_oc", amount);
            d.put("discount_rate", BigDecimal.ZERO);
            d.put("discount_amount", discount);
            d.put("discount_amount_oc", discount);
            d.put("vat_rate", 0);
            d.put("vat_amount", BigDecimal.ZERO);
            d.put("vat_amount_oc", BigDecimal.ZERO);
            d.put("unit_price_after_tax", unitPrice);
            d.put("stock_code", defaultStockCode);
            d.put("stock_name", defaultStockName);
            d.put("exchange_rate_operator", "*");
            d.put("is_description", false);
            d.put("is_promotion", false);
            details.add(d);
        }
        return details;
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private String resolveCustomerCode(Order order) {
        // Ưu tiên: customer.customerCode → order.customerPhone → "KL-" + orderId
        if (order.getCustomer() != null && order.getCustomer().getCustomerCode() != null)
            return order.getCustomer().getCustomerCode();
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank())
            return order.getCustomerPhone();
        return "KL-" + order.getId();
    }

    private String resolveCustomerName(Order order) {
        if (order.getCustomerName() != null && !order.getCustomerName().isBlank())
            return order.getCustomerName();
        if (order.getCompanyName() != null && !order.getCompanyName().isBlank())
            return order.getCompanyName();
        return "Khách lẻ";
    }

    /** Map Order.status → MISA sa_order.status */
    private int mapOrderStatus(Order order) {
        return switch (order.getStatus()) {
            case COMPLETED -> 2;
            case CANCELLED, FAILED -> 3;
            case PENDING -> 0;
            default -> 1; // CONFIRMED, PREPARING, READY, DELIVERING, PENDING_PAYMENT
        };
    }

    /** Map Order.status → MISA delivered_status */
    private int mapDeliveredStatus(Order order) {
        return switch (order.getStatus()) {
            case COMPLETED -> 2;
            case DELIVERING, PENDING_PAYMENT -> 1;
            default -> 0;
        };
    }

    private String buildReceiptMemo(Order order, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thu tiền đơn hàng ").append(order.getOrderCode());
        if (order.getCustomerName() != null)
            sb.append(" - KH: ").append(order.getCustomerName());
        if (note != null && !note.isBlank())
            sb.append(" - ").append(note);
        return sb.toString();
    }

    private String formatDate(Long epochMs) {
        if (epochMs == null || epochMs <= 0) return null;
        return Instant.ofEpochMilli(epochMs).atZone(VN).format(ISO_DT);
    }

    private BigDecimal toBigDecimal(Object val, BigDecimal fallback) {
        if (val == null) return fallback;
        try { return new BigDecimal(val.toString()); }
        catch (Exception e) { return fallback; }
    }

    // ── HTTP client (placeholder — sẽ implement khi có tài khoản) ────────────

    /**
     * Gọi MISA API. Hiện tại throw nếu chưa config.
     * Khi có tài khoản: implement bằng RestTemplate/WebClient + access_token.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callMisaApi(String path, Map<String, Object> body) {
        // TODO: implement khi có tài khoản MISA
        // 1. Lấy access_token (cache + refresh)
        // 2. POST baseUrl + path, header Authorization: Bearer {token}
        // 3. Parse response
        throw new UnsupportedOperationException(
                "MISA API chưa được cấu hình. Set misa.enabled=true và điền misa.app-id, misa.org-company-code");
    }
}