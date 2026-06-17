package com.nhatnam.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateVariantRequest {

    @NotNull(message = "Product ID is required")
    private Long productId;

    @NotBlank(message = "Variant name is required")
    private String variantName;

    private String variantCode;

    private Boolean isDefault;

    private Integer displayOrder;

    @NotNull(message = "Ingredients list is required")
    private List<VariantIngredientItem> ingredients;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantIngredientItem {
        @NotNull(message = "Ingredient ID is required")
        private Long ingredientId;

        @NotNull(message = "Quantity is required")
        private BigDecimal quantity;
    }
}