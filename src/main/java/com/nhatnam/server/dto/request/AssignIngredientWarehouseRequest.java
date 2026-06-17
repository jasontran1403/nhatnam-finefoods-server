// request/AssignIngredientWarehouseRequest.java
package com.nhatnam.server.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

@Data
public class AssignIngredientWarehouseRequest {
    @NotNull private Long ingredientId;
    @NotNull private List<Long> warehouseIds; // danh sách kho gán (replace all)
}