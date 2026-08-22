package com.nhatnam.server.dto.ingredient;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngredientStockRowDto {
    // Ingredient info
    private Long   ingredientId;
    private String ingredientName;
    private String ingredientImageUrl;
    private String unit;

    // Category / SubCategory
    private Long   categoryId;
    private String categoryName;
    private Long   subCategoryId;
    private String subCategoryName;

    // Stock in this warehouse
    private BigDecimal stockQuantity;

    // Expiry info
    private LocalDate nearestExpiryDate;       // ngày hết hạn gần nhất (null nếu không có lô)
    private BigDecimal nearExpiryQuantity;     // tổng số lượng các lô có hạn <= 3 tháng
    private Integer daysUntilExpiry;           // số ngày tới hạn gần nhất (null nếu không có)

    /**
     * Badge level:
     *   NONE     — không có lô sắp hết hạn
     *   WARNING  — có lô <= 3 tháng
     *   DANGER   — có lô <= 1 tháng
     */
    private String expiryBadge;

    /**
     * Danh sách từng lô (FIFO) — để admin thấy chi tiết HSD + giá vốn từng lô.
     * Sort: expiryDate ASC (null cuối).
     */
    private List<LotDto> lots;

    /** Tổng giá vốn tích lũy (= sum của quantity * costPrice theo từng lô còn hàng) */
    private BigDecimal totalCostValue;

    // Meta
    private Long updatedAt;

    /**
     * MÀU TÌNH TRẠNG LÔ — mức ưu tiên hiển thị, gắt nhất thắng:
     * <pre>
     *   EXPIRED_OR_CRITICAL  (đỏ cam) — có lô đã hết hạn HOẶC sắp hết hạn &lt; 7 ngày
     *   NEAR_EXPIRY          (vàng)   — có lô sắp hết hạn trong vòng 1 tháng
     *   NEWLY_STOCKED        (xanh dương nhạt) — có lô mới nhập &lt; 1 tháng
     *   NONE                 (trắng)  — không lô nào dính điều kiện
     * </pre>
     * Khác {@link #expiryBadge} (chỉ xét hết hạn): trường này gộp cả "mới nhập"
     * và tách riêng mức "đã/sắp hết hạn gắt" để tô màu theo yêu cầu quản lý kho.
     */
    private String freshnessBadge;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LotDto {
        private LocalDate expiryDate;   // null = không có HSD
        private BigDecimal quantity;    // số lượng còn lại của lô
        private BigDecimal costPrice;   // giá vốn đơn vị (null = không theo dõi)
        private BigDecimal totalCost;   // quantity * costPrice
        private Long importedAt;        // thời điểm nhập lô (createdAt) — để biết "mới nhập"
    }
}