package com.nhatnam.server.dto.expense;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ExpenseVoucherDto {
    private Long id;
    private String voucherCode;
    private String vendorName;
    private String reason;
    private String createdByName;
    private String requestedByName;
    private Long createdById;
    private String status;
    private String approvedByName;
    private Long approvedAt;
    private String rejectReason;
    private List<ExpenseItemDto> items;
    private BigDecimal totalAmount;
    private List<String> imageUrls;
    private Long createdAt;
    private Long updatedAt;

    @Data
    @Builder
    public static class ExpenseItemDto {
        private Long id;
        private String itemName;
        private BigDecimal amount;
        private String note;
    }
}
