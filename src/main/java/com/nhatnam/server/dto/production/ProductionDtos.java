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
        private String unit;                 // đơn vị LƯU KHO
        private String orderUnit;            // đơn vị ĐẶT HÀNG (nullable)
        private BigDecimal conversionRatio;  // 1 orderUnit = ratio unit (nullable)
        private Integer shelfLifeDays;       // HSD tính theo số ngày (>0)
        private Integer supplierLeadDays;    // số ngày NCC giao dự kiến (>0)
        private String storageCondition;     // điều kiện bảo quản (text)
        private String description;
        private Boolean isMixable;
        private Boolean isActive;
        private Long createdAt;

        // Danh mục: nguyên liệu ⟶ danh mục riêng ⟶ danh mục chung
        private Long subCategoryId;          // danh mục riêng
        private String subCategoryName;
        private Long categoryId;             // danh mục chung
        private String categoryName;

        // Các xưởng mà nguyên liệu này tồn tại
        private List<Long> factoryIds;
        private List<String> factoryNames;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveFactoryMaterialRequest {
        private String name;
        private String unit;                 // đơn vị lưu kho (bắt buộc)
        private String orderUnit;            // đơn vị đặt hàng (nullable)
        private BigDecimal conversionRatio;  // tỷ lệ quy đổi (nullable; bắt buộc nếu có orderUnit khác unit)
        private Integer shelfLifeDays;       // HSD số ngày (>0)
        private Integer supplierLeadDays;    // số ngày NCC giao (>0)
        private String storageCondition;
        private String description;
        private Boolean isMixable;           // sản phẩm đầu ra của mix gia vị (Mục 4)
        private Long subCategoryId;          // danh mục riêng (bắt buộc)
        private List<Long> factoryIds;       // xưởng có nguyên liệu; null/rỗng khi TẠO = tất cả xưởng
    }

    // ── Danh mục nguyên liệu xưởng (chung / riêng) ────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryMaterialSubCategoryDto {
        private Long id;
        private String name;
        private Long categoryId;
        private String categoryName;
        private String description;
        private Boolean isActive;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryMaterialCategoryDto {
        private Long id;
        private String name;
        private String description;
        private Boolean isActive;
        private List<FactoryMaterialSubCategoryDto> subCategories; // danh mục riêng trực thuộc
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveCategoryRequest {
        private String name;
        private String description;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveSubCategoryRequest {
        private String name;
        private Long categoryId;
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
        /** Ingredient gốc liên kết (cầu nối tới kho bán hàng) — nullable nếu chưa liên kết */
        private Long ingredientId;
        private String ingredientName;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveFactoryProductRequest {
        /** Tuỳ chọn: liên kết từ danh sách Ingredient có sẵn — name/unit sẽ tự lấy theo Ingredient này */
        private Long ingredientId;
        /** Nếu không liên kết ingredient, nhập trực tiếp tên + đơn vị */
        private String name;
        private String unit;
        private String description;
    }

    // ─────────────────────────────────────────────────────────────
    // Recipe (= Biến thể sản xuất của 1 FactoryProduct)
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
    public static class RecipeStepDto {
        private Long id;
        private Long stepTemplateId;
        private String stepName;
        private Integer sortOrder;
        private boolean requiresQc;
        /** NONE | VISUAL | PHOTO_WEIGHT */
        private String controlType;
        private Integer durationMinutes;
        private Long machineId;
        private String machineName;
        /** Bước làm chung cả lệnh (true) hay riêng từng mẻ (false) */
        private boolean shared;
        /** Công suất tối đa mỗi lần của bước chung (kg) — null = làm chung toàn bộ 1 lần */
        private BigDecimal capacityPerRun;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductionRecipeDto {
        private Long id;
        private Long factoryProductId;
        private String factoryProductName;
        private String name;
        private BigDecimal standardOutputQty;
        private String outputUnit;
        /** Định lượng đóng gói chuẩn (VD: 0.5 kg/túi) — chỉ dùng ước tính, không dùng tính hao hụt */
        private BigDecimal packagingQty;
        private String packagingUnit;
        private String notes;
        private Boolean isActive;
        private String createdByName;
        private Long createdAt;
        private List<RecipeItemDto> items;
        private List<RecipeStepDto> steps;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RecipeItemRequest {
        private Long factoryMaterialId;
        private BigDecimal standardQty;
        private String unit;
        private Integer sortOrder;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RecipeStepRequest {
        private Long stepTemplateId;
        /** Tên bước — nếu null sẽ lấy tên từ stepTemplate */
        private String stepName;
        private Integer sortOrder;
        private boolean requiresQc;
        /** NONE | VISUAL | PHOTO_WEIGHT — nếu null sẽ suy ra từ requiresQc (true → PHOTO_WEIGHT) */
        private String controlType;
        private Integer durationMinutes;
        private Long machineId;
        /** Bước làm chung cả lệnh (true) hay riêng từng mẻ (false, mặc định) */
        private boolean shared;
        /** Công suất tối đa mỗi lần của bước chung (kg) — null/≤0 = làm chung toàn bộ 1 lần */
        private BigDecimal capacityPerRun;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveRecipeRequest {
        private Long factoryProductId;
        private String name;
        private BigDecimal standardOutputQty;
        private String outputUnit;
        /** Định lượng đóng gói chuẩn (VD: 0.5 kg/túi) — nullable */
        private BigDecimal packagingQty;
        private String packagingUnit;
        private String notes;
        private List<RecipeItemRequest> items;
        private List<RecipeStepRequest> steps;
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