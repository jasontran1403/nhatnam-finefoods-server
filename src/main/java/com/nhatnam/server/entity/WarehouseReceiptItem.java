package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Dòng chi tiết nguyên liệu trong phiếu kho.
 *
 * THAY ĐỔI: bỏ @ManyToOne ingredient → dùng ingredient_id plain column + snapshot fields.
 * Các snapshot (ingredientNameSnapshot, ingredientUnitSnapshot) được fill khi tạo record.
 * Khi ingredient bị xóa, phiếu kho vẫn đọc được từ snapshot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "warehouse_receipt_item")
public class WarehouseReceiptItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private WarehouseReceipt receipt;

    // ── Không dùng @ManyToOne nữa — chỉ lưu id plain để tham chiếu ──
    // nullable = true: khi ingredient bị hard-delete thì set null (legacy), còn snapshot vẫn còn
    @Column(name = "ingredient_id", nullable = true)
    private Long ingredientId;

    // ── Snapshot — fill khi tạo, đọc khi ingredient đã bị xóa ──────
    @Column(name = "ingredient_name_snapshot", length = 255)
    private String ingredientNameSnapshot;

    @Column(name = "ingredient_unit_snapshot", length = 50)
    private String ingredientUnitSnapshot;

    @Column(name = "ingredient_image_url_snapshot", length = 500)
    private String ingredientImageUrlSnapshot;

    /** Số lượng thay đổi: (+) nhập, (-) xuất, delta khi ADJUST */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Tồn kho trước khi thao tác (snapshot) */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityBefore;

    /** Tồn kho sau khi thao tác (snapshot) */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityAfter;

    /** Chênh lệch = quantityAfter - quantityBefore */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal difference;

    /** Số lượng thực tế user kiểm kê (ADJUST) */
    @Column(precision = 12, scale = 3)
    private BigDecimal physicalQty;

    /** SURPLUS (thừa) | SHORTAGE (thiếu) | MATCH (khớp) - dùng cho ADJUST */
    @Enumerated(EnumType.STRING)
    private AdjustResult adjustResult;

    /** Hạn sử dụng – dùng cho IMPORT */
    private LocalDate expiryDate;

    private String note;

    /**
     * ĐƠN GIÁ NHẬP của 1 đơn vị (CHƯA gồm thuế/phí phân bổ) — kế toán trưởng nhập.
     * Cho phép 3 chữ số thập phân.
     */
    @Column(name = "unit_price", precision = 18, scale = 3)
    private BigDecimal unitPrice;

    /**
     * Tổng thuế/phí được PHÂN BỔ cho dòng này (theo tỷ trọng giá trị dòng trên tổng phiếu).
     * Giữ nguyên phần thập phân — không làm tròn ở bước này.
     */
    @Column(name = "allocated_fee", precision = 18, scale = 3)
    private BigDecimal allocatedFee;

    /**
     * GIÁ VỐN CUỐI CÙNG của 1 đơn vị = unitPrice + allocatedFee / quantity,
     * làm tròn tới hàng ĐƠN VỊ ĐỒNG (HALF_UP) — chỉ làm tròn ở bước cuối này.
     */
    @Column(name = "cost_price", precision = 18, scale = 3)
    private BigDecimal costPrice;

    /**
     * Lô HSD (IngredientExpiry) được tạo ngay lúc NHẬP KHO cho dòng này.
     *
     * <p>Flow mới: nhập kho → cộng tồn + tạo lô ngay (costPrice = 0). Khi kế toán trưởng
     * nhập giá vốn thật → chỉ CẬP NHẬT LẠI costPrice của đúng lô này, không cộng tồn nữa.
     * Null với các phiếu cũ (tạo trước khi đổi flow) → fallback về hành vi cũ.
     */
    @Column(name = "ingredient_expiry_id")
    private Long ingredientExpiryId;

    public enum AdjustResult {
        SURPLUS, SHORTAGE, MATCH
    }

    // ── Convenience: lấy tên từ snapshot (luôn có) ──────────────────
    public String resolvedIngredientName() {
        return ingredientNameSnapshot != null ? ingredientNameSnapshot : "N/A";
    }

    public String resolvedIngredientUnit() {
        return ingredientUnitSnapshot != null ? ingredientUnitSnapshot : "";
    }

    public String resolvedIngredientImageUrl() {
        return ingredientImageUrlSnapshot;
    }
}
