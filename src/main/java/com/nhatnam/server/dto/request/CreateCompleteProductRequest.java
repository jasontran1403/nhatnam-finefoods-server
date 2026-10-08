package com.nhatnam.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateCompleteProductRequest {

    @NotBlank
    private String name;

    private String description;
    private String imageUrl;
    private String category;
    @JsonProperty("categoryId")
    private Long categoryId;   // ← THÊM (nhận từ Flutter)
    private String unit;

    // ── Giá gốc (tier pricing) ────────────────────────────────────
    private BigDecimal basePrice;

    // ── VAT rate (int: 0, 5, 8, 10) ──────────────────────────────
    private int vatRate = 8;   // mặc định 8%
    private String vatMode = "INCLUSIVE";  // mặc định đã tính vào giá

    /** Chiết khấu tối đa (%) seller được phép áp khi tạo đơn. 0 = không giới hạn */
    private Integer maxDiscountRate = 0;

    /**
     * Số đơn vị tính trong 1 thùng.
     * null hoặc 0 = không hỗ trợ bán thùng (chỉ bán lẻ).
     */
    private Integer unitsPerBox;

    /** Quy cách (gr/đơn vị): 500, 424, 410, 210, 1000 — chỉ khi unit ≠ Kg */
    private Integer specification;

    /** Danh mục MISA: Kem, Xúc xích bò, Xúc xích heo, Xúc xích gà */
    private String misaCategory;

    // ── Khung giá sỉ ──────────────────────────────────────────────
    private List<TierItem> tiers;

    // ── Giá cũ (backward-compat) ──────────────────────────────────
    private List<PriceItem> prices;

    // ── Biến thể hoặc nguyên liệu trực tiếp ──────────────────────
    private List<VariantItem> variants;
    private List<IngredientItem> ingredients;

    // ─────────────────────────────────────────────────────────────
    @Data
    public static class TierItem {
        private Long       id;           // null = mới, non-null = update
        @NotBlank
        private String     tierName;
        @NotNull
        private BigDecimal minQuantity;
        private BigDecimal maxQuantity;  // null = không giới hạn
        @NotNull
        private BigDecimal price;
        private Integer    sortOrder;
    }

    @Data
    public static class PriceItem {
        private Long       id;
        private String     priceName;
        private BigDecimal price;
        private boolean    isDefault;

        // Lombok không gen getter cho boolean field tên "isDefault" đúng cách
        // → dùng explicit getter
        public boolean isDefault() { return isDefault; }
    }

    @Data
    public static class VariantItem {
        private String variantName;
        private boolean isDefault;
        private List<IngredientItem> ingredients;

        public boolean isDefault() { return isDefault; }
    }

    @Data
    public static class IngredientItem {
        private Long       ingredientId;
        private BigDecimal quantity;

        private Boolean canOverride = false;
    }
}