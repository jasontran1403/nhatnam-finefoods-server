package com.nhatnam.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateIngredientRequest {
    @NotBlank(message = "Ingredient name is required")
    private String name;

    @NotBlank(message = "Unit is required")
    private String unit;

    private String imageUrl;

    private String itemCode;      // ← THÊM

    private Long categoryId;      // ← THÊM

    private Long subCategoryId;   // ← THÊM
}