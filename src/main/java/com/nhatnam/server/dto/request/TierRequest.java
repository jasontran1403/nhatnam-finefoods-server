package com.nhatnam.server.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class TierRequest {
    @NotBlank  private String tierName;

    @NotNull @DecimalMin("0")
    private BigDecimal minQuantity;

    private BigDecimal maxQuantity; // null = unlimited

    @NotNull @DecimalMin("0")
    private BigDecimal price;

    private Integer sortOrder = 0;
}
