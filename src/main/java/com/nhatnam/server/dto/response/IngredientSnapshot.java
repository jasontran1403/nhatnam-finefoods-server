package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class IngredientSnapshot {
    private Long ingredientId;
    private String ingredientName;
    private String ingredientImageUrl;
    private BigDecimal quantityUsed;
    private String unit;
}