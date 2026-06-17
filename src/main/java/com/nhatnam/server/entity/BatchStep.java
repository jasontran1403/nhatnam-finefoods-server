package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Bước xác nhận trong mẻ sản xuất.
 * Factory Worker xác nhận từng bước + upload ảnh chứng từ.
 * VD: Bước 1 Xay thịt → ảnh cân ký nguyên liệu
 *     Bước 2 Trộn gia vị → ảnh thành phẩm bán thành phẩm
 *     Bước 3 Nhồi → ảnh xúc xích đã nhồi
 */
@Entity
@Table(name = "batch_step")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"batch", "completedBy"})
@EqualsAndHashCode(of = "id")
public class BatchStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private ProductionBatch batch;

    /** Số thứ tự bước (1, 2, 3...) */
    @Column(name = "step_sequence", nullable = false)
    private Integer stepSequence;

    /** Tên bước (lấy từ WorkOrderPlan.batchSteps) */
    @Column(name = "step_name", nullable = false, length = 200)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private StepStatus status = StepStatus.PENDING;

    /** Ảnh xác nhận bước này (JSON array URLs) — có thể nhiều ảnh */
    @Column(name = "attachments", columnDefinition = "TEXT")
    @Builder.Default
    private String attachments = "[]";

    /** Ghi chú khi xác nhận */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Người xác nhận */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by_id")
    private User completedBy;

    @Column(name = "completed_by_name", length = 200)
    private String completedByName;

    @Column(name = "completed_at")
    private Long completedAt;

    /** Máy sử dụng cho bước này */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id")
    private Machine machine;

    @Column(name = "machine_name", length = 200)
    private String machineName;

    /** Bước có yêu cầu kiểm soát (bắt buộc chụp ảnh) không */
    @Column(name = "requires_qc")
    @Builder.Default
    private boolean requiresQc = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist void onCreate() { createdAt = System.currentTimeMillis(); }

    public enum StepStatus { PENDING, COMPLETED }
}
