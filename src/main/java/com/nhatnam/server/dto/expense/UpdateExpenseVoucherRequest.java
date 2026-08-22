// Trong UpdateExpenseVoucherRequest.java - thêm vendorId và vendorName

package com.nhatnam.server.dto.expense;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class UpdateExpenseVoucherRequest {

    @NotBlank(message = "Lý do chi không được để trống")
    private String reason;

    /** Tên nhà cung cấp */
    private String vendorName;

    /** ID nhà cung cấp */
    private Long vendorId;

    /** Ngày chi (epoch ms) - null nếu dùng kỳ */
    private Long expenseDate;

    /** Kỳ chi (yyyy-MM) - null nếu dùng ngày */
    private String expensePeriod;

    /** CASH | BANK_TRANSFER */
    private String paymentType;

    /** Tên ngân hàng (bắt buộc nếu paymentType = BANK_TRANSFER) */
    private String bankName;

    /** Mã tham chiếu (bắt buộc nếu paymentType = BANK_TRANSFER) */
    private String bankRef;

    @Valid
    @NotNull(message = "Danh sách khoản chi không được để trống")
    @Size(min = 1, message = "Phiếu chi phải có ít nhất 1 khoản chi")
    private List<ExpenseItemRequest> items;

    @Data
    public static class ExpenseItemRequest {
        private Long id; // null = thêm mới
        private Long categoryId;
        private BigDecimal amount;
        private String note;
    }
}