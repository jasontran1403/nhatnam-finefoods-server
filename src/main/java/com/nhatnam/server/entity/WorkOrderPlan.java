package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Phương án sản xuất — Factory Worker lập sau khi nhận lệnh.
 * Từ bản cập nhật biến thể sản xuất: Factory Worker chỉ cần CHỌN 1 BIẾN THỂ
 * (ProductionRecipe, đúng FactoryProduct của WorkOrder) và NHẬP SẢN LƯỢNG
 * CẦN SẢN XUẤT (requestedQty). Số mẻ, sản lượng mỗi mẻ, nguyên liệu mỗi mẻ
 * và tổng nguyên liệu cho cả lệnh được TÍNH TỰ ĐỘNG (xem
 * ProductionBatchPlanningService) — không còn nhập tay nguyên liệu/bước.
 *
 * 1-1 với WorkOrder.
 */
@Entity
@Table(name = "work_order_plan")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkOrderPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false, unique = true)
    private WorkOrder workOrder;

    /** Biến thể sản xuất đã chọn cho lệnh này — dùng CHUNG cho mọi mẻ trong lệnh */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private ProductionRecipe recipe;

    /** Sản lượng cần sản xuất do nhân viên nhập — dùng để tính số mẻ + nguyên liệu */
    @Column(name = "requested_qty", nullable = false, precision = 12, scale = 3)
    private java.math.BigDecimal requestedQty;

    /** Số mẻ được tính = ceil(requestedQty / recipe.standardOutputQty), tối thiểu 1 */
    @Column(name = "total_batches", nullable = false)
    private Integer totalBatches;

    /** Sản lượng thành phẩm chuẩn của biến thể (kg/mẻ) — snapshot lúc lập phương án */
    @Column(name = "batch_qty_per_run", nullable = false, precision = 10, scale = 2)
    private java.math.BigDecimal batchQtyPerRun;

    /** JSON array sản lượng từng mẻ đã tính: [30.0, 15.0] (mẻ cuối có thể nhỏ hơn chuẩn) */
    @Column(name = "batch_qty_per_run_list", columnDefinition = "TEXT")
    private String batchQtyPerRunList;

    /** Nhân sự (JSON array: [{name, role, shift}]) — vẫn nhập tay, không liên quan biến thể */
    @Column(name = "planned_staff", columnDefinition = "TEXT")
    private String plannedStaff;

    /** Các bước chung cho mỗi mẻ — snapshot tên bước từ recipe lúc lập phương án (JSON array tên) */
    @Column(name = "batch_steps", columnDefinition = "TEXT")
    private String batchSteps;

    /** JSON array chi tiết bước snapshot từ recipe: [{name,requiresQC,machineId,durationMinutes}] */
    @Column(name = "batch_step_details", columnDefinition = "TEXT")
    private String batchStepDetails;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by_id", nullable = false)
    private User submittedBy;

    @Column(name = "submitted_by_name", length = 200)
    private String submittedByName;

    @Column(name = "submitted_at", nullable = false)
    private Long submittedAt;

    /** Tổng nguyên liệu CHO CẢ LỆNH (= tổng nguyên liệu đã tính + làm tròn riêng của tất cả các mẻ) — dùng để trừ kho FIFO khi startWorkOrder */
    @Builder.Default
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<WorkOrderPlanMaterial> materials = new ArrayList<>();

    /** Chi tiết nguyên liệu riêng của từng mẻ — phục vụ hiển thị "nguyên liệu riêng mỗi mẻ" ở UI */
    @Builder.Default
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("batchNumber ASC, sortOrder ASC")
    private List<WorkOrderPlanBatchMaterial> batchMaterials = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
