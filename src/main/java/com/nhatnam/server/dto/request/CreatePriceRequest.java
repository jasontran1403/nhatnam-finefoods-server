package com.nhatnam.server.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePriceRequest {

    @NotNull(message = "Product ID is required")
    private Long productId;

    private Long variantId; // NULL = áp dụng chung

    @NotNull(message = "Price name is required")
    private String priceName;

    @NotNull(message = "Price is required")
    @Positive(message = "Price must be positive")
    private BigDecimal price;

    private Boolean isDefault;

    private Long validFrom;

    private Long validTo;
}