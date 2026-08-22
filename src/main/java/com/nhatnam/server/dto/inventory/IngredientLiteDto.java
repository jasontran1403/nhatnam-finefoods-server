package com.nhatnam.server.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IngredientLiteDto {
    private Long id;
    private String name;
    private String unit;
    private String imageUrl;
}
