package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Một LẦN CHẠY của một CÔNG ĐOẠN (stage) trong lệnh sản xuất — cấp LỆNH, không cấp mẻ.
 *
 * Thay cho mô hình cũ "mỗi mẻ lặp lại toàn bộ bước" (BatchStep), việc thực thi giờ
 * được tổ chức theo công đoạn của cả lệnh:
 *
 *  - Bước RIÊNG (shared = false): số lần chạy = số mẻ, mỗi lần gắn với 1 mẻ (batchNumber),
 *    khối lượng = sản lượng mẻ đó. VD: xay/nhồi/đóng gói — làm theo mẻ.
 *  - Bước CHUNG (shared = true): gom sản lượng cả lệnh, số lần chạy = ceil(tổng / công suất),
 *    mỗi lần phủ 1 phần khối lượng (batchNumber = null). VD: rửa 300kg 1 lần, hoặc
 *    tủ xông khói 100kg → lệnh 120kg tách 2 lần (100 + 20).
 *
 * Các công đoạn chạy TUẦN TỰ theo stageSequence. Bước chung là điểm ĐỒNG BỘ (barrier):
 * chỉ bắt đầu được khi toàn bộ công đoạn trước đã hoàn tất (đủ khối lượng để gom).
 */
@Entity
@Table(name = "work_order_step")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"workOrder", "machine", "startedBy", "completedBy"})
@EqualsAndHashCode(of = "id")
public class WorkOrderStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;

    /** Thứ tự công đoạn (1, 2, 3...) — theo sortOrder của bước trong biến thể */
    @Column(name = "stage_sequence", nullable = false)
    private Integer stageSequence;

    /** Tên công đoạn (snapshot từ phương án lúc bắt đầu lệnh) */
    @Column(name = "stage_name", nullable = false, length = 200)
    private String stageName;

    /** Công đoạn làm chung cả lệnh (true) hay riêng từng mẻ (false) */
    @Column(name = "shared", nullable = false)
    @Builder.Default
    private boolean shared = false;

    /** Số thứ tự lần chạy trong công đoạn này (1..totalRuns) */
    @Column(name = "run_number", nullable = false)
    private Integer runNumber;

    /** Tổng số lần chạy của công đoạn này */
    @Column(name = "total_runs", nullable = false)
    private Integer totalRuns;

    /** Khối lượng lần chạy này phủ (kg) — bước riêng = sản lượng mẻ; bước chung = phần khối lượng của lần này */
    @Column(name = "run_qty", precision = 10, scale = 3)
    private BigDecimal runQty;

    /** Mẻ tương ứng (chỉ có với bước RIÊNG). Bước chung = null. */
    @Column(name = "batch_number")
    private Integer batchNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "requires_qc")
    @Builder.Default
    private boolean requiresQc = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_type", length = 20)
    @Builder.Default
    private ProductionRecipeStep.ControlType controlType = ProductionRecipeStep.ControlType.NONE;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id")
    private Machine machine;

    @Column(name = "machine_name", length = 200)
    private String machineName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by_id")
    private User startedBy;

    @Column(name = "started_by_name", length = 200)
    private String startedByName;

    @Column(name = "started_at")
    private Long startedAt;

    @Column(name = "attachments", columnDefinition = "TEXT")
    @Builder.Default
    private String attachments = "[]";

    /**
     * SỐ HƯ HỎNG ghi nhận tại công đoạn này — chỉ áp dụng cho bước CÓ KIỂM SOÁT
     * (controlType = VISUAL hoặc PHOTO_WEIGHT). Đơn vị = đơn vị sản lượng của sản
     * phẩm đang sản xuất (WorkOrder.outputUnit, thường là kg).
     *
     * <p>Chỉ để GHI NHẬN/thống kê — KHÔNG tự trừ vào sản lượng của lệnh. Sản lượng
     * đạt/lỗi cuối cùng vẫn do nhân viên nhập ở bước hoàn thành mẻ
     * (actualOutputQty / scrapQty).
     */
    @Column(name = "damaged_qty", precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal damagedQty = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by_id")
    private User completedBy;

    @Column(name = "completed_by_name", length = 200)
    private String completedByName;

    @Column(name = "completed_at")
    private Long completedAt;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist void onCreate() { createdAt = System.currentTimeMillis(); }

    public enum Status { PENDING, IN_PROGRESS, COMPLETED }
}
