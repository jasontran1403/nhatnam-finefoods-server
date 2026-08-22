package com.nhatnam.server.dto.income;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder
public class IncomeVoucherDto {
    private Long id;
    private String voucherCode;
    private String receiptNumber;
    private String payerName;
    private String reason;
    private String createdByName;
    private Long createdById;
    private String status;
    private String approvedByName;
    private Long approvedAt;
    private String rejectReason;
    private String paymentType;  // CASH | BANK_TRANSFER
    private String bankName;
    private String bankRef;
    private List<String> linkedOrderCodes;

    /**
     * Số tiền phiếu này đã ghi cho từng đơn: { orderCode → amount }.
     * Form SỬA đọc để hiện đúng "còn lại" của đơn (cộng ngược phần phiếu này),
     * kể cả khi đơn được nhiều phiếu cùng trả.
     */
    private java.util.Map<String, java.math.BigDecimal> orderAllocations;
    /** Lần chỉnh sửa gần nhất — null nếu phiếu chưa từng được sửa. */
    private String lastEditedByName;
    private Long lastEditedAt;   // epoch ms; null nếu chưa sửa
    /** Tên khách hàng của các đơn liên kết — đã loại trùng (nhiều đơn cùng tên chỉ hiện 1). */
    private List<String> linkedCustomerNames;
    private List<IncomeItemDto> items;
    private BigDecimal totalAmount;
    private List<String> imageUrls;
    private Long createdAt;
    private Long updatedAt;

    /**
     * PHẦN KHÁCH TRẢ DƯ của phiếu (nếu có) — gắn ở đơn cuối trong danh sách.
     * FE dùng để hiện nút "Tạo phiếu chi hoàn phần dư". {@code null} = không dư.
     */
    private OverpayInfoDto overpay;

    @Data @Builder
    public static class IncomeItemDto {
        private Long id;
        private String itemName;
        private BigDecimal amount;
        private String note;
    }

    @Data @Builder
    public static class OverpayInfoDto {
        /** Đơn gánh phần dư (đơn cuối trong danh sách). */
        private String orderCode;
        /** Tên khách của đơn — dùng làm người nhận trên phiếu chi hoàn. */
        private String customerName;
        /** Số tiền dư cần hoàn lại. */
        private BigDecimal amount;
        /** Mã phiếu chi hoàn đã lập ({@code null} = chưa lập → còn hiện nút). */
        private String refundVoucherCode;
    }
}
