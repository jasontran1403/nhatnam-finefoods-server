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
    private String customerName;
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
    /** Tổng tiền GROSS (Σ items.amount) — số nguyên owner đã nhập, chưa trừ cấn trừ. */
    private BigDecimal totalAmount;
    /**
     * Tổng tiền THỰC THU = {@code totalAmount − offsetUsedAmount}. Đây là số phải
     * dùng khi cộng doanh thu/dòng tiền để không bị DOUBLE-COUNT với các phiếu con
     * được tạo bằng "Cấn trừ". FE list card và dashboard hiển thị số này.
     */
    private BigDecimal effectiveTotalAmount;
    private List<String> imageUrls;
    private Long createdAt;
    private Long updatedAt;

    /**
     * PHẦN KHÁCH TRẢ DƯ của phiếu (nếu có) — gắn ở đơn cuối trong danh sách.
     * FE dùng để hiện nút "Tạo phiếu chi hoàn phần dư". {@code null} = không dư.
     */
    private OverpayInfoDto overpay;

    /** ID phiếu NGUỒN nếu phiếu này được cấn trừ từ phiếu khác. */
    private Long offsetSourceVoucherId;
    /** Số phiếu NGUỒN — tiện FE hiển thị. */
    private String offsetSourceReceiptNumber;
    /** Tổng số tiền đã cấn trừ TỪ phiếu này sang các phiếu con. */
    private BigDecimal offsetUsedAmount;

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