package com.nhatnam.server.dto.income;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder
public class IncomeVoucherDto {
    private Long id;
    private String voucherCode;
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
    private List<IncomeItemDto> items;
    private BigDecimal totalAmount;
    private List<String> imageUrls;
    private Long createdAt;
    private Long updatedAt;

    @Data @Builder
    public static class IncomeItemDto {
        private Long id;
        private String itemName;
        private BigDecimal amount;
        private String note;
    }
}
