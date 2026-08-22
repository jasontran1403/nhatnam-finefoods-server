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
    /** Số phiếu chi do người dùng nhập (có thể null với phiếu cũ / phiếu trả công nợ NCC). */
    private String paymentNumber;
    private String vendorName;
    private Long vendorId;
    private String reason;
    private String expensePeriod;
    /** Ngày chi cụ thể (epoch ms) nếu phiếu tạo theo chế độ "Ngày"; null nếu tạo theo "Kỳ". */
    private Long expenseDate;
    private String createdByName;
    private String requestedByName;
    private Long createdById;
    private String status;
    private String approvedByName;
    private Long approvedById;
    private String approverScope;   // SUPER_ACCOUNTANT | OWNER
    private String vendorType;      // key VENDOR_TYPE_LABEL
    private String paymentType;     // CASH | BANK_TRANSFER
    private String bankName;
    private String bankRef;
    /** Tên ngân hàng của khách hàng (dùng cho phiếu hoàn phần dư qua chuyển khoản) */
    private String customerBankName;
    /** Số tài khoản ngân hàng của khách hàng */
    private String customerBankAccount;
    /** Tên chủ tài khoản ngân hàng của khách hàng */
    private String customerBankHolder;
    private String createdByRole;

    private Long approvedAt;
    private String rejectReason;
    private List<ExpenseItemDto> items;
    private BigDecimal totalAmount;
    private List<String> imageUrls;
    private Long createdAt;
    private Long updatedAt;
    /**
     * "EXPENSE" (phiếu chi phí tự do, mặc định) hoặc "VENDOR_DEBT_PAYMENT" (phiếu trả
     * công nợ NCC — gộp từ {@link com.nhatnam.server.entity.VendorExpenseVoucher} để
     * hiển thị chung trong trang "Phiếu chi"). Frontend dùng field này để hiển thị
     * nhãn/route chi tiết phù hợp.
     */
    @Builder.Default
    private String voucherType = "EXPENSE";

    @Data
    @Builder
    public static class ExpenseItemDto {
        private Long id;
        private String itemName;
        private Long categoryId;
        private BigDecimal amount;
        private String note;
    }
}