package com.nhatnam.server.dto.gift;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** DTO của module phiếu tặng quà bằng sản phẩm. */
public final class GiftOrderDtos {

    private GiftOrderDtos() {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GiftOrderDto {
        private Long id;
        private String code;

        private Long customerId;
        private String customerName;
        private String customerPhone;
        private String customerType;

        private Long warehouseId;
        private String warehouseName;

        private String occasion;     // BIRTHDAY | STORE_OPENING | OTHER
        private String status;       // PENDING | APPROVED | DELIVERING | COMPLETED | REJECTED | CANCELLED
        private String note;

        private String createdByName;
        private Long createdAt;

        private String approvedByName;
        private Long approvedAt;
        private String rejectReason;

        private String handledByName;
        private Long handledAt;

        private Long warehouseReceiptId;

        private List<GiftOrderItemDto> items;
        private BigDecimal totalQuantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GiftOrderItemDto {
        private Long productId;
        private String productName;
        private String unit;
        private BigDecimal quantity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateGiftOrderRequest {

        @NotNull(message = "Vui lòng chọn khách hàng")
        private Long customerId;

        @NotNull(message = "Vui lòng chọn kho xuất hàng")
        private Long warehouseId;

        /** BIRTHDAY | STORE_OPENING | OTHER — suy ra từ loại khách nếu để trống. */
        private String occasion;

        private String note;

        private List<ItemInput> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemInput {
        private Long productId;
        private BigDecimal quantity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectRequest {
        private String reason;
    }

    /**
     * KẾT QUẢ KIỂM TỒN KHO khi duyệt.
     *
     * <p>Khi thiếu hàng, {@code ok = false} và {@code shortages} liệt kê từng nguyên liệu
     * thiếu kèm số cần / số còn. Phiếu KHÔNG được duyệt và KHÔNG trừ kho — OWNER đọc
     * danh sách này để báo nhập kho rồi duyệt lại.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockCheckResult {
        private boolean ok;
        private List<Shortage> shortages;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Shortage {
        /** Sản phẩm trong phiếu kéo theo thiếu hụt này. */
        private String productName;
        private Long ingredientId;
        private String ingredientName;
        private String unit;
        private BigDecimal required;
        private BigDecimal available;
        private BigDecimal missing;
    }

    /** Sản phẩm khả dụng của một kho — dùng cho bước chọn hàng ở form tạo phiếu. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GiftProductOption {
        private Long id;
        private String name;
        private String unit;
        /**
         * Số lượng còn có thể xuất theo tồn nguyên liệu hiện tại.
         * Chỉ để THAM KHẢO lúc chọn hàng — tồn có thể đổi trước khi phiếu được duyệt,
         * nên đây không phải cam kết giữ hàng.
         */
        private BigDecimal availableQty;

        /**
         * Cho phép nhập số lẻ hay không (tối đa 3 chữ số thập phân).
         *
         * <p>Suy từ đơn vị tính — xem {@code UnitDecimalRule}. FE dùng cờ này để đặt
         * {@code step} của ô nhập, và backend vẫn kiểm tra lại khi tạo phiếu vì cờ do
         * client gửi lên không đáng tin.
         */
        private boolean allowDecimal;
    }
}
