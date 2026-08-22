package com.nhatnam.server.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InventoryIngredientDto {
    private Long ingredientId;
    private String name;
    private String unit;
    private String imageUrl;

    private Long categoryId;
    private String categoryName;
    private Long subCategoryId;
    private String subCategoryName;

    private BigDecimal totalOpening;
    private BigDecimal totalNhap;
    private BigDecimal totalBan;
    private BigDecimal totalXuat;
    private BigDecimal totalClosing;

    /** Số liệu theo từng kho (chỉ kho có tồn/phát sinh). */
    private List<InventoryCellDto> byWarehouse;
}
