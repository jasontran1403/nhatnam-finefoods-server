package com.nhatnam.server.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// IngredientResponse.java
public class IngredientResponse {
    private Long id;
    private String name;
    private String imageUrl;
    private String unit;
    private Long createdAt;
    private Long updatedAt;

    private String itemCode;      // ← THÊM
    private Long categoryId;      // ← THÊM
    private Long subCategoryId;   // ← THÊM

    // Bỏ stockQuantity cố định, thay bằng map tồn kho theo từng kho
    // Key = warehouseId, Value = stockQuantity
    private Map<Long, BigDecimal> stockByWarehouse; // optional, dùng khi cần show all
    private BigDecimal stockQuantity;               // optional, dùng khi query theo 1 kho cụ thể

    @Builder.Default
    private List<Long> warehouseIds = new java.util.ArrayList<>();
}