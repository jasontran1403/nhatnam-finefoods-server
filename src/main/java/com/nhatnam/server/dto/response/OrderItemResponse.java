package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class OrderItemResponse {

    private Long id;

    // ── Sản phẩm (snapshot) ───────────────────────────────────────
    private Long   productId;
    private String productName;
    private String productImageUrl;
    private String unit;

    // ── Variant (snapshot, nullable) ──────────────────────────────
    private Long   variantId;
    private String variantName;

    // ── Quy cách bán ──────────────────────────────────────────────
    /** "BOX" = bán thùng, "RETAIL" = bán lẻ */
    private String saleType;
    /** Số đơn vị / thùng tại thời điểm đặt (snapshot). null nếu bán lẻ. */
    private Integer unitsPerBox;

    // ── Giá (snapshot) ────────────────────────────────────────────
    /** Giá gốc tại thời điểm tạo đơn */
    private BigDecimal basePrice;

    /** Giá tier gốc trước CK món — để hiển thị đúng trên UI */
    private BigDecimal originalUnitPrice;

    /** Giá thực tế áp dụng (sau tier / discount) */
    private BigDecimal unitPrice;

    /** BASE | TIER | DISCOUNT_PERCENT */
    private String priceMode;

    /** INCLUSIVE | EXCLUSIVE */
    private String vatMode;

    /** ID khung giá đã áp dụng (null nếu không phải TIER) */
    private Long   tierId;

    /** Tên khung giá (snapshot, null nếu không phải TIER) */
    private String tierName;

    /** % giảm đã áp dụng (null nếu không phải DISCOUNT_PERCENT) */
    private Integer discountPercent;

    // ── VAT ───────────────────────────────────────────────────────
    /** Tỷ lệ VAT (%) của sản phẩm: 0, 5, 8, 10 */
    private Integer vatRate;

    /** Số tiền VAT của item = subtotal * vatRate / 100 */
    private BigDecimal vatAmount;

    // ── Số lượng & thành tiền ─────────────────────────────────────
    private BigDecimal quantity;

    /** subtotal = unitPrice × quantity (chưa VAT) */
    private BigDecimal subtotal;

    private String notes;

    // ── Nguyên liệu đã dùng ───────────────────────────────────────
    private List<IngredientUsed> ingredientsUsed;

    @Data
    @Builder
    public static class IngredientUsed {
        private Long       ingredientId;
        private String     ingredientName;
        private String     ingredientImageUrl;
        private BigDecimal quantityUsed;
        private String     unit;
    }
}