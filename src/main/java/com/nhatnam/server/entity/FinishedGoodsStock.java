package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Kho thành phẩm của xưởng — cơ chế RIÊNG, độc lập với Warehouse (kho bán hàng)
 * và FactoryMaterialStock (kho nguyên liệu xưởng).
 *
 * Mỗi lần hoàn thành 1 mẻ sản xuất (completeBatch) → tạo 1 lô mới ở đây, theo
 * sản lượng thực tế + ngày sản xuất + hạn sử dụng do Factory Worker nhập tay.
 *
 * Xuất kho (export) hoặc chuyển kho (transfer) sẽ trừ dần theo FIFO (ưu tiên lô
 * gần hết hạn nhất), tương tự cơ chế FactoryMaterialStock.
 */
@Entity
@Table(name = "finished_goods_stock")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FinishedGoodsStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Thành phẩm (FactoryProduct) — plain FK + snapshot tên để vẫn đọc được nếu sản phẩm bị xoá */
    @Column(name = "factory_product_id")
    private Long factoryProductId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(nullable = false, length = 50)
    private String unit;

    /** Số lượng còn lại của lô này */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Số lượng ban đầu khi nhập (để trace) */
    @Column(name = "initial_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal initialQuantity;

    /** Ngày sản xuất — bắt buộc nhập khi hoàn thành mẻ */
    @Column(name = "manufacture_date")
    private Long manufactureDate;

    /** Hạn sử dụng — bắt buộc nhập khi hoàn thành mẻ */
    @Column(name = "expiry_date")
    private Long expiryDate;

    // ── GIÁ VỐN — chốt tại đây, đây là mốc "không quay đầu" ────────────────────
    // Kho thành phẩm là nơi ĐẦU TIÊN biết số lượng cuối cùng dùng để bán/chuyển kho
    // (sau hao hụt đóng gói + cấp đông), nên giá vốn chỉ được chốt ở bước này.

    /** Tổng giá vốn nguyên liệu của cả lô (đồng). */
    @Column(name = "total_cost", precision = 15, scale = 2)
    private BigDecimal totalCost;

    /**
     * Giá vốn 1 đơn vị của lô theo {@link #unit} (túi/hộp...) = totalCost / quantity,
     * LÀM TRÒN LÊN tới đồng. Đây là giá vốn mang theo khi xuất bán / chuyển kho.
     */
    @Column(name = "unit_cost", precision = 15, scale = 2)
    private BigDecimal unitCost;

    /**
     * Giá vốn 1 kg = totalCost / {@link #netWeightKg}, LÀM TRÒN LÊN tới đồng.
     * VD: 5.868.000đ / 57,85kg = 101.434,745… → 101.435 đ/kg.
     */
    @Column(name = "unit_cost_per_kg", precision = 15, scale = 2)
    private BigDecimal unitCostPerKg;

    /** Trọng lượng thực cân khi kho thành phẩm nhận (kg) — mẫu số của giá vốn/kg. */
    @Column(name = "net_weight_kg", precision = 12, scale = 3)
    private BigDecimal netWeightKg;

    /** Mẻ sản xuất nguồn gốc (để trace ngược) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private ProductionBatch batch;

    @Column(name = "batch_code_snapshot", length = 100)
    private String batchCodeSnapshot;

    /** Xưởng sản xuất ra lô này (nullable) */
    @Column(name = "factory_id")
    private Long factoryId;

    @Column(name = "factory_name_snapshot", length = 200)
    private String factoryNameSnapshot;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}