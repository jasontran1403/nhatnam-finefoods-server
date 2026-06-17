package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Lịch bảo trì/sửa chữa máy móc.
 * Có 2 loại:
 *   - PREVENTIVE: định kỳ, lặp lại (WEEKLY/MONTHLY/QUARTERLY)
 *   - CORRECTIVE: sự cố phát sinh, xử lý 1 lần
 *
 * Factory Worker tạo, WS notify Owner + Super Accountant.
 */
@Entity
@Table(name = "maintenance_schedule")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaintenanceSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id", nullable = false)
    private Machine machine;

    @Column(name = "machine_name", length = 200)
    private String machineName;

    @Enumerated(EnumType.STRING)
    @Column(name = "maintenance_type", nullable = false)
    private MaintenanceType maintenanceType;

    /** Chỉ dùng cho PREVENTIVE: tần suất lặp lại */
    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_type")
    private RecurrenceType recurrenceType;

    /** Tiêu đề / lý do (bắt buộc cho CORRECTIVE) */
    @Column(nullable = false, length = 500)
    private String title;

    /** Mô tả chi tiết */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Ngày bắt đầu theo kế hoạch (epoch ms) */
    @Column(name = "planned_start", nullable = false)
    private Long plannedStart;

    /** Ngày kết thúc theo kế hoạch (epoch ms) */
    @Column(name = "planned_end", nullable = false)
    private Long plannedEnd;

    /** Ngày thực tế bắt đầu */
    @Column(name = "actual_start")
    private Long actualStart;

    /** Ngày thực tế kết thúc */
    @Column(name = "actual_end")
    private Long actualEnd;

    /** Giờ downtime theo kế hoạch */
    @Column(name = "planned_downtime_hours", precision = 8, scale = 2)
    private BigDecimal plannedDowntimeHours;

    /** Giờ downtime thực tế */
    @Column(name = "actual_downtime_hours", precision = 8, scale = 2)
    private BigDecimal actualDowntimeHours;

    /** Tên đơn vị sửa chữa / bảo trì */
    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    /** SĐT đơn vị */
    @Column(name = "vendor_phone", length = 20)
    private String vendorPhone;

    /** Chi phí ước tính */
    @Column(name = "estimated_cost", precision = 15, scale = 2)
    private BigDecimal estimatedCost;

    /** Chi phí thực tế */
    @Column(name = "actual_cost", precision = 15, scale = 2)
    private BigDecimal actualCost;

    /** Ảnh trước khi sửa (JSON array URLs) */
    @Column(name = "before_images", columnDefinition = "TEXT")
    @Builder.Default
    private String beforeImages = "[]";

    /** Ảnh sau khi sửa (JSON array URLs) */
    @Column(name = "after_images", columnDefinition = "TEXT")
    @Builder.Default
    private String afterImages = "[]";

    /** Chứng từ hóa đơn (JSON array URLs) */
    @Column(name = "receipt_images", columnDefinition = "TEXT")
    @Builder.Default
    private String receiptImages = "[]";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MaintenanceStatus status = MaintenanceStatus.PLANNED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum MaintenanceType {
        PREVENTIVE,  // Bảo trì định kỳ
        CORRECTIVE,  // Sửa chữa sự cố
        INSPECTION   // Kiểm tra
    }

    /** Ngày trong tháng lặp lại (1-28) cho MONTHLY/QUARTERLY */
    @Column(name = "recurrence_day")
    private Integer recurrenceDay;

    /** Tháng trong quý lặp lại (1-3) cho QUARTERLY */
    @Column(name = "recurrence_month_in_quarter")
    private Integer recurrenceMonthInQuarter;

    /** Ghi chú sau khi hoàn thành */
    @Column(name = "completion_notes", columnDefinition = "TEXT")
    private String completionNotes;

    public enum RecurrenceType {
        ONCE,       // 1 lần
        WEEKLY,     // Hàng tuần
        MONTHLY,    // Hàng tháng
        QUARTERLY,  // Hàng quý
        YEARLY      // Hàng năm (ngày/tháng cố định)
    }

    public enum MaintenanceStatus {
        PLANNED,    // Đã lên kế hoạch
        IN_PROGRESS,// Đang thực hiện (máy đang bảo trì — không sử dụng được)
        COMPLETED,  // Hoàn thành
        ADJUSTED,   // Điều chỉnh (lệch ngày)
        MISSED      // Bỏ lỡ
    }
}
