package com.nhatnam.server.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InventorySummaryDto {
    private Long from;
    private Long to;                       // đã clamp về hiện tại nếu chọn tương lai
    private List<WarehouseLiteDto> warehouses;
    private List<InventoryIngredientDto> ingredients;
}
