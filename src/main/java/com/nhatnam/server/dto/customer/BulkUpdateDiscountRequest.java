package com.nhatnam.server.dto.customer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class BulkUpdateDiscountRequest {
    @NotEmpty(message = "Danh sách customer không được rỗng")
    private List<Long> customerIds;

    @NotNull(message = "discountRate bắt buộc")
    @Min(value = 0, message = "Chiết khấu không hợp lệ")
    @Max(value = 100, message = "Chiết khấu không hợp lệ")
    private Integer discountRate;
}
