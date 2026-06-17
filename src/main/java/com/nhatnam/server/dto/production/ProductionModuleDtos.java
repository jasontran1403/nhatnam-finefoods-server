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
        private Integer totalBatches;
        private BigDecimal batchQtyPerRun;
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

    // ─── ProductionBatch ──────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BatchStepDto {
        private Long id;
        private int stepSequence;
        private String stepName;
        private String status;
        private boolean requiresQc;
        private Long machineId;
        private String machineName;
        private List<String> attachments;
        private String notes;
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
        // Steps progress
        private int totalSteps;
        private int completedSteps;
        private BigDecimal stepProgressPct;
        private List<BatchStepDto> steps;
        private BatchCancellationDto cancellation;
        // NVL items (legacy)
        private List<Object> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class StartBatchRequest {
        private Long workOrderId;
        private Long recipeId;
        private String notes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteStepRequest {
        private List<String> attachments;   // URLs đã upload
        private String notes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteBatchRequest {
        private BigDecimal actualOutputQty;
        private String notes;
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

    // ─── WorkOrder Detail (cho Gantt mẻ) ─────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderDetailDto {
        private WorkOrderDto workOrder;
        private WorkOrderPlanDto plan;
        private List<ProductionBatchDto> batches;   // đã sort theo batchNumber
        private BigDecimal progressPct;
        private int currentBatchNumber;             // mẻ đang làm (IN_PROGRESS)
        private String currentStepName;             // bước đang làm trong mẻ hiện tại
    }
}