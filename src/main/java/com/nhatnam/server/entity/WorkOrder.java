package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Lệnh sản xuất — Owner tạo, Factory Worker thực hiện.
 * 1 lệnh = 1 sản phẩm, nhiều mẻ tích lũy đến đủ plannedQty.
 *
 * Flow:
 *   SCHEDULED (hẹn giờ) → PENDING_PLAN (đến ngày, chờ factory lập phương án)
 *   → PLANNED (có phương án) → IN_PROGRESS (đang sản xuất)
 *   → COMPLETED (đủ sản lượng) → CANCELLED
 */
@Entity
@Table(name = "work_order")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã lệnh: WO-YYYYMMDD-XXXX */
    @Column(name = "work_order_code", nullable = false, unique = true, length = 50)
    private String workOrderCode;

    /** Kế hoạch sản xuất cha */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_plan_id")
    private ProductionPlan productionPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_product_id", nullable = false)
    private FactoryProduct factoryProduct;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** Ngày được phép bắt đầu sản xuất (hẹn giờ) */
    @Column(name = "scheduled_start_date")
    private Long scheduledStartDate;

    /** Deadline lập phương án = scheduledStartDate + 1 ngày */
    /** Lý do gia hạn deadline (owner cập nhật) */
    @Column(name = "extend_reason", columnDefinition = "TEXT")
    private String extendReason;

    @Column(name = "plan_deadline")
    private Long planDeadline;

    /** Ngày kết thúc dự kiến */
    @Column(name = "planned_end_date")
    private Long plannedEndDate;

    /** Ngày bắt đầu thực tế */
    @Column(name = "actual_start_date")
    private Long actualStartDate;

    /** Ngày kết thúc thực tế */
    @Column(name = "actual_end_date")
    private Long actualEndDate;

    /** Sản lượng mục tiêu của lệnh */
    @Column(name = "planned_qty", nullable = false, precision = 12, scale = 2)
    private BigDecimal plannedQty;

    /** Sản lượng đã tích lũy từ các mẻ COMPLETED (tính lại khi mẻ hoàn thành) */
    @Column(name = "accumulated_qty", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal accumulatedQty = BigDecimal.ZERO;

    @Column(name = "output_unit", length = 50)
    private String outputUnit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private WorkOrderStatus status = WorkOrderStatus.SCHEDULED;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Xưởng sản xuất được giao thực hiện lệnh này */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_factory_id")
    private ProductionFactory productionFactory;

    @Column(name = "production_factory_name", length = 200)
    private String productionFactoryName;

    /**
     * Chế độ hẹn giờ chặt: nếu true, factory chỉ được lập phương án + tạo bước mẻ,
     * KHÔNG được nhập nguyên liệu cho đến 3 ngày trước scheduledStartDate.
     */
    @Column(name = "scheduled_mode", nullable = false)
    @Builder.Default
    private Boolean scheduledMode = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    /** Phương án sản xuất do factory lập (1-1) */
    @OneToOne(mappedBy = "workOrder", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private WorkOrderPlan workOrderPlan;

    /** Các mẻ sản xuất thuộc lệnh này */
    @Builder.Default
    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductionBatch> batches = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum WorkOrderStatus {
        SCHEDULED,      // Hẹn giờ, chưa đến ngày bắt đầu
        PENDING_PLAN,   // Đến ngày, chờ factory lập phương án (deadline 1 ngày)
        PLANNED,        // Đã có phương án, chờ bắt đầu
        IN_PROGRESS,    // Đang sản xuất
        COMPLETED,      // Đủ sản lượng
        CANCELLED       // Đã huỷ
    }
}