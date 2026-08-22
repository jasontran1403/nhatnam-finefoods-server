package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class DraftOrderResponse {

    private Long   id;
    private String draftCode;

    // ── Khách hàng ─────────────────────────────────────────────────────────
    private Long   customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String shippingAddress;

    /** Tỉnh/thành + phường/xã của địa chỉ giao — dùng khi tạo đơn thật từ nháp/hẹn giờ. */
    private String provinceName;
    private String wardName;

    // ── Người nhận ─────────────────────────────────────────────────────────
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private Long   receiverInfoId;

    // ── Đơn hàng ───────────────────────────────────────────────────────────
    private String     notes;
    private String     paymentMethod;
    private Integer    discountRate;
    private BigDecimal discountAmount;
    private BigDecimal surcharge;
    private String     surchargeDetail;   // JSON raw string
    private List<SurchargeItemDto> surchargeItems; // parsed list

    private Long   warehouseId;
    private String warehouseName;

    // ── Hiển thị giá ───────────────────────────────────────────────────────
    private Boolean showPrices;
    private Boolean hideAllPrices;

    // ── Giao hàng / người đặt ──────────────────────────────────────────────
    private Long   deliveryDatetime;
    private String orderedByName;

    // ── Hẹn giờ ────────────────────────────────────────────────────────────
    private String  type;        // DRAFT | SCHEDULED
    private Long    scheduledAt; // timestamp ms

    // ── Timestamps ─────────────────────────────────────────────────────────
    private Long createdAt;
    private Long updatedAt;

    // ── Items ──────────────────────────────────────────────────────────────
    private List<ItemDto> items;

    @Data
    @Builder
    public static class ItemDto {
        private Long       productId;
        private String     productName;
        private String     productImageUrl;
        private String     unit;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal basePrice;
        private String     priceMode;
        private Long       tierId;
        private String     tierName;
        private Integer    discountPercent;
        private Boolean    isManualPrice;
        private String     saleType;
        private Integer    unitsPerBox;
        private Boolean    isPromo;
        private String     promoNote;
        private Integer    itemDiscountRate;
        private String     notes;
        private BigDecimal subtotal;
        private Integer    vatRate;
        private String     vatMode;
    }

    @Data
    @Builder
    public static class SurchargeItemDto {
        private String name;
        private BigDecimal amount;
    }
}