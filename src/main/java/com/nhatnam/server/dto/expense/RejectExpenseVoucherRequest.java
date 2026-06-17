package com.nhatnam.server.dto.expense;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectExpenseVoucherRequest {
    @NotBlank(message = "Lý do từ chối là bắt buộc")
    private String reason;
}
