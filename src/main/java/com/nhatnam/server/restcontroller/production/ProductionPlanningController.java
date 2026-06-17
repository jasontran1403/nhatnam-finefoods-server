package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.ProductionPlanningDtos.*;
import com.nhatnam.server.service.ProductionPlanningService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API cho Production Planning Module.
 * Endpoint /api/owner/** chỉ OWNER truy cập.
 */
@RestController
@RequiredArgsConstructor
public class ProductionPlanningController {

    private final ProductionPlanningService planningService;

    // ─── Machine ──────────────────────────────────────────────────────────────

    @GetMapping("/api/owner/factory/machines")
    public ApiResponse<List<MachineDto>> listMachines(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ApiResponse.ok(planningService.listMachines(activeOnly));
    }

    @PostMapping("/api/owner/factory/machines")
    public ApiResponse<MachineDto> createMachine(@RequestBody SaveMachineRequest req) {
        return ApiResponse.ok(planningService.saveMachine(null, req));
    }

    @PutMapping("/api/owner/factory/machines/{id}")
    public ApiResponse<MachineDto> updateMachine(@PathVariable Long id,
                                                   @RequestBody SaveMachineRequest req) {
        return ApiResponse.ok(planningService.saveMachine(id, req));
    }

    @PatchMapping("/api/owner/factory/machines/{id}/toggle")
    public ApiResponse<MachineDto> toggleMachine(@PathVariable Long id,
                                                   @RequestParam boolean active) {
        return ApiResponse.ok(planningService.toggleMachineStatus(id, active));
    }

    // ─── Maintenance Schedule ─────────────────────────────────────────────────

    @GetMapping("/api/owner/factory/maintenance")
    public ApiResponse<MaintenanceSummaryDto> getMaintenanceSummary(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(required = false) Long machineId) {
        int y = year == 0 ? java.time.Year.now().getValue() : year;
        return ApiResponse.ok(planningService.getMaintenanceSummary(y, machineId));
    }

    @PostMapping("/api/owner/factory/maintenance")
    public ApiResponse<MaintenanceDto> createMaintenance(@RequestBody SaveMaintenanceRequest req) {
        return ApiResponse.ok(planningService.saveMaintenance(null, req));
    }

    @PutMapping("/api/owner/factory/maintenance/{id}")
    public ApiResponse<MaintenanceDto> updateMaintenance(@PathVariable Long id,
                                                          @RequestBody SaveMaintenanceRequest req) {
        return ApiResponse.ok(planningService.saveMaintenance(id, req));
    }

    @PatchMapping("/api/owner/factory/maintenance/{id}/complete")
    public ApiResponse<MaintenanceDto> completeMaintenance(@PathVariable Long id,
                                                            @RequestBody CompleteMaintenanceRequest req) {
        return ApiResponse.ok(planningService.completeMaintenance(id, req));
    }

    @DeleteMapping("/api/owner/factory/maintenance/{id}")
    public ApiResponse<Void> deleteMaintenance(@PathVariable Long id) {
        planningService.deleteMaintenance(id);
        return ApiResponse.ok(null);
    }

    // ─── Annual MPS ──────────────────────────────────────────────────────────

    @GetMapping("/api/owner/factory/mps/dashboard")
    public ApiResponse<AnnualMpsDashboardDto> getMpsDashboard(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(required = false) Long productId) {
        int y = year == 0 ? java.time.Year.now().getValue() : year;
        return ApiResponse.ok(planningService.getAnnualMpsDashboard(y, productId));
    }

    @GetMapping("/api/owner/factory/mps")
    public ApiResponse<List<AnnualMpsDto>> listMps(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(required = false) Long productId) {
        int y = year == 0 ? java.time.Year.now().getValue() : year;
        return ApiResponse.ok(planningService.listAnnualMps(y, productId));
    }

    @PostMapping("/api/owner/factory/mps")
    public ApiResponse<AnnualMpsDto> createMps(@RequestBody SaveAnnualMpsRequest req) {
        return ApiResponse.ok(planningService.saveAnnualMps(null, req));
    }

    @PutMapping("/api/owner/factory/mps/{id}")
    public ApiResponse<AnnualMpsDto> updateMps(@PathVariable Long id,
                                                @RequestBody SaveAnnualMpsRequest req) {
        return ApiResponse.ok(planningService.saveAnnualMps(id, req));
    }

    @PatchMapping("/api/owner/factory/mps/{id}/status")
    public ApiResponse<AnnualMpsDto> updateMpsStatus(@PathVariable Long id,
                                                      @RequestParam String status) {
        return ApiResponse.ok(planningService.updateMpsStatus(id, status));
    }

    @DeleteMapping("/api/owner/factory/mps/{id}")
    public ApiResponse<Void> deleteMps(@PathVariable Long id) {
        planningService.deleteAnnualMps(id);
        return ApiResponse.ok(null);
    }

    // ─── Work Orders ──────────────────────────────────────────────────────────

    @GetMapping("/api/owner/factory/work-orders")
    public ApiResponse<Page<WorkOrderDto>> listWorkOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(planningService.listWorkOrders(page, size, status));
    }

    @GetMapping("/api/owner/factory/work-orders/{id}")
    public ApiResponse<WorkOrderDto> getWorkOrder(@PathVariable Long id) {
        return ApiResponse.ok(planningService.getWorkOrder(id));
    }

    @PostMapping("/api/owner/factory/work-orders")
    public ApiResponse<WorkOrderDto> createWorkOrder(@RequestBody CreateWorkOrderRequest req,
                                                      Authentication auth) {
        return ApiResponse.ok(planningService.createWorkOrder(req, auth.getName()));
    }

    @PatchMapping("/api/owner/factory/work-orders/{id}/status")
    public ApiResponse<WorkOrderDto> updateStatus(@PathVariable Long id,
                                                   @RequestBody UpdateWorkOrderStatusRequest req) {
        return ApiResponse.ok(planningService.updateWorkOrderStatus(id, req));
    }
}
