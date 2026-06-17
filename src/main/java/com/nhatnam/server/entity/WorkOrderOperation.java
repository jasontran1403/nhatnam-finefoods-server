package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Bước thao tác trong lệnh sản xuất.
 * VD: Bước 1 Xay thịt, Bước 2 Trộn gia vị, Bước 3 Nhồi, ...
 */
@Entity
@Table(name = "work_order_operation")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkOrderOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;

    @Column(name = "operation_sequence", nullable = false)
    private Integer operationSequence;

    @Column(name = "operation_name", nullable = false, length = 200)
    private String operationName;

    @Column(name = "operation_description", columnDefinition = "TEXT")
    private String operationDescription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id")
    private Machine machine;

    @Column(name = "machine_name", length = 200)
    private String machineName;

    @Column(name = "planned_hours", precision = 8, scale = 2)
    private BigDecimal plannedHours;

    @Column(name = "actual_hours", precision = 8, scale = 2)
    private BigDecimal actualHours;

    @Column(name = "planned_qty", precision = 12, scale = 2)
    private BigDecimal plannedQty;

    @Column(name = "actual_qty", precision = 12, scale = 2)
    private BigDecimal actualQty;

    @Column(name = "scrap_qty", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal scrapQty = BigDecimal.ZERO;

    /** Có cần kiểm tra QC tại bước này không */
    @Column(name = "qc_required")
    @Builder.Default
    private Boolean qcRequired = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "qc_type")
    private QcType qcType;

    @Column(name = "qc_control_point", length = 100)
    private String qcControlPoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "qc_status")
    private QcStatus qcStatus;

    @Column(name = "qc_notes", columnDefinition = "TEXT")
    private String qcNotes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OperationStatus status = OperationStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum QcType { VISUAL, MEASUREMENT, SAMPLING, OTHER }
    public enum QcStatus { PASS, FAIL, NEEDS_REVIEW, PENDING }
    public enum OperationStatus { PENDING, IN_PROGRESS, COMPLETED, SKIPPED }
}
