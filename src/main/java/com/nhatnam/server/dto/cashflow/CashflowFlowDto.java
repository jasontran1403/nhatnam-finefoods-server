package com.nhatnam.server.dto.cashflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/** Một dòng thu/chi trong kỳ. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CashflowFlowDto {
    private Long id;
    private String kind;          // INCOME | EXPENSE
    private String number;        // receiptNumber (thu) | paymentNumber (chi)
    private String voucherCode;
    private BigDecimal amount;
    private String reason;
    private String createdByName;
    private String approvedByName; // chỉ chi
    private String paymentType;   // CASH | BANK_TRANSFER
    private String bankName;
    private Long at;              // mốc thời gian (income=createdAt, expense=effectiveAt)
}
