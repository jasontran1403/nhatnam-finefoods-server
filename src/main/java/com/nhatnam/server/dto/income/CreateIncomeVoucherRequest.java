package com.nhatnam.server.dto.income;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateIncomeVoucherRequest {

    private String payerName;

    @NotBlank(message = "Lý do thu là bắt buộc")
    private String reason;

    /** CASH | BANK_TRANSFER */
    private String paymentType;

    private String bankName;
    private String bankRef;

    @NotBlank(message = "Số phiếu thu là bắt buộc")
    private String receiptNumber;

    /** Danh sách mã đơn hàng liên kết (tuỳ chọn) */
    private List<String> linkedOrderCodes;

    /**
     * Số tiền thực thu (bắt buộc khi có linkedOrderCodes).
     */
    private BigDecimal collectedAmount;

    /**
     * Kiểm tra race condition: client gửi lên số tiền còn lại của từng đơn
     * tại thời điểm load. Backend so sánh với actualRemaining hiện tại.
     * Nếu lệch → trả 409.
     */
    private List<ExpectedOrderAmount> expectedOrderAmounts;

    /**
     * Cách xử lý đơn cuối khi thu thiếu 1 phần:
     *   "PARTIAL" — ghi nhận thu 1 phần, đơn vẫn PENDING_PAYMENT
     *   "FULL"    — ghi nhận đã thu đủ dù thực tế thiếu, đơn → COMPLETED
     */
    private String lastOrderHandling;

    @NotEmpty(message = "Phải có ít nhất 1 khoản thu")
    private List<IncomeItemRequest> items;

    private List<String> imageUrls;

    @Data
    public static class IncomeItemRequest {
        @NotBlank(message = "Tên khoản thu là bắt buộc")
        private String itemName;

        @Positive(message = "Số tiền phải lớn hơn 0")
        private BigDecimal amount;

        private String note;
    }

    @Data
    public static class ExpectedOrderAmount {
        /** Mã đơn hàng */
        private String orderCode;
        /**
         * Số tiền còn lại client đang thấy =
         * order.totalAmount - order.paidAmount tại thời điểm load
         */
        private BigDecimal expectedRemainingAmount;
    }
}
