package com.nhatnam.server.dto.request;

import lombok.Data;
import jakarta.validation.constraints.NotNull;

@Data
public class SetDefaultPriceRequest {
    @NotNull
    private Long productId;

    @NotNull
    private Long priceId;
}