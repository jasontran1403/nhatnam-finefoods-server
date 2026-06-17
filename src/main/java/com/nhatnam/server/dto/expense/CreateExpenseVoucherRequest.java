package com.nhatnam.server.dto.expense;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateExpenseVoucherRequest {

    /** Tên đơn vị thi công / nhà cung cấp — có thể rỗng */
    private String vendorName;

    @NotBlank(message = "Lý do chi là bắt buộc")
    private String reason;

    /** Người yêu cầu — nếu null, mặc định là người tạo */
    private String requestedByName;
    private Long requestedById;

    @NotEmpty(message = "Phải có ít nhất 1 khoản chi")
    private List<ExpenseItemRequest> items;

    /** URLs ảnh chứng từ (đã upload trước) */
    private List<String> imageUrls;

    @Data
    public static class ExpenseItemRequest {
        @NotBlank(message = "Tên khoản chi là bắt buộc")
        private String itemName;

        @Positive(message = "Số tiền phải lớn hơn 0")
        private BigDecimal amount;

        private String note;
    }
}
