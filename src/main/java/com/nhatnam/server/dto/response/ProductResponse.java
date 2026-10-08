package com.nhatnam.server.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductResponse {

    private Long    id;
    private String  name;
    private String  description;
    private String  unit;

    // Category
    private Long    categoryId;
    private String  categoryName;

    private String  imageUrl;
    private Boolean isActive;

    private BigDecimal stockQuantity;

    private String vatMode;

    private BigDecimal basePrice;

    // vatRate lưu dạng int (0, 5, 8, 10)
    private Integer vatRate;

    private Integer maxDiscountRate;    // % chiết khấu tối đa

    /** Số đơn vị / thùng. null hoặc 0 = không hỗ trợ bán thùng. */
    private Integer unitsPerBox;

    /** Quy cách (gr/đơn vị): 500, 424, 410, 210, 1000 */
    private Integer specification;

    /** Danh mục MISA: Kem, Xúc xích bò, Xúc xích heo, Xúc xích gà */
    private String misaCategory;

    private Long createdAt;
    private Long updatedAt;

    // ── Khung giá sỉ theo số lượng ───────────────────────────────
    private List<PriceTierResponse> priceTiers;

    // ── Nguyên liệu trực tiếp ─────────────────────────────────────
    private List<IngredientItem> ingredients;

    // ─────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PriceTierResponse {
        private Long       id;
        private String     tierName;
        private BigDecimal minQuantity;
        private BigDecimal maxQuantity;
        private BigDecimal price;
        private Integer    sortOrder;
        private Boolean    isActive;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class IngredientItem {
        private Long   ingredientId;
        private String ingredientName;
        private String ingredientImageUrl;
        private String unit;
        private BigDecimal stockQuantity;
        private BigDecimal quantity;
        private Boolean canOverride;
    }
}