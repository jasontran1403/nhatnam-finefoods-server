package com.nhatnam.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateOrderRequest {
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String shippingAddress;

    /**
     * TỈNH/THÀNH và PHƯỜNG/XÃ của địa chỉ giao — chọn từ dropdown, không gõ tay.
     *
     * <p>Bắt buộc với đơn cần xác định vùng COD. Thiếu thì {@code isCodAllowed} trả false
     * và đơn rơi vào diện phải thu tiền trước — an toàn nhưng gây phiền, nên frontend
     * chặn submit khi chưa chọn.
     */
    private String provinceName;
    private String wardName;

    private Integer discountRate = 0;
    private List<SurchargeItem> surchargeItems;


    @Data
    public static class SurchargeItem {
        private String name;
        private BigDecimal amount;
    }
    /**
     * Số tiền giảm trực tiếp (nhập tay).
     * Nếu != null → ưu tiên dùng giá trị này thay vì tính từ discountRate.
     * Bị ràng buộc tối đa 10% tổng bill ở phía frontend; backend sẽ clamp
     * thêm một lần nữa để an toàn.
     */
    private BigDecimal discountAmount;

    private String type;
    private BigDecimal surcharge;      // phụ phí
    private String receiverName;       // tên người nhận
    private String receiverPhone;      // sđt người nhận
    private String receiverAddress;    // địa chỉ người nhận
    private Long receiverInfoId;       // id thông tin người nhận đã lưu

    @NotNull(message = "Vui lòng chọn kho xuất hàng")
    private Long warehouseId;

    @NotBlank
    private String paymentMethod; // CASH | BANK_TRANSFER | DEBT

    private String notes;

    /**
     * Thời gian giao hàng do người dùng nhập (timestamp milliseconds, UTC).
     * Nếu null → backend tính tự động (createdAt làm tròn lên giờ chẵn + 1h).
     */
    private Long deliveryDatetime;

    /** Tên người đặt hàng (do seller nhập, hiển thị lên phiếu đặt hàng) */
    private String orderedByName;

    /** Hiển thị giá trên phiếu đặt hàng (mặc định true) */
    private Boolean showPrices = true;

    private Boolean hideAllPrices = false;

    /**
     * Có tính KPI cho đơn hàng này không? (mặc định true)
     *
     * Chỉ có ý nghĩa khi caller là OWNER — các role khác BE bỏ qua giá trị này
     * và tự xử lý theo logic role:
     *   - SELLER       → luôn tính cá nhân về chính seller đó
     *   - SUPER_SELLER → tự động (phòng hoặc cá nhân NV được gán)
     *   - OWNER + true → cùng logic SUPER_SELLER
     *   - OWNER + false → không tính KPI cho ai (kpiUserId = -1)
     *   - Role khác    → không tính KPI (kpiUserId = -1)
     */
    private Boolean includeKpi = true;

    @NotEmpty
    @Valid
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {
        @NotNull
        private Long productId;
        private Long variantId;

        @NotNull
        @DecimalMin("0.01")
        private BigDecimal quantity;

        // "BASE" | "TIER" | "DISCOUNT_PERCENT"
        @NotBlank
        private String priceMode = "TIER";

        // null = auto-detect theo quantity
        private Long tierId;

        @Min(1) @Max(100)
        private Integer discountPercent;

        private String notes;

        @JsonProperty("sentUnitPrice")
        private BigDecimal sentUnitPrice;

        @JsonProperty("orderType")
        private String orderType;

        private Boolean isManualPrice;

        /**
         * Quy cách bán: "BOX" = bán thùng, "RETAIL" = bán lẻ (mặc định).
         */
        private String saleType = "RETAIL";

        @Min(0) @Max(100)
        private Integer vatRate;

        /**
         * VAT mode override: "INCLUSIVE" | "EXCLUSIVE".
         * Nếu null → backend dùng vatMode gốc của product.
         */
        private String vatMode;
    }
}