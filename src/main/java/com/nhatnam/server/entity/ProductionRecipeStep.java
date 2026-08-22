package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Một bước xử lý trong biến thể sản xuất (ProductionRecipe).
 * Tên bước lấy từ BatchStepTemplate (preset chung), nhưng mỗi biến thể
 * có thể tuỳ chỉnh: có kiểm soát (QC) hay không, thời gian hoàn thành dự kiến,
 * và máy móc sẽ sử dụng cho bước đó.
 *
 * Ví dụ (biến thể "Xúc xích 30kg"):
 *   1. Rửa thịt       — không KS — 25 phút
 *   2. Xay thịt       — có KS    — 15 phút — máy: Máy xay thịt
 *   3. Nhồi ruột      — không KS — 120 phút — máy: Máy nhồi
 *   4. Luộc xúc xích  — có KS    — 60 phút — máy: Lò luộc
 *   5. Đóng gói       — có KS    — 180 phút — máy: Máy in tem + băng chuyền
 */
@Entity
@Table(name = "production_recipe_step")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"recipe", "machine"})
@EqualsAndHashCode(of = "id")
public class ProductionRecipeStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private ProductionRecipe recipe;

    /** Mẫu bước được chọn — chỉ để tham chiếu, tên luôn snapshot vào stepName */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_template_id")
    private BatchStepTemplate stepTemplate;

    /** Tên bước — snapshot từ BatchStepTemplate.name lúc chọn, không đổi nếu template đổi sau này */
    @Column(name = "step_name", nullable = false, length = 200)
    private String stepName;

    /** Thứ tự thực hiện (1, 2, 3...) */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * Có yêu cầu kiểm soát (QC) không — tuỳ biến thể, mặc định lấy theo template lúc chọn.
     * @deprecated giữ lại để tương thích dữ liệu cũ — dùng {@link #controlType} thay thế.
     * true tương đương controlType = PHOTO_WEIGHT (kiểm soát hình ảnh cân ký, hành vi cũ).
     */
    @Column(name = "requires_qc", nullable = false)
    @Builder.Default
    private boolean requiresQc = false;

    /**
     * Loại kiểm soát của bước này:
     *  - NONE: không kiểm soát
     *  - VISUAL: kiểm soát trực quan — nhân viên kiểm tra bằng mắt rồi xác nhận (không cần ảnh)
     *  - PHOTO_WEIGHT: kiểm soát hình ảnh cân ký — bắt buộc chụp ảnh lúc cân ký khi xác nhận
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "control_type", length = 20)
    @Builder.Default
    private ControlType controlType = ControlType.NONE;

    public enum ControlType { NONE, VISUAL, PHOTO_WEIGHT }

    /**
     * Bước LÀM CHUNG cho cả lệnh sản xuất hay không.
     *  - false (mặc định): bước RIÊNG — thực hiện lặp lại 1 lần / mẻ (hành vi cũ).
     *  - true: bước CHUNG — gom sản lượng cả lệnh xử lý chung, số lần chạy = ceil(tổng / capacityPerRun).
     * Ví dụ: rửa/luộc/xông khói có thể làm chung cả lệnh thay vì chia theo từng mẻ.
     */
    @Column(name = "shared", nullable = false)
    @Builder.Default
    private boolean shared = false;

    /**
     * Công suất tối đa xử lý MỖI LẦN của bước chung (theo đơn vị đầu ra, VD kg).
     * Chỉ có ý nghĩa khi shared = true. Null hoặc ≤ 0 nghĩa là không giới hạn
     * → làm chung toàn bộ lệnh trong 1 lần.
     * Ví dụ: tủ xông khói 100kg/lần → lệnh 120kg tách thành 2 lần (100 + 20).
     */
    @Column(name = "capacity_per_run", precision = 10, scale = 3)
    private BigDecimal capacityPerRun;

    /** Thời gian dự kiến để hoàn thành bước này (phút) — dùng để đo lường & lên lịch máy */
    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    /** Máy móc sẽ dùng cho bước này (optional) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id")
    private Machine machine;

    /** Snapshot tên máy — để hiển thị nhanh không cần join, và giữ lại nếu máy bị xoá */
    @Column(name = "machine_name", length = 200)
    private String machineName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
