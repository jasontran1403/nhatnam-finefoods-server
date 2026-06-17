package com.nhatnam.server.dto.production;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

public class ProductionPlanningDtos {

    // ─── Machine ─────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MachineDto {
        private Long id;
        private String name;
        private BigDecimal capacityHoursPerMonth;
        private String status;
        private String description;
        private Long createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveMachineRequest {
        private String name;
        private BigDecimal capacityHoursPerMonth;
        private String description;
    }

    // ─── MaintenanceSchedule ─────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaintenanceDto {
        private Long id;
        private Long machineId;
        private String machineName;
        private Long plannedStart;
        private Long plannedEnd;
        private Long actualStart;
        private Long actualEnd;
        private BigDecimal plannedDowntimeHours;
        private BigDecimal actualDowntimeHours;
        private String maintenanceType;
        private String status;
        private String notes;
        private BigDecimal deviationDays; // plannedStart vs actualStart
        private Long createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveMaintenanceRequest {
        private Long machineId;
        private Long plannedStart;
        private Long plannedEnd;
        private BigDecimal plannedDowntimeHours;
        private String maintenanceType;
        private String notes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteMaintenanceRequest {
        private Long actualStart;
        private Long actualEnd;
        private BigDecimal actualDowntimeHours;
        private String notes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaintenanceSummaryDto {
        private int year;
        private BigDecimal totalPlannedDowntimeHours;
        private BigDecimal totalActualDowntimeHours;
        private long completedOnSchedule;
        private long totalScheduled;
        private long adjustmentsMade;
        private BigDecimal avgDeviationDays;
        private List<MaintenanceDto> items;
    }

    // ─── AnnualMPS ───────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AnnualMpsDto {
        private Long id;
        private Integer year;
        private Integer month;
        private Long factoryProductId;
        private String factoryProductName;
        private String factoryProductUnit;
        private Long machineId;
        private String machineName;
        private BigDecimal forecastDemand;
        private BigDecimal plannedProductionQty;
        private BigDecimal machineHoursRequired;
        private BigDecimal netAvailableMachineHours;
        private BigDecimal utilizationPercent;
        private String status;
        private String notes;
        private Long createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveAnnualMpsRequest {
        private Integer year;
        private Integer month;
        private Long factoryProductId;
        private Long machineId;
        private BigDecimal forecastDemand;
        private BigDecimal plannedProductionQty;
        private BigDecimal machineHoursRequired;
        private BigDecimal netAvailableMachineHours;
        private String notes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AnnualMpsDashboardDto {
        private int year;
        private BigDecimal totalProductionHours;
        private BigDecimal avgUtilizationPct;
        private BigDecimal productionVariancePct;
        private List<AnnualMpsDto> plans;
        private List<MonthlyKpiDto> monthlyKpis;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MonthlyKpiDto {
        private int month;
        private String monthLabel;
        private BigDecimal plannedQty;
        private BigDecimal plannedHours;
        private BigDecimal machineHours;
        private BigDecimal maintenanceHours;
        private BigDecimal utilizationPct;
    }

    // ─── WorkOrder ───────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderOperationDto {
        private Long id;
        private Integer operationSequence;
        private String operationName;
        private String operationDescription;
        private Long machineId;
        private String machineName;
        private BigDecimal plannedHours;
        private BigDecimal actualHours;
        private BigDecimal plannedQty;
        private BigDecimal actualQty;
        private BigDecimal scrapQty;
        private Boolean qcRequired;
        private String qcType;
        private String qcControlPoint;
        private String qcStatus;
        private String qcNotes;
        private String status;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderDto {
        private Long id;
        private String workOrderCode;
        private Long factoryProductId;
        private String productName;
        private Long recipeId;
        private String recipeName;
        private Long annualMpsId;
        private Long plannedStartDate;
        private Long plannedEndDate;
        private Long actualStartDate;
        private Long actualEndDate;
        private BigDecimal plannedQty;
        private BigDecimal actualQty;
        private BigDecimal scrapQty;
        private String outputUnit;
        private String status;
        private String notes;
        private String assignedToName;
        private Long assignedToId;
        private String createdByName;
        private Long createdAt;
        private Long productionBatchId;
        private String batchCode;
        private List<WorkOrderOperationDto> operations;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class WorkOrderOperationRequest {
        private Integer operationSequence;
        private String operationName;
        private String operationDescription;
        private Long machineId;
        private BigDecimal plannedHours;
        private BigDecimal plannedQty;
        private Boolean qcRequired;
        private String qcType;
        private String qcControlPoint;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateWorkOrderRequest {
        private Long factoryProductId;
        private Long recipeId;
        private Long annualMpsId;
        private BigDecimal plannedQty;
        private Long plannedStartDate;
        private Long plannedEndDate;
        private String notes;
        private Long assignedToId;
        private List<WorkOrderOperationRequest> operations;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateWorkOrderStatusRequest {
        private String status;
        private BigDecimal actualQty;
        private BigDecimal scrapQty;
        private String notes;
        private Long productionBatchId;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateOperationRequest {
        private BigDecimal actualHours;
        private BigDecimal actualQty;
        private BigDecimal scrapQty;
        private String qcStatus;
        private String qcNotes;
        private String status;
    }

    // ─── Dashboard summary ────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductionPlanDashboardDto {
        private int year;
        private long totalWorkOrders;
        private long completedWorkOrders;
        private long inProgressWorkOrders;
        private long draftWorkOrders;
        private BigDecimal totalPlannedQty;
        private BigDecimal totalActualQty;
        private BigDecimal overallVariancePct;
        private BigDecimal totalPlannedDowntimeHours;
        private BigDecimal avgMachineUtilizationPct;
    }
}
