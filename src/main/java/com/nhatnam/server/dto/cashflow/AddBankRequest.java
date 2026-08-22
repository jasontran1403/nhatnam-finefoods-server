package com.nhatnam.server.dto.cashflow;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddBankRequest {
    @NotBlank(message = "Tên ngân hàng là bắt buộc")
    private String name;
    private String accountNumber;
}
