package com.nhatnam.server.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class SaveDraftRequest {

    // ── Khách hàng ─────────────────────────────────────────────────────────
    private Long   customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String shippingAddress;

    /** Tỉnh/thành + phường/xã của địa chỉ giao — chọn từ danh mục (giữ để tạo đơn thật). */
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

    /** List phụ phí chi tiết: [{"name":"Thùng xốp","amount":20000}] */
    private List<SurchargeItemDto> surchargeItems;

    private Long   warehouseId;
    private String warehouseName;

    // ── Hiển thị giá ───────────────────────────────────────────────────────
    private Boolean showPrices    = Boolean.TRUE;
    private Boolean hideAllPrices = Boolean.FALSE;

    // ── Giao hàng / người đặt ──────────────────────────────────────────────
    private Long   deliveryDatetime;
    private String orderedByName;

    // ── Hẹn giờ xuất đơn ──────────────────────────────────────────────────
    /**
     * "DRAFT"     → lưu nháp thường
     * "SCHEDULED" → lưu đơn hẹn giờ
     */
    private String type = "DRAFT";

    /**
     * Timestamp (ms) thời điểm cần xuất đơn.
     * Bắt buộc khi type = SCHEDULED.
     */
    private Long scheduledAt;

    // ── Sản phẩm ───────────────────────────────────────────────────────────
    private List<ItemDto> items;

    // ── Nested DTOs ────────────────────────────────────────────────────────
    @Data
    public static class ItemDto {
        private Long       productId;
        private String     productName;
        private String     productImageUrl;
        private String     unit;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal basePrice;
        private String     priceMode;   // BASE | TIER | DISCOUNT_PERCENT
        private Long       tierId;
        private String     tierName;
        private Integer    discountPercent;
        private Boolean    isManualPrice;
        private String     saleType;    // RETAIL | BOX
        private Integer    unitsPerBox;
        private Boolean    isPromo;
        private String     promoNote;
        private Integer    itemDiscountRate;
        private String     notes;
        private BigDecimal subtotal;
        private Integer    vatRate;
        private String     vatMode;     // INCLUSIVE | EXCLUSIVE
    }

    @Data
    public static class SurchargeItemDto {
        private String name;
        private BigDecimal amount;
    }
}