package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * YÊU CẦU ĐỊNH GIÁ LÔ — sinh ra khi nhân viên kho TẠO LÔ MỚI trong phiếu
 * điều chỉnh tồn kho ({@code WarehouseService.adjustStock}).
 *
 * <p>Lô được tạo ngay lập tức với giá vốn mặc định = 1 (tồn kho cập nhật liền),
 * bản ghi này chỉ để KẾ TOÁN TRƯỞNG (SUPER_ACCOUNTANT) vào nhập giá vốn thật sau.
 *
 * <p>Snapshot tên kho / tên nguyên liệu / người thao tác được lưu thẳng vào đây
 * để card hiển thị luôn đọc được kể cả khi nguyên liệu bị soft-delete.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "lot_pricing_request",
        indexes = {
                @Index(columnList = "status"),
                @Index(columnList = "ingredient_expiry_id")
        })
public class LotPricingRequest {

    public enum Status { PENDING, PRICED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lô kho tương ứng (IngredientExpiry.id) — nơi giá vốn sẽ được ghi vào. */
    @Column(name = "ingredient_expiry_id", nullable = false)
    private Long ingredientExpiryId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "warehouse_name", length = 255)
    private String warehouseName;

    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    @Column(name = "ingredient_name", length = 255)
    private String ingredientName;

    @Column(name = "ingredient_unit", length = 50)
    private String ingredientUnit;

    /** Số lượng của lô tại thời điểm tạo yêu cầu (dùng để chia giá tổng). */
    @Column(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** Mã phiếu điều chỉnh đã sinh ra lô này (để đối chiếu). */
    @Column(name = "receipt_code", length = 100)
    private String receiptCode;

    // ── Người thao tác (nhân viên kho) ──────────────────────────────
    @Column(name = "requested_by_id")
    private Long requestedById;

    @Column(name = "requested_by_name", length = 255)
    private String requestedByName;

    // ── Kết quả định giá ────────────────────────────────────────────
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** Giá vốn 1 đơn vị — đã làm tròn tới hàng đơn vị đồng. */
    @Column(name = "unit_cost", precision = 15, scale = 2)
    private BigDecimal unitCost;

    /** Giá vốn cả lô = unitCost × quantity (lưu để đối chiếu). */
    @Column(name = "total_cost", precision = 15, scale = 2)
    private BigDecimal totalCost;

    @Column(name = "priced_by_id")
    private Long pricedById;

    @Column(name = "priced_by_name", length = 255)
    private String pricedByName;

    private Long createdAt;
    private Long pricedAt;
}
