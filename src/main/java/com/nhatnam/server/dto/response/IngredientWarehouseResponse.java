// response/IngredientWarehouseResponse.java
package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class IngredientWarehouseResponse {
    private Long id;
    private Long ingredientId;
    private String ingredientName;
    private Long warehouseId;
    private String warehouseName;
    private Long createdAt;
}