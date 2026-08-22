package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "production_batch")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"workOrder", "recipe", "createdBy", "items", "steps", "cancellation"})
@EqualsAndHashCode(of = "id")
public class ProductionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_code", nullable = false, unique = true)
    private String batchCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id")
    private WorkOrder workOrder;

    @Column(name = "batch_number")
    private Integer batchNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private ProductionRecipe recipe;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "recipe_name", nullable = false, length = 200)
    private String recipeName;

    @Builder.Default
    @Column(name = "actual_output_qty", precision = 10, scale = 3, nullable = false)
    private BigDecimal actualOutputQty = java.math.BigDecimal.ZERO;

    /** Sản lượng lỗi/huỷ do không đạt chất lượng (kg) — vào Kho Scrap khi hoàn thành mẻ */
    @Column(name = "scrap_qty", precision = 10, scale = 3)
    private BigDecimal scrapQty;

    /** Lý do lỗi — bắt buộc nhập nếu scrapQty > 0 */
    @Column(name = "scrap_reason", columnDefinition = "TEXT")
    private String scrapReason;

    @Column(name = "output_unit", nullable = false, length = 50)
    private String outputUnit;

    @Builder.Default
    @Column(name = "produced_at", nullable = false)
    private Long producedAt = System.currentTimeMillis();

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BatchStatus status = BatchStatus.IN_PROGRESS;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", nullable = false, length = 200)
    private String createdByName;

    @Builder.Default
    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductionBatchItem> items = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BatchStep> steps = new ArrayList<>();

    @OneToOne(mappedBy = "batch", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private BatchCancellation cancellation;

    /**
     * Giá vốn NGUYÊN LIỆU của mẻ này (đồng) — phần chi phí nguyên liệu của lệnh
     * được phân bổ cho mẻ theo định lượng trong phương án.
     * Không gồm nhân công / điện / khấu hao máy.
     */
    @Column(name = "material_cost", precision = 15, scale = 2)
    private BigDecimal materialCost;

    /**
     * Giá vốn 1 kg BÁN THÀNH PHẨM của mẻ = {@link #materialCost} / {@code actualOutputQty}.
     * Giữ 6 chữ số thập phân, KHÔNG làm tròn — chỉ làm tròn ở bước cuối khi ghi
     * vào lô kho thành phẩm, để không bị lệch số khi cộng dồn nhiều mẻ.
     * Sản lượng lỗi (scrap) không gánh giá vốn: toàn bộ chi phí dồn vào sản lượng đạt.
     */
    @Column(name = "unit_cost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum BatchStatus {
        IN_PROGRESS,
        COMPLETED,
        CANCELLED,
        SUBMITTED,
        REVIEWED
    }
}