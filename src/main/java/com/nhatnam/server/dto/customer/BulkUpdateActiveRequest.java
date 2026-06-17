package com.nhatnam.server.dto.customer;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class BulkUpdateActiveRequest {
    @NotEmpty(message = "Danh sách customer không được rỗng")
    private List<Long> customerIds;

    @NotNull(message = "isActive bắt buộc")
    private Boolean isActive;
}
