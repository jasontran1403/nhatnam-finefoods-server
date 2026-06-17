package com.nhatnam.server.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class InvoiceDTO {
    private Long   orderId;
    private String orderCode;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String shippingAddress;
    private String notes;
    private String surchargeDetail;
    private Boolean hideAllPrices;

    private Map<Integer, BigDecimal> vatBreakdownInclusive;
    private Map<Integer, BigDecimal> vatBreakdownExclusive;

    private BigDecimal totalAmount;       // gross subtotal (trước discount)
    private BigDecimal discountAmount;
    private BigDecimal vatAmount;         // tổng VAT
    private BigDecimal finalAmount;       // số tiền phải trả cuối cùng
    private BigDecimal surcharge;         // phụ phí

    private String status;
    private String paymentStatus;
    private String paymentMethod;         // "CASH" | "BANK_TRANSFER" | "DEBT"
    private Long   createdAt;

    private String estimatedDelivery;
    private String paymentDeadline;

    private String orderedByName;

    private String customerType;
    private String companyName;
    private String taxCode;
    private String contactName;
    private String deliveryAddress;
    private String companyPhone;
    private String companyAddress;
    private String receiverName;

    /** ==================== FIELD MỚI ==================== */
    private Long deliveryDatetime;        // Timestamp ngày giờ giao hàng

    @Builder.Default
    private Map<Integer, BigDecimal> vatBreakdown = new LinkedHashMap<>();

    private List<Item> items;

    // ─────────────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class Item {
        private String     productName;
        private String     variantName;
        private String     priceName;
        private BigDecimal unitPrice;
        private BigDecimal tierPrice;
        private BigDecimal quantity;
        private BigDecimal subtotal;
        private String     unit;
        private BigDecimal defaultPrice;
        private Integer    vatRate;
        private String     vatMode;
        private BigDecimal vatAmount;
        private String     saleType;
        private Integer    unitsPerBox;
        private String     notes;    // "[KM] note text" nếu là khuyến mãi
        private List<Ingredient> ingredientsUsed;
    }

    // ─────────────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class Ingredient {
        private String     ingredientName;
        private BigDecimal quantityUsed;
        private String     unit;
    }
}