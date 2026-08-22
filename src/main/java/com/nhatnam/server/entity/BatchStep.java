package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Bước xác nhận trong mẻ sản xuất.
 * Factory Worker BẮT ĐẦU bước (startedAt) rồi HOÀN THÀNH bước (completedAt) + upload ảnh chứng từ.
 * Các bước chạy tuần tự: chỉ được bắt đầu khi bước liền trước (stepSequence - 1) đã COMPLETED.
 * VD: Bước 1 Xay thịt → ảnh cân ký nguyên liệu
 *     Bước 2 Trộn gia vị → ảnh thành phẩm bán thành phẩm
 *     Bước 3 Nhồi → ảnh xúc xích đã nhồi
 */
@Entity
@Table(name = "batch_step")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"batch", "completedBy", "startedBy"})
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

    /** Tên bước (snapshot từ ProductionRecipeStep lúc tạo mẻ) */
    @Column(name = "step_name", nullable = false, length = 200)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private StepStatus status = StepStatus.PENDING;

    /** Người bắt đầu bước */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by_id")
    private User startedBy;

    @Column(name = "started_by_name", length = 200)
    private String startedByName;

    /** Thời điểm bắt đầu bước — máy gắn với bước này coi như busy từ mốc này */
    @Column(name = "started_at")
    private Long startedAt;

    /** Ảnh xác nhận bước này (JSON array URLs) — có thể nhiều ảnh */
    @Column(name = "attachments", columnDefinition = "TEXT")
    @Builder.Default
    private String attachments = "[]";

    /** Ghi chú khi xác nhận */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Người xác nhận hoàn thành */
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

    /**
     * Bước có yêu cầu kiểm soát (bắt buộc chụp ảnh) không.
     * @deprecated giữ lại để tương thích dữ liệu cũ — dùng {@link #controlType} thay thế.
     */
    @Column(name = "requires_qc")
    @Builder.Default
    private boolean requiresQc = false;

    /**
     * Loại kiểm soát của bước này (snapshot từ ProductionRecipeStep lúc lập phương án):
     *  - NONE: không kiểm soát — xác nhận tự do, không cần ảnh
     *  - VISUAL: kiểm soát trực quan — nhân viên kiểm tra bằng mắt rồi xác nhận, không cần ảnh
     *  - PHOTO_WEIGHT: kiểm soát hình ảnh cân ký — bắt buộc chụp ảnh lúc cân ký khi xác nhận
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "control_type", length = 20)
    @Builder.Default
    private ProductionRecipeStep.ControlType controlType = ProductionRecipeStep.ControlType.NONE;

    /** Thời gian dự kiến hoàn thành bước (phút) — snapshot từ ProductionRecipeStep, dùng để đo lường thực tế vs dự kiến */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist void onCreate() { createdAt = System.currentTimeMillis(); }

    public enum StepStatus { PENDING, IN_PROGRESS, COMPLETED }
}
