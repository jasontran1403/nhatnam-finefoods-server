package com.nhatnam.server.dto.production;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

// ─────────────────────────────────────────────────────────────────
// FactoryMaterial
// ─────────────────────────────────────────────────────────────────
public class ProductionDtos {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryMaterialDto {
        private Long id;
        private String name;
        private String unit;
        private String description;
        private Boolean isActive;
        private Long createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveFactoryMaterialRequest {
        private String name;
        private String unit;
        private String description;
    }

    // ─────────────────────────────────────────────────────────────
    // FactoryProduct
    // ─────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryProductDto {
        private Long id;
        private String name;
        private String unit;
        private String description;
        private Boolean isActive;
        private Long createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveFactoryProductRequest {
        private String name;
        private String unit;
        private String description;
    }

    // ─────────────────────────────────────────────────────────────
    // Recipe
    // ─────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecipeItemDto {
        private Long id;
        private Long factoryMaterialId;
        private String materialName;
        private BigDecimal standardQty;
        private String unit;
        private Integer sortOrder;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductionRecipeDto {
        private Long id;
        private Long factoryProductId;
        private String factoryProductName;
        private String name;
        private BigDecimal standardOutputQty;
        private String outputUnit;
        private String notes;
        private Boolean isActive;
        private String createdByName;
        private Long createdAt;
        private List<RecipeItemDto> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RecipeItemRequest {
        private Long factoryMaterialId;
        private BigDecimal standardQty;
        private String unit;
        private Integer sortOrder;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveRecipeRequest {
        private Long factoryProductId;
        private String name;
        private BigDecimal standardOutputQty;
        private String outputUnit;
        private String notes;
        private List<RecipeItemRequest> items;
    }

    // ─────────────────────────────────────────────────────────────
    // ProductionBatch
    // ─────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BatchItemDto {
        private Long id;
        private Long factoryMaterialId;
        private String materialName;
        private BigDecimal actualQty;
        private String unit;
        private BigDecimal standardQty;
        private BigDecimal variancePct;  // + hoặc –
        private Integer sortOrder;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductionBatchDto {
        private Long id;
        private String batchCode;
        private Long recipeId;
        private String productName;
        private String recipeName;
        private BigDecimal actualOutputQty;
        private BigDecimal standardOutputQty;
        private BigDecimal outputVariancePct;  // hao hụt thành phẩm
        private String outputUnit;
        private Long producedAt;
        private String notes;
        private String status;
        private String createdByName;
        private Long createdAt;
        private List<BatchItemDto> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class BatchItemRequest {
        private Long factoryMaterialId;
        private BigDecimal actualQty;
        private String unit;
        private Integer sortOrder;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateBatchRequest {
        private Long recipeId;
        private BigDecimal actualOutputQty;
        private Long producedAt;  // epoch ms, null = now
        private String notes;
        private List<BatchItemRequest> items;
    }
}
