package com.nhatnam.server.dto.production;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * DTOs cho Production Planning Module v2.
 * Bao gồm: ProductionPlan, WorkOrder, WorkOrderPlan, Batch, BatchStep, Maintenance, Dashboard.
 */
public class ProductionModuleDtos {

    // ─── ProductionPlan ───────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductionPlanDto {
        private Long id;
        private String planCode;
        private String title;
        private Long factoryProductId;          // sản phẩm chính (backward compat)
        private String productName;             // tên sản phẩm chính
        private Long productionFactoryId;
        private String productionFactoryName;
        private String outputUnit;
        private BigDecimal targetQty;
        private Long startDate;
        private Long endDate;
        private String status;
        private String notes;
        private String createdByName;
        private Long createdAt;
        // Multi-product support
        private List<Long> factoryProductIds;   // tất cả sản phẩm trong kế hoạch
        private List<String> factoryProductNames; // tên tương ứng
        // Computed
        private BigDecimal accumulatedQty;
        private BigDecimal progressPct;
        private int totalWorkOrders;
        private int completedWorkOrders;
        private int inProgressWorkOrders;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreatePlanRequest {
        private String title;
        private Long factoryProductId;          // sản phẩm chính (backward compat)
        private List<Long> factoryProductIds;   // danh sách sản phẩm (ưu tiên nếu có)
        private Long productionFactoryId;        // xưởng xử lý kế hoạch
        private BigDecimal targetQty;
        private Long startDate;
        private Long endDate;
        private String notes;
    }

    // ─── WorkOrder ────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderDto {
        private Long id;
        private String workOrderCode;
        private Long productionPlanId;
        private String planTitle;
        private Long factoryProductId;
        private String productName;
        private String outputUnit;
        private BigDecimal plannedQty;
        private BigDecimal accumulatedQty;
        private BigDecimal progressPct;      // accumulatedQty / plannedQty * 100
        private Long scheduledStartDate;
        private Long planDeadline;
        private Long plannedEndDate;
        private Long actualStartDate;
        private Long actualEndDate;
        private String status;
        private String notes;
        private String assignedToName;
        private Long assignedToId;
        private String createdByName;
        private Long createdAt;
        // Batch summary
        private int totalBatches;
        private int completedBatches;
        private int cancelledBatches;
        private int inProgressBatches;
        // Plan summary (nếu đã có phương án)
        private boolean hasPlan;
        private Integer planTotalBatches;
        private BigDecimal planBatchQtyPerRun;
        // Xưởng sản xuất
        private Long productionFactoryId;
        private String productionFactoryName;
        // Chế độ hẹn giờ chặt
        private Boolean scheduledMode;
        private Boolean canInputMaterials;   // có thể nhập NVL không (3 ngày trước ngày SX)
        /** Hao hụt đóng gói — CHỈ có giá trị khi tất cả mẻ đã COMPLETED (xem WorkOrderLossDto) */
        private WorkOrderLossDto packagingLoss;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateWorkOrderRequest {
        private Long productionPlanId;
        private Long factoryProductId;
        private BigDecimal plannedQty;
        private Long scheduledStartDate;
        private Long plannedEndDate;
        private String notes;
        private Long assignedToId;
        /** Nếu true: bỏ qua cảnh báo vượt sản lượng kế hoạch */
        private Boolean forceCreate;
        /** Xưởng sản xuất được giao */
        private Long productionFactoryId;
        /** Chế độ hẹn giờ chặt: factory không được nhập NVL trước 3 ngày */
        private Boolean scheduledMode;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateWorkOrderStatusRequest {
        private String status;
        private String notes;
    }

    // ─── WorkOrderPlan (phương án sản xuất) ──────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PlanMaterialDto {
        private Long id;
        private String materialName;
        private BigDecimal quantity;
        private String unit;
        private String vendorName;
        private String vendorPhone;
        private BigDecimal estimatedUnitPrice;
        private BigDecimal estimatedTotal;
        private List<String> invoiceImages;
        private int sortOrder;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderPlanDto {
        private Long id;
        private Long workOrderId;
        private Long recipeId;
        private String recipeName;
        private BigDecimal requestedQty;
        private Integer totalBatches;
        private BigDecimal batchQtyPerRun;
        private List<BigDecimal> batchQtyPerRunList; // sản lượng từng mẻ, nếu null dùng batchQtyPerRun
        private List<Object> plannedStaff;   // parsed từ JSON
        private List<String> batchSteps;     // parsed từ JSON
        private String notes;
        private String submittedByName;
        private Long submittedAt;
        private List<PlanMaterialDto> materials;
        private BigDecimal totalEstimatedCost;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PlanMaterialRequest {
        private String materialName;
        private BigDecimal quantity;
        private String unit;
        private String vendorName;
        private String vendorPhone;
        private BigDecimal estimatedUnitPrice;
        private int sortOrder;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateWorkOrderPlanRequest {
        private Integer totalBatches;
        private BigDecimal batchQtyPerRun;
        private List<BigDecimal> batchQtyPerRunList; // sản lượng từng mẻ, nếu null dùng batchQtyPerRun
        private String plannedStaff;         // JSON string
        private List<String> batchSteps;     // danh sách tên bước
        private List<java.util.Map<String,Object>> batchStepDetails; // [{name,requiresQC,machineId}]
        private String notes;
        private List<PlanMaterialRequest> materials;
    }

    // ─── Lập phương án theo Biến thể sản xuất (MỚI) ───────────────────────────

    /** Request preview/submit: chỉ cần chọn biến thể + nhập sản lượng cần sản xuất */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SubmitPlanByRecipeRequest {
        private Long recipeId;
        private BigDecimal requestedQty;
        /** Nhân sự (tuỳ chọn, vẫn nhập tay) */
        private String plannedStaff;
        private String notes;
    }

    /** 1 dòng nguyên liệu trong preview/kết quả (đã làm tròn theo loại đơn vị) */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialLineDto {
        private Long factoryMaterialId;
        private String materialName;
        private String unit;
        private BigDecimal qty;
    }

    /** 1 bước trong preview — snapshot từ ProductionRecipeStep */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecipeStepPreviewDto {
        private String stepName;
        private boolean requiresQc;
        private String controlType;
        private Integer durationMinutes;
        private Long machineId;
        private String machineName;
        /** Bước làm chung cả lệnh (true) hay riêng từng mẻ (false) */
        private boolean shared;
        /** Công suất tối đa mỗi lần của bước chung (kg) — null = làm chung toàn bộ 1 lần */
        private BigDecimal capacityPerRun;
    }

    /** Kế hoạch 1 mẻ trong preview */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BatchPlanPreviewDto {
        private int batchNumber;
        private BigDecimal outputQty;
        private List<MaterialLineDto> materials;
    }

    /** Toàn bộ preview trả về cho UI trước khi submit phương án */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PlanPreviewDto {
        private Long recipeId;
        private String recipeName;
        private BigDecimal standardOutputQty;
        private String outputUnit;
        private BigDecimal requestedQty;
        private int totalBatches;
        private List<BatchPlanPreviewDto> batches;
        private List<MaterialLineDto> totalMaterials;
        private List<RecipeStepPreviewDto> steps;
    }

    // ─── ProductionBatch ──────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BatchStepDto {
        private Long id;
        private int stepSequence;
        private String stepName;
        private String status;
        private boolean requiresQc;
        /** NONE | VISUAL | PHOTO_WEIGHT */
        private String controlType;
        private Long machineId;
        private String machineName;
        private Integer durationMinutes;
        private String startedByName;
        private Long startedAt;
        private List<String> attachments;
        private String notes;
        /** Số hư hỏng ghi nhận ở bước này (chỉ bước có kiểm soát); null nếu không áp dụng. */
        private BigDecimal damagedQty;
        private String completedByName;
        private Long completedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BatchCancellationDto {
        private Long id;
        private String reason;
        private List<String> attachments;
        private String resolution;
        private String resolutionNotes;
        private String cancelledByName;
        private Long cancelledAt;
        // ── Mới: thông tin hoàn kho khi hủy ─────────────────────────────────
        /** Sản lượng thực tế đã đạt được trước khi hủy */
        private BigDecimal actualOutputQty;
        /** Chi tiết từng nguyên liệu: đã trừ bao nhiêu / đã dùng bao nhiêu / hoàn lại bao nhiêu */
        private List<StockDeductionDto> materialUsage;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductionBatchDto {
        private Long id;
        private String batchCode;
        private Long workOrderId;
        private String workOrderCode;
        private Integer batchNumber;
        private Long recipeId;
        private String productName;
        private String recipeName;
        private BigDecimal actualOutputQty;
        private BigDecimal standardOutputQty;
        private BigDecimal outputVariancePct;
        private String outputUnit;
        private Long producedAt;
        private String notes;
        private String status;
        private String createdByName;
        private Long createdAt;
        /** Sản lượng lỗi/huỷ (kg) ghi nhận khi hoàn thành mẻ — vào Kho Scrap */
        private BigDecimal scrapQty;
        private String scrapReason;

        /** Giá vốn NGUYÊN LIỆU của mẻ (đồng) — không gồm nhân công/điện/khấu hao */
        private BigDecimal materialCost;
        /** Giá vốn 1 kg bán thành phẩm của mẻ = materialCost / sản lượng đạt */
        private BigDecimal unitCost;
        // Steps progress
        private int totalSteps;
        private int completedSteps;
        private BigDecimal stepProgressPct;
        private List<BatchStepDto> steps;
        private BatchCancellationDto cancellation;
        // NVL items (legacy)
        private List<Object> items;
        /** Nguyên liệu RIÊNG của mẻ này (từ phương án, đã làm tròn theo loại đơn vị) */
        private List<MaterialLineDto> batchMaterials;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class StartBatchRequest {
        private Long workOrderId;
        private Long recipeId;
        private String notes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class StartStepRequest {
        // Hiện chưa cần field gì thêm, giữ object rỗng để dễ mở rộng (ví dụ ghi chú khi bắt đầu)
        private String notes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteStepRequest {
        private List<String> attachments;   // URLs đã upload
        private String notes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteBatchRequest {
        /** Sản lượng ĐẠT chất lượng (kg) — nhập vào Kho bán thành phẩm */
        private BigDecimal actualOutputQty;
        private String notes;
        /** Ngày sản xuất của lô thành phẩm — bắt buộc nhập khi hoàn thành mẻ (Issue #1) */
        private Long manufactureDate;
        /** Hạn sử dụng của lô thành phẩm — bắt buộc nhập khi hoàn thành mẻ (Issue #1) */
        private Long expiryDate;
        /** Sản lượng LỖI/huỷ do không đạt chất lượng (kg) — nhập vào Kho Scrap. Nullable/0 nếu không có hàng lỗi. */
        private BigDecimal scrapQty;
        /** Lý do lỗi — bắt buộc nhập nếu scrapQty > 0 */
        private String scrapReason;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CancelBatchRequest {
        private String reason;
        private List<String> attachments;       // URLs đã upload
        private String resolution;              // REDO | REPLACE | ABORT
        private String resolutionNotes;

        /**
         * Sản lượng thực tế đạt được trước khi huỷ (có thể null/0 nếu chưa ra sản phẩm).
         * Sẽ được cộng vào accumulatedQty của lệnh sản xuất.
         */
        private BigDecimal actualOutputQty;

        /**
         * Số lượng từng loại nguyên liệu đã thực tế sử dụng trước khi huỷ.
         * Key   = materialName (khớp với tên trong WorkOrderPlanMaterial / phương án)
         * Value = số lượng đã dùng (>= 0, đơn vị giống lúc lập phương án)
         *
         * Phần chưa dùng = tổng đã trừ kho cho NVL đó − giá trị nhập ở đây
         * → phần đó sẽ được cộng trả lại đúng lô gốc trong kho xưởng.
         *
         * Nếu null/rỗng hoặc thiếu key của một NVL nào → NVL đó coi như đã dùng hết,
         * không hoàn kho phần đó.
         */
        private Map<String, BigDecimal> usedMaterialQtys;
    }

    // ─── Kho xưởng — trừ/hoàn theo FIFO ───────────────────────────────────────

    /** Chi tiết 1 lần trừ kho xưởng theo FIFO khi bắt đầu lệnh sản xuất */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockDeductionDto {
        private Long id;
        private Long stockId;
        private String materialName;
        private String unit;
        /** Số lượng đã trừ khỏi lô này khi bắt đầu lệnh */
        private BigDecimal deductedQty;
        /** Số lượng thực tế đã dùng (chỉ có giá trị sau khi hủy + nhập liệu) */
        private BigDecimal actualUsedQty;
        /** Đã hoàn kho phần dư chưa */
        private Boolean returned;
        /** Hạn sử dụng của lô đã trừ (epoch ms), null nếu lô không có hạn */
        private Long stockExpiryDate;
        private Long createdAt;
    }

    /** Tổng hợp NVL đã trừ kho cho 1 lệnh sản xuất — dùng để hiển thị form khi hủy mẻ */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderMaterialUsageDto {
        private String materialName;
        private String unit;
        /** Tổng số lượng đã trừ kho cho lệnh này (cộng tất cả các lô) */
        private BigDecimal totalDeductedQty;
        /** Tổng số lượng đã thực tế dùng đã ghi nhận trước đó (nếu có hủy nhiều mẻ) */
        private BigDecimal totalUsedQty;
        /** Phần còn chưa hoàn/chưa xác nhận dùng (totalDeductedQty - totalUsedQty - đã hoàn) */
        private BigDecimal remainingQty;
    }

    // ─── MaintenanceSchedule ──────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaintenanceDto {
        private Long id;
        private Long machineId;
        private String machineName;
        private String maintenanceType;
        private String recurrenceType;
        private String title;
        private String description;
        private Long plannedStart;
        private Long plannedEnd;
        private Long actualStart;
        private Long actualEnd;
        private BigDecimal plannedDowntimeHours;
        private BigDecimal actualDowntimeHours;
        private String vendorName;
        private String vendorContactPerson;
        private String vendorPhone;
        private BigDecimal estimatedCost;
        private BigDecimal actualCost;
        private List<String> beforeImages;
        private List<String> afterImages;
        private List<String> receiptImages;
        private String completionNotes;
        private String status;
        private String createdByName;
        private BigDecimal deviationDays;
        private Long createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateMaintenanceRequest {
        private Long machineId;
        private String maintenanceType;      // PREVENTIVE | CORRECTIVE | INSPECTION
        private String recurrenceType;       // ONCE | WEEKLY | MONTHLY | QUARTERLY
        private String title;
        private String description;
        private Long plannedStart;
        private Long plannedEnd;
        private BigDecimal plannedDowntimeHours;
        private String vendorName;
        private String vendorContactPerson;
        private String vendorPhone;
        private Integer recurrenceDay;
        private Integer recurrenceMonthInQuarter;
        private BigDecimal estimatedCost;
        private List<String> beforeImages;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteMaintenanceRequest {
        private Long actualStart;
        private Long actualEnd;
        private BigDecimal actualDowntimeHours;
        private BigDecimal actualCost;
        private List<String> afterImages;
        private List<String> receiptImages;
        private String notes;
    }

    // ─── Machine ─────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MachineWorkScheduleDto {
        private String activeWeekdays;  // e.g. "[1,2,3,4,5,6]"
        private int startHour;
        private int endHour;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MachineDto {
        private Long id;
        private String name;
        private BigDecimal capacityHoursPerMonth;
        private String status;
        private String description;
        private Long purchaseDate;
        private BigDecimal purchaseCost;
        private String manufacturer;
        private String serialNumber;
        private Long createdAt;
        // Computed stats
        private BigDecimal totalMaintenanceCost;
        private int totalMaintenanceCount;
        private Long nextMaintenanceDate;
        private String nextMaintenanceTitle;
        private MachineWorkScheduleDto workSchedule;
        private Long factoryId;       // ← thêm
        private String factoryName;   // ← thêm
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveMachineRequest {
        private String name;
        private BigDecimal capacityHoursPerMonth;
        private String description;
        private Long purchaseDate;
        private BigDecimal purchaseCost;
        private String manufacturer;
        private String serialNumber;
        private Long factoryId;
        /** Trạng thái (ACTIVE / INACTIVE / UNDER_MAINTENANCE) — cho sửa trực tiếp ở form (Mục 6) */
        private String status;
    }

    /** 1 khoảng thời gian máy bị chiếm bởi 1 bước của 1 mẻ/lệnh — dùng để vẽ sọc chéo xanh dương trên Gantt máy */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MachineOccupancyDto {
        private Long machineId;
        private Long stepId;
        private Long batchId;
        private String batchCode;
        private Long workOrderId;
        private String workOrderCode;
        private String stepName;
        private Long startedAt;
        /** null nếu bước đang chạy (chưa hoàn thành) */
        private Long completedAt;
        /** Thời gian dự kiến hoàn thành bước (phút) — snapshot từ lúc lập phương án */
        private Integer durationMinutes;
        /**
         * Thời điểm DỰ KIẾN hoàn thành = startedAt + durationMinutes (phút).
         * Đây là lịch hoạt động dự kiến của máy, KHÔNG phụ thuộc giờ hiện tại —
         * dùng để vẽ Gantt ngay cả khi bước chưa hoàn thành và mốc dự kiến nằm
         * trong tương lai (vd: bắt đầu 9:09, dự kiến xong 10:09).
         * Nếu bước đã completedAt thì estimatedEndAt = completedAt (thực tế đã xong).
         * Nếu không có durationMinutes (dữ liệu cũ) → fallback null, FE tự xử lý.
         */
        private Long estimatedEndAt;
    }

    // ─── Machine Metrics (trang quản lý metric máy) ────────────────────────────

    /** 1 điểm dữ liệu theo tháng cho chart thời gian sản xuất / bảo trì */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MachineMonthlyMetricDto {
        /** "2026-01" */
        private String month;
        private BigDecimal productionHours;
        private BigDecimal maintenanceHours;
    }

    /** Tổng quan + lịch sử bảo trì đầy đủ của 1 máy — dùng cho trang quản lý metric máy */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MachineMetricsDto {
        private Long machineId;
        private String machineName;
        private String status;
        private String factoryName;

        /** Thời gian mua máy (= ngày tạo nếu không khai báo purchaseDate) */
        private Long purchaseDate;
        /** Thời điểm bắt đầu có hoạt động sản xuất đầu tiên */
        private Long firstProductionAt;
        /** Thời điểm hoạt động sản xuất gần nhất */
        private Long lastProductionAt;
        /** Tổng số giờ máy đã thực sự hoạt động sản xuất (cộng dồn các bước đã hoàn thành/đang chạy đến hiện tại) */
        private BigDecimal totalProductionHours;
        /** Tổng số giờ máy hư hỏng/bảo trì (downtime thực tế — nếu chưa hoàn thành, dùng dự kiến đến hiện tại) */
        private BigDecimal totalMaintenanceHours;
        /** Tổng chi phí bảo trì/bảo dưỡng ĐÃ HOÀN TẤT (chỉ tính các phiếu COMPLETED) */
        private BigDecimal totalCompletedMaintenanceCost;
        /** Số lần bảo trì/sửa chữa đã hoàn tất */
        private int completedMaintenanceCount;
        /** Số lần đang xử lý / lên kế hoạch */
        private int activeMaintenanceCount;

        /** Chart: theo từng tháng — giờ sản xuất vs giờ bảo trì/hư hỏng */
        private List<MachineMonthlyMetricDto> monthlyChart;

        /** Lịch sử bảo trì/bảo dưỡng đầy đủ — mới nhất trước */
        private List<MaintenanceDto> maintenanceHistory;
    }

    // ─── Dashboard ────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductionFactoryDto {
        private Long id;
        private String name;
        private String address;
        private String description;
        private String status;
        private List<FactoryManagerDto> managers;
        private Long createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryManagerDto {
        private Long id;
        private String fullName;
        private String username;
        private String phone;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateFactoryRequest {
        private String name;
        private String address;
        private String description;
        private List<Long> managerIds;   // user ids của factory workers
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateFactoryManagersRequest {
        private List<Long> managerIds;
    }

    // ─── Dashboard ────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductionDashboardDto {
        // === KPI tổng hợp ===
        private int totalActivePlans;
        private int totalWorkOrders;
        private int inProgressOrders;
        private int completedOrders;
        private int scheduledOrders;        // hẹn giờ chưa đến ngày
        private int pendingPlanOrders;      // chờ lập phương án

        /** TỔNG SẢN LƯỢNG (kg) của tất cả lệnh sản xuất đã COMPLETED. */
        private java.math.BigDecimal totalCompletedOutput;

        /** Tổng sản lượng hoàn thành trong THÁNG HIỆN TẠI (kg). */
        private java.math.BigDecimal completedOutputThisMonth;

        // === Kế hoạch sản xuất ===
        private List<ProductionPlanDto> recentPlans;

        // === Lệnh sản xuất — calendar data ===
        private List<WorkOrderCalendarItem> calendarItems;

        // === Máy móc ===
        private int totalMachines;
        private int activeMachines;
        private int underMaintenanceMachines;
        private List<MachineDto> machines;
        private List<MaintenanceDto> upcomingMaintenance;   // 30 ngày tới

        // === Batch metrics ===
        private long totalBatchesThisMonth;
        private long cancelledBatchesThisMonth;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderCalendarItem {
        private Long id;
        private String workOrderCode;
        private String productName;
        private Long scheduledStartDate;
        private Long plannedEndDate;
        private BigDecimal plannedQty;
        private BigDecimal accumulatedQty;
        private BigDecimal progressPct;
        private String status;
        private String colorLevel;
        private Long productionPlanId;   // để group theo kế hoạch
        private String planCode;
    }

    // ─── Owner actions ───────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExtendWorkOrderRequest {
        private Long newEndDate;
        private String reason;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveWorkScheduleRequest {
        private String activeWeekdays;  // e.g. "[1,2,3,4,5,6]"
        private int startHour;
        private int endHour;
    }

    // ─── Hao hụt đóng gói tổng hợp cho 1 lệnh sản xuất (chỉ có khi tất cả mẻ đã xong) ──

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderLossDto {
        /** Tổng sản lượng thực tế các mẻ đã sản xuất ra (kg, trước đóng gói) */
        private BigDecimal totalActualOutputQty;
        /** Tổng kg đã được kế toán kho xác nhận nhận (đã quy đổi tương ứng theo batch) */
        private BigDecimal totalActualReceivedWeight;
        /** Tổng số lượng đóng gói thực tế (túi/hộp), quy đổi tương ứng theo batch */
        private BigDecimal totalPackagedQty;
        private String packagedUnit;
        /** Hao hụt (kg) = totalActualOutputQty − totalActualReceivedWeight, chỉ tính nếu > 0 */
        private BigDecimal lossQty;
        /** Tỷ lệ hao hụt (%) = lossQty / totalActualOutputQty × 100 */
        private BigDecimal lossPct;
        /** Đã đối soát đủ chưa — true nếu mọi batch COMPLETED đều đã được chuyển + xác nhận nhận hết */
        private boolean fullyReconciled;
    }

    // ─── Công đoạn cấp lệnh (bước chung / bước riêng) ─────────────────────────

    /** 1 lần chạy của 1 công đoạn (cấp lệnh) */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderStepRunDto {
        private Long id;
        private Integer runNumber;
        private Integer totalRuns;
        private BigDecimal runQty;
        private Integer batchNumber;   // null nếu bước chung
        private String status;         // PENDING | IN_PROGRESS | COMPLETED
        private boolean canStart;      // đủ điều kiện bắt đầu (công đoạn trước đã xong theo quy tắc)
        private boolean requiresQc;
        private String controlType;    // NONE | VISUAL | PHOTO_WEIGHT
        private Long machineId;
        private String machineName;
        private Integer durationMinutes;
        private String startedByName;
        private Long startedAt;
        private List<String> attachments;
        private String notes;
        /** Số hư hỏng ghi nhận ở bước này (chỉ bước có kiểm soát). Đơn vị = outputUnit của lệnh. */
        private BigDecimal damagedQty;
        private String completedByName;
        private Long completedAt;
    }

    /** 1 công đoạn của lệnh (gom các lần chạy) */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderStageDto {
        private Integer stageSequence;
        private String stageName;
        private boolean shared;        // làm chung cả lệnh
        private String controlType;
        private Long machineId;
        private String machineName;
        private Integer durationMinutes;
        private int totalRuns;
        private int completedRuns;
        private String status;         // PENDING | IN_PROGRESS | COMPLETED
        private BigDecimal totalQty;   // tổng khối lượng công đoạn xử lý
        private List<WorkOrderStepRunDto> runs;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class StartStageRunRequest {
        private String notes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteStageRunRequest {
        private List<String> attachments;
        private String notes;
        /**
         * Số hư hỏng tại bước này — chỉ nhận khi bước CÓ kiểm soát (VISUAL / PHOTO_WEIGHT).
         * Null hoặc 0 nếu không có hư hỏng. Không được âm.
         */
        private BigDecimal damagedQty;
    }

    // ─── WorkOrder Detail (cho Gantt mẻ) ─────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderDetailDto {
        private WorkOrderDto workOrder;
        private WorkOrderPlanDto plan;
        private List<ProductionBatchDto> batches;   // đã sort theo batchNumber
        /** Các công đoạn của lệnh (bước chung + bước riêng) — luồng thực thi chính */
        private List<WorkOrderStageDto> stages;
        private BigDecimal progressPct;
        private int currentBatchNumber;             // mẻ đang làm (IN_PROGRESS)
        private String currentStepName;             // bước đang làm trong mẻ hiện tại
        /** Còn mẻ kế tiếp chưa bắt đầu không (dùng để FE hỏi "bắt đầu mẻ tiếp theo ngay?") */
        private boolean hasNextBatch;
        private Integer nextBatchNumber;
        /**
         * Hao hụt đóng gói tổng hợp — CHỈ có giá trị (non-null) khi TẤT CẢ mẻ của lệnh
         * này đã COMPLETED. Nếu còn mẻ PENDING/IN_PROGRESS, trường này là null.
         */
        private WorkOrderLossDto packagingLoss;
    }
}