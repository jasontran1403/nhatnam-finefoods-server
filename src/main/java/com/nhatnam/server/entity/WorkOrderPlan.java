package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Phương án sản xuất — Factory Worker lập trong 1 ngày sau khi nhận lệnh.
 * Bao gồm: nhân sự, nguyên liệu (+ NCC), số mẻ, kg/mẻ, các bước.
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

    /** Số mẻ dự kiến */
    @Column(name = "total_batches", nullable = false)
    private Integer totalBatches;

    /** Kg thành phẩm mỗi mẻ */
    @Column(name = "batch_qty_per_run", nullable = false, precision = 10, scale = 2)
    private java.math.BigDecimal batchQtyPerRun;

    /** JSON array sản lượng mỗi mẻ: [30.0, 20.0] — nếu null dùng batchQtyPerRun cho tất cả */
    @Column(name = "batch_qty_per_run_list", columnDefinition = "TEXT")
    private String batchQtyPerRunList;

    /** Nhân sự (JSON array: [{name, role, shift}]) */
    @Column(name = "planned_staff", columnDefinition = "TEXT")
    private String plannedStaff;

    /** Các bước chung cho mỗi mẻ (JSON array: ["Xay thịt","Trộn gia vị","Nhồi","Hấp","Đóng gói"]) */
    @Column(name = "batch_steps", columnDefinition = "TEXT")
    private String batchSteps;

    /** JSON array chi tiết bước: [{name, requiresQC}] */
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

    /** Danh sách nguyên liệu trong phương án */
    @Builder.Default
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<WorkOrderPlanMaterial> materials = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
