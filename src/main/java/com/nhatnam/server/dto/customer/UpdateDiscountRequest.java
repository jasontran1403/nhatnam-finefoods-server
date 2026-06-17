package com.nhatnam.server.dto.customer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateDiscountRequest {
    @NotNull
    @Min(value = 0, message = "Chiết khấu không hợp lệ")
    @Max(value = 100, message = "Chiết khấu không hợp lệ")
    private Integer discountRate;
}
