package com.nhatnam.server.dto.request;

import lombok.Data;
import jakarta.validation.constraints.NotNull;

@Data
public class RemovePriceRequest {
    @NotNull
    private Long productId;

    @NotNull
    private Long priceId;
}
