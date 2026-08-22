package com.nhatnam.server.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderResponse {

    private Long   id;
    private String orderCode;
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;       // ← thêm (dùng trong SellerController invoice)
    private String shippingAddress;
    private String warehouseName;
    private BigDecimal subtotal;        // tổng trước chiết khấu
    private BigDecimal discountAmount;
    private BigDecimal vatAmount;
    private BigDecimal totalAmount;     // sau chiết khấu, chưa VAT
    private BigDecimal finalAmount;     // thanh toán cuối = totalAmount + vatAmount
    private BigDecimal surcharge;
    private BigDecimal actualAmountPaid;
    private String estimatedDelivery;       // "12:00-16:00 dd/MM/yyyy" – backend tính
    private String paymentDeadline;
    private String orderedByName;
    private String receiverName;
    private String surchargeDetail;

    /** Số phiếu thu (receiptNumber) của các phiếu thu đã liên kết với đơn này — có thể nhiều phiếu nếu thu nhiều lần */
    private List<String> receiptNumbers;
    @Builder.Default
    private List<DriverInfo> drivers = new java.util.ArrayList<>();
    @Builder.Default
    private List<Map<String, Object>> deliveryInfo = new java.util.ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriverInfo {
        private Long   id;
        private String name;
    }


    @Builder.Default
    private Integer discountRate = 0;

    private BigDecimal paidAmount;
    private String status;
    private String paymentStatus;
    private String paymentMethod;
    private String notes;
    private Long   createdAt;

    /** Thời gian giao hàng (timestamp ms). Đã được tính/lưu sẵn, dùng để hiển thị. */
    private Long    deliveryDatetime;
    private Boolean showPrices;
    private Boolean hideAllPrices;

    private String customerType;
    private String companyName;
    private String taxCode;
    private String contactName;
    private String deliveryAddress;
    private String companyPhone;
    private String companyAddress;

    private List<OrderItemResponse> items;
    private List<VatBreakdownItem> vatBreakdown;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VatBreakdownItem {
        private Integer    rate;    // 5, 8, 10 …
        private BigDecimal amount;
    }

    // ─────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItemResponse {
        private Long   id;
        private Long   productId;
        private String productName;
        private String productImageUrl;
        private String unit;

        // ── Quy cách bán ──────────────────────────────────────────────
        private String  saleType;
        private Integer unitsPerBox;

        // ── Giá ──────────────────────────────────────────────────────
        private BigDecimal basePrice;
        private BigDecimal originalUnitPrice;
        private BigDecimal unitPrice;
        private String     vatMode;
        private String     priceMode;
        private String     priceName;
        private Long       tierId;
        private String     tierName;
        private Integer    discountPercent;
        private BigDecimal defaultPrice;

        // ── VAT ──────────────────────────────────────────────────────
        private Integer    vatRate;
        private BigDecimal vatAmount;

        // ── Số lượng & tổng ──────────────────────────────────────────
        private BigDecimal quantity;
        private BigDecimal subtotal;
        private String     notes;

        // ── Snapshot fields mới ──────────────────────────────────────
        private String     categorySnapshot;
        private String     skuSnapshot;
        private String     packagingDescriptionSnapshot;
        private Integer    maxDiscountRateSnapshot;
        private BigDecimal tierPriceSnapshot;

        private List<IngredientUsed> ingredientsUsed;
    }

    // ── Quy tắc thu tiền trước (chỉ đọc, tính ở server) ──────────────────────
    /**
     * true = kho KHÔNG được bắt đầu giao khi đơn chưa thu đủ tiền.
     *
     * <p>Tính ở server để giao diện kho vô hiệu hoá nút "Bắt đầu giao" ngay từ đầu, thay
     * vì cho bấm rồi mới trả lỗi — nhân viên kho không có cách nào biết trước là đơn nào
     * bị chặn, và mỗi lần bấm hụt là một lần phải đi hỏi lại kinh doanh.
     */
    private Boolean requirePrepaymentEffective;

    /** Tỉnh/thành và phường/xã của địa chỉ giao — để màn hình kho hiển thị đủ. */
    private String provinceName;
    private String wardName;

    /** Lý do bị chặn, hiển thị làm tooltip cạnh nút bị khoá. */
    private String prepaymentReason;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class IngredientUsed {
        private Long       ingredientId;
        private String     ingredientName;
        private String     ingredientImageUrl;
        private BigDecimal quantityUsed;
        private String     unit;
        private BigDecimal qtyPerUnit;  // ← thêm
    }
}