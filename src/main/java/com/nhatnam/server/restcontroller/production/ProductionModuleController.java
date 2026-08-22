package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.ProductionModuleDtos.*;
import com.nhatnam.server.service.ProductionModuleService;
import com.nhatnam.server.service.ProductionFileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import com.nhatnam.server.entity.MaterialVendor;
import com.nhatnam.server.repository.MaterialVendorRepository;


@RestController
@RequiredArgsConstructor
public class ProductionModuleController {

    private final ProductionModuleService service;
    private final ProductionFileStorageService fileStorage;
    private final com.nhatnam.server.repository.BatchStepTemplateRepository stepTemplateRepo;
    private final MaterialVendorRepository materialVendorRepository;

    // ═══════════════════════════════════════════════════════════════════════════
    // OWNER ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    // ── Dashboard ─────────────────────────────────────────────────────────────

    @GetMapping("/api/owner/production/dashboard")
    public ApiResponse<ProductionDashboardDto> getDashboard() {
        return ApiResponse.ok(service.getDashboard());
    }

    // ── Production Plans ──────────────────────────────────────────────────────

    @GetMapping("/api/owner/production/plans")
    public ApiResponse<Page<ProductionPlanDto>> listPlans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.listPlans(page, size, status));
    }

    @GetMapping("/api/owner/production/plans/{id}")
    public ApiResponse<ProductionPlanDto> getPlan(@PathVariable Long id) {
        return ApiResponse.ok(service.getPlan(id));
    }

    @PostMapping("/api/owner/production/plans")
    public ApiResponse<ProductionPlanDto> createPlan(@RequestBody CreatePlanRequest req,
                                                     Authentication auth) {
        return ApiResponse.ok(service.createPlan(req, auth.getName()));
    }

    @PatchMapping("/api/owner/production/plans/{id}/status")
    public ApiResponse<ProductionPlanDto> updatePlanStatus(@PathVariable Long id,
                                                           @RequestParam String status) {
        return ApiResponse.ok(service.updatePlanStatus(id, status));
    }

    // ── Work Orders (Owner) ───────────────────────────────────────────────────

    @GetMapping("/api/owner/production/plans/{id}/work-orders")
    public ApiResponse<List<WorkOrderDto>> listWorkOrdersByPlan(@PathVariable Long id) {
        return ApiResponse.ok(service.listWorkOrdersByPlan(id));
    }

    @GetMapping("/api/owner/production/work-orders")
    public ApiResponse<Page<WorkOrderDto>> listWorkOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.listWorkOrders(page, size, status));
    }

    @GetMapping("/api/owner/production/work-orders/{id}")
    public ApiResponse<WorkOrderDetailDto> getWorkOrderDetail(@PathVariable Long id) {
        return ApiResponse.ok(service.getWorkOrderDetail(id));
    }

    @PostMapping("/api/owner/production/work-orders")
    public ApiResponse<WorkOrderDto> createWorkOrder(@RequestBody CreateWorkOrderRequest req,
                                                     Authentication auth) {
        return ApiResponse.ok(service.createWorkOrder(req, auth.getName()));
    }

    @PatchMapping("/api/owner/production/work-orders/{id}/status")
    public ApiResponse<WorkOrderDto> updateWorkOrderStatus(@PathVariable Long id,
                                                           @RequestBody UpdateWorkOrderStatusRequest req) {
        return ApiResponse.ok(service.updateWorkOrderStatusByOwner(id, req));
    }

    @PatchMapping("/api/owner/production/work-orders/{id}/extend")
    public ApiResponse<WorkOrderDto> extendWorkOrder(@PathVariable Long id,
                                                     @RequestBody ExtendWorkOrderRequest req) {
        return ApiResponse.ok(service.extendWorkOrder(id, req));
    }

    @PutMapping("/api/owner/production/machines/{id}/schedule")
    public ApiResponse<MachineDto> saveWorkSchedule(@PathVariable Long id,
                                                    @RequestBody SaveWorkScheduleRequest req) {
        return ApiResponse.ok(service.saveWorkSchedule(id, req));
    }

    @GetMapping("/api/factory/machines/{id}/schedule")
    public ApiResponse<MachineDto> getWorkScheduleFactory(@PathVariable Long id) {
        return ApiResponse.ok(service.getMachineDto(id));
    }

    // ── Machines (Owner — read) ───────────────────────────────────────────────

    @GetMapping("/api/owner/production/machines")
    public ApiResponse<List<MachineDto>> listMachines(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return ApiResponse.ok(service.listMachines(activeOnly));
    }

    @PostMapping("/api/owner/production/machines")
    public ApiResponse<MachineDto> createMachine(@RequestBody SaveMachineRequest req,
                                                 Authentication auth) {
        return ApiResponse.ok(service.saveMachine(null, req));
    }

    @PutMapping("/api/owner/production/machines/{id}")
    public ApiResponse<MachineDto> updateMachine(@PathVariable Long id,
                                                 @RequestBody SaveMachineRequest req) {
        return ApiResponse.ok(service.saveMachine(id, req));
    }

    @PatchMapping("/api/owner/production/machines/{id}/toggle")
    public ApiResponse<MachineDto> toggleMachine(@PathVariable Long id,
                                                 @RequestParam boolean active) {
        return ApiResponse.ok(service.toggleMachine(id, active));
    }

    // ── Maintenance (Owner — read + manage) ───────────────────────────────────

    @GetMapping("/api/owner/production/maintenance")
    public ApiResponse<List<MaintenanceDto>> listMaintenance(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(required = false) Long machineId) {
        int y = year == 0 ? java.time.Year.now().getValue() : year;
        return ApiResponse.ok(service.listMaintenance(y, machineId));
    }

    /**
     * Khoảng thời gian các máy đang/đã bị chiếm bởi bước sản xuất (WorkOrder/Batch)
     * trong khoảng [fromMs, toMs) — dùng để vẽ sọc chéo xanh dương trên Gantt máy.
     */
    @GetMapping("/api/owner/production/machine-occupancy")
    public ApiResponse<List<MachineOccupancyDto>> listMachineOccupancy(
            @RequestParam long fromMs, @RequestParam long toMs) {
        return ApiResponse.ok(service.listMachineOccupancy(fromMs, toMs));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FACTORY WORKER ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    // ── Xem lệnh được giao ────────────────────────────────────────────────────

    @GetMapping("/api/factory/work-orders")
    public ApiResponse<List<WorkOrderDto>> listMyWorkOrders(
            @RequestParam(required = false) Long factoryId,
            Authentication auth) {
        // Không lọc theo userId nữa — lọc theo factoryId (optional)
        return ApiResponse.ok(service.listActiveOrdersForFactory(factoryId));
    }

    @GetMapping("/api/factory/my-factories")
    public ApiResponse<List<ProductionFactoryDto>> listMyFactories(Authentication auth) {
        Long userId = getUserId(auth);
        return ApiResponse.ok(service.listMyFactories(userId));
    }

    @GetMapping("/api/factory/work-orders/{id}")
    public ApiResponse<WorkOrderDetailDto> getWorkOrderDetailFactory(@PathVariable Long id) {
        return ApiResponse.ok(service.getWorkOrderDetail(id));
    }

    // ── Lập phương án ─────────────────────────────────────────────────────────

    /**
     * Preview phương án theo biến thể sản xuất — KHÔNG lưu, chỉ tính toán để hiển thị
     * nguyên liệu từng mẻ/tổng + các bước trước khi nhân viên quyết định submit.
     */
    @GetMapping("/api/factory/work-orders/{id}/plan-preview")
    public ApiResponse<PlanPreviewDto> previewPlanByRecipe(@PathVariable Long id,
                                                            @RequestParam Long recipeId,
                                                            @RequestParam BigDecimal requestedQty) {
        return ApiResponse.ok(service.previewPlanByRecipe(id, recipeId, requestedQty));
    }

    /**
     * Lập phương án theo biến thể sản xuất (THAY THẾ cách nhập tay cũ).
     * Chỉ cần chọn 1 biến thể (đúng FactoryProduct của lệnh) + nhập sản lượng cần sản xuất.
     */
    @PostMapping("/api/factory/work-orders/{id}/plan-by-recipe")
    public ApiResponse<WorkOrderPlanDto> submitPlanByRecipe(@PathVariable Long id,
                                                             @RequestBody SubmitPlanByRecipeRequest req,
                                                             Authentication auth) {
        return ApiResponse.ok(service.submitPlanByRecipe(id, req, auth.getName()));
    }

    // ── Bắt đầu lệnh ─────────────────────────────────────────────────────────

    @PostMapping("/api/factory/work-orders/{id}/start")
    public ApiResponse<WorkOrderDto> startWorkOrder(@PathVariable Long id, Authentication auth) {
        return ApiResponse.ok(service.startWorkOrder(id, auth.getName()));
    }

    // ── Batch operations ──────────────────────────────────────────────────────

    @PostMapping("/api/factory/batches/start")
    public ApiResponse<ProductionBatchDto> startBatch(@RequestBody StartBatchRequest req,
                                                      Authentication auth) {
        return ApiResponse.ok(service.startBatch(req, auth.getName()));
    }

    @PostMapping("/api/factory/batches/{batchId}/steps/{stepSeq}/start")
    public ApiResponse<BatchStepDto> startStep(@PathVariable Long batchId,
                                               @PathVariable int stepSeq,
                                               @RequestBody(required = false) StartStepRequest req,
                                               Authentication auth) {
        return ApiResponse.ok(service.startStep(batchId, stepSeq, req, auth.getName()));
    }

    @PostMapping("/api/factory/batches/{batchId}/steps/{stepSeq}/complete")
    public ApiResponse<BatchStepDto> completeStep(@PathVariable Long batchId,
                                                  @PathVariable int stepSeq,
                                                  @RequestBody CompleteStepRequest req,
                                                  Authentication auth) {
        return ApiResponse.ok(service.completeStep(batchId, stepSeq, req, auth.getName()));
    }

    @PostMapping("/api/factory/batches/{batchId}/complete")
    public ApiResponse<ProductionBatchDto> completeBatch(@PathVariable Long batchId,
                                                         @RequestBody CompleteBatchRequest req,
                                                         Authentication auth) {
        return ApiResponse.ok(service.completeBatch(batchId, req, auth.getName()));
    }

    // ── Công đoạn cấp lệnh (bước chung / bước riêng) ─────────────────────────

    @PostMapping("/api/factory/work-order-steps/{stepId}/start")
    public ApiResponse<WorkOrderStepRunDto> startStageRun(@PathVariable Long stepId,
                                                          @RequestBody(required = false) StartStageRunRequest req,
                                                          Authentication auth) {
        return ApiResponse.ok(service.startStageRun(stepId, req, auth.getName()));
    }

    @PostMapping("/api/factory/work-order-steps/{stepId}/complete")
    public ApiResponse<WorkOrderStepRunDto> completeStageRun(@PathVariable Long stepId,
                                                             @RequestBody CompleteStageRunRequest req,
                                                             Authentication auth) {
        return ApiResponse.ok(service.completeStageRun(stepId, req, auth.getName()));
    }

    @PostMapping("/api/factory/batches/{batchId}/cancel")
    public ApiResponse<ProductionBatchDto> cancelBatch(@PathVariable Long batchId,
                                                       @RequestBody CancelBatchRequest req,
                                                       Authentication auth) {
        return ApiResponse.ok(service.cancelBatch(batchId, req, auth.getName()));
    }

    /**
     * Danh sách nguyên liệu đã trừ kho (chưa hoàn) cho 1 lệnh sản xuất.
     * FE dùng để hiển thị form "đã thực tế dùng bao nhiêu" khi nhân viên huỷ mẻ.
     */
    @GetMapping("/api/factory/work-orders/{workOrderId}/material-usage")
    public ApiResponse<List<WorkOrderMaterialUsageDto>> getMaterialUsage(@PathVariable Long workOrderId) {
        return ApiResponse.ok(service.getMaterialUsageForWorkOrder(workOrderId));
    }

    // ── ProductionFactory (Owner) ─────────────────────────────────────────────

    @GetMapping("/api/owner/production/factories")
    public ApiResponse<List<ProductionFactoryDto>> listFactories() {
        return ApiResponse.ok(service.listFactories());
    }

    @PostMapping("/api/owner/production/factories")
    public ApiResponse<ProductionFactoryDto> createFactory(@RequestBody CreateFactoryRequest req,
                                                           Authentication auth) {
        return ApiResponse.ok(service.createFactory(req, auth.getName()));
    }

    @PatchMapping("/api/owner/production/factories/{id}/managers")
    public ApiResponse<ProductionFactoryDto> updateManagers(@PathVariable Long id,
                                                            @RequestBody UpdateFactoryManagersRequest req) {
        return ApiResponse.ok(service.updateFactoryManagers(id, req));
    }

    @PatchMapping("/api/owner/production/factories/{id}/toggle")
    public ApiResponse<ProductionFactoryDto> toggleFactory(@PathVariable Long id,
                                                           @RequestParam boolean active) {
        return ApiResponse.ok(service.toggleFactory(id, active));
    }

    // ── Machine (Factory) ─────────────────────────────────────────────────────

    @GetMapping("/api/factory/machines")
    public ApiResponse<List<MachineDto>> listMachinesFactory() {
        return ApiResponse.ok(service.listMachines(true));
    }

    @PostMapping("/api/factory/machines")
    public ApiResponse<MachineDto> createMachineFactory(@RequestBody SaveMachineRequest req,
                                                        Authentication auth) {
        return ApiResponse.ok(service.saveMachine(null, req));
    }

    @GetMapping("/api/factory/machines/{id}/metrics")
    public ApiResponse<MachineMetricsDto> getMachineMetrics(@PathVariable Long id) {
        return ApiResponse.ok(service.getMachineMetrics(id));
    }

    // ── Maintenance (Factory) ─────────────────────────────────────────────────

    @GetMapping("/api/factory/maintenance")
    public ApiResponse<List<MaintenanceDto>> listMaintenanceFactory(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(required = false) Long machineId) {
        int y = year == 0 ? java.time.Year.now().getValue() : year;
        return ApiResponse.ok(service.listMaintenance(y, machineId));
    }

    @PostMapping("/api/factory/maintenance")
    public ApiResponse<MaintenanceDto> createMaintenance(@RequestBody CreateMaintenanceRequest req,
                                                         Authentication auth) {
        return ApiResponse.ok(service.createMaintenance(req, auth.getName()));
    }

    @PatchMapping("/api/factory/maintenance/{id}/complete")
    public ApiResponse<MaintenanceDto> completeMaintenance(@PathVariable Long id,
                                                           @RequestBody CompleteMaintenanceRequest req,
                                                           Authentication auth) {
        return ApiResponse.ok(service.completeMaintenance(id, req, auth.getName()));
    }

    @DeleteMapping("/api/factory/maintenance/{id}")
    public ApiResponse<Void> deleteMaintenance(@PathVariable Long id) {
        service.deleteMaintenance(id);
        return ApiResponse.ok(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILE UPLOAD ENDPOINTS (shared: FACTORY_WORKER + OWNER)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Upload ảnh xác nhận bước mẻ */
    @PostMapping(value = "/api/upload/production/batch-step",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> uploadBatchStepImages(
            @RequestParam Long batchId,
            @RequestParam int stepSeq,
            @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(uploadMultiple(files, f -> {
            try { return fileStorage.saveBatchStepImage(batchId, stepSeq, f); }
            catch (IOException e) { throw new RuntimeException(e); }
        }));
    }

    /** Upload ảnh xác nhận 1 lần chạy công đoạn (bước chung/riêng) */
    @PostMapping(value = "/api/upload/production/work-order-step",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> uploadWorkOrderStepImages(
            @RequestParam Long stepId,
            @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(uploadMultiple(files, f -> {
            try { return fileStorage.saveWorkOrderStepImage(stepId, f); }
            catch (IOException e) { throw new RuntimeException(e); }
        }));
    }

    /** Upload ảnh hủy/lỗi mẻ */
    @PostMapping(value = "/api/upload/production/batch-cancel",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> uploadBatchCancelImages(
            @RequestParam Long batchId,
            @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(uploadMultiple(files, f -> {
            try { return fileStorage.saveBatchCancelImage(batchId, f); }
            catch (IOException e) { throw new RuntimeException(e); }
        }));
    }

    /** Upload ảnh bảo trì — before */
    @PostMapping(value = "/api/upload/production/maintenance-before",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> uploadMaintenanceBefore(
            @RequestParam Long maintenanceId,
            @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(uploadMultiple(files, f -> {
            try { return fileStorage.saveMaintenanceBeforeImage(maintenanceId, f); }
            catch (IOException e) { throw new RuntimeException(e); }
        }));
    }

    /** Upload ảnh bảo trì — after */
    @PostMapping(value = "/api/upload/production/maintenance-after",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> uploadMaintenanceAfter(
            @RequestParam Long maintenanceId,
            @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(uploadMultiple(files, f -> {
            try { return fileStorage.saveMaintenanceAfterImage(maintenanceId, f); }
            catch (IOException e) { throw new RuntimeException(e); }
        }));
    }

    /** Upload chứng từ bảo trì */
    @PostMapping(value = "/api/upload/production/maintenance-receipt",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> uploadMaintenanceReceipt(
            @RequestParam Long maintenanceId,
            @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(uploadMultiple(files, f -> {
            try { return fileStorage.saveMaintenanceReceiptImage(maintenanceId, f); }
            catch (IOException e) { throw new RuntimeException(e); }
        }));
    }

    /** Upload hóa đơn nguyên liệu */
    @PostMapping(value = "/api/upload/production/material-invoice",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> uploadMaterialInvoice(
            @RequestParam Long workOrderId,
            @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(uploadMultiple(files, f -> {
            try { return fileStorage.saveMaterialInvoiceImage(workOrderId, f); }
            catch (IOException e) { throw new RuntimeException(e); }
        }));
    }

    /**
     * Upload hóa đơn/chứng từ khi SUPER_ACCOUNTANT hoàn thành phiếu đặt hàng nguyên liệu.
     * Role: SUPER_ACCOUNTANT (không cần FACTORY_WORKER)
     * POST /api/upload/production/material-request-invoice
     */
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasAnyRole('SUPER_ACCOUNTANT','OWNER','ADMIN')")
    @PostMapping(value = "/api/upload/production/material-request-invoice",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> uploadMaterialRequestInvoice(
            @RequestParam(required = false) Long requestId,
            @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(uploadMultiple(files, f -> {
            try { return fileStorage.saveMaterialRequestInvoiceImage(requestId, f); }
            catch (IOException e) { throw new RuntimeException(e); }
        }));
    }


    // ── Material Vendor CRUD ──────────────────────────────────────────────────

    /** GET /api/factory/material-vendors?q=xxx&types=MACHINE,REPAIR */
    @GetMapping("/api/factory/material-vendors")
    public ApiResponse<List<Map<String, Object>>> listVendors(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false) String types) {
        List<MaterialVendor> vendors;
        if (types != null && !types.isBlank()) {
            List<MaterialVendor.VendorType> typeList = java.util.Arrays.stream(types.split(","))
                    .map(t -> MaterialVendor.VendorType.valueOf(t.trim()))
                    .collect(java.util.stream.Collectors.toList());
            // Coi vendorType = NULL như MATERIAL (NCC cũ chưa có loại) → chỉ gộp khi
            // đang lọc bao gồm MATERIAL, tránh lẫn sang MACHINE/REPAIR.
            boolean includeNull = typeList.contains(MaterialVendor.VendorType.MATERIAL);
            vendors = q.isBlank()
                    ? materialVendorRepository.findActiveByTypesOrNull(typeList, includeNull)
                    : materialVendorRepository.findActiveByNameAndTypesOrNull(q, typeList, includeNull);
        } else {
            vendors = q.isBlank()
                    ? materialVendorRepository.findByActiveTrueOrderByNameAsc()
                    : materialVendorRepository.findByNameContainingIgnoreCaseAndActiveTrue(q);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (MaterialVendor v : vendors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("name", v.getName());
            m.put("contactPerson", v.getContactPerson());
            m.put("contactPhone", v.getContactPhone());
            m.put("address", v.getAddress());
            m.put("taxCode", v.getTaxCode());
            m.put("vendorType", v.getVendorType() != null ? v.getVendorType().name() : "MATERIAL");
            result.add(m);
        }
        return ApiResponse.ok(result);
    }

    /** POST /api/factory/material-vendors */
    @PostMapping("/api/factory/material-vendors")
    public ApiResponse<Map<String, Object>> createVendor(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isBlank()) throw new IllegalArgumentException("Tên nhà cung cấp không được trống");
        String typeStr = body.getOrDefault("vendorType", "MATERIAL");
        MaterialVendor.VendorType vtype;
        try { vtype = MaterialVendor.VendorType.valueOf(typeStr); } catch (Exception e) { vtype = MaterialVendor.VendorType.MATERIAL; }
        MaterialVendor v = materialVendorRepository.save(MaterialVendor.builder()
                .name(name)
                .contactPerson(body.getOrDefault("contactPerson", ""))
                .contactPhone(body.getOrDefault("contactPhone", ""))
                .address(body.getOrDefault("address", ""))
                .taxCode(body.getOrDefault("taxCode", ""))
                .vendorType(vtype)
                .active(true).build());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId()); m.put("name", v.getName());
        m.put("contactPerson", v.getContactPerson()); m.put("contactPhone", v.getContactPhone());
        m.put("address", v.getAddress()); m.put("taxCode", v.getTaxCode());
        m.put("vendorType", v.getVendorType().name());
        return ApiResponse.ok(m);
    }

    /** PUT /api/factory/material-vendors/{id} */
    @PutMapping("/api/factory/material-vendors/{id}")
    public ApiResponse<Map<String, Object>> updateVendor(@PathVariable Long id,
                                                         @RequestBody Map<String, String> body) {
        MaterialVendor v = materialVendorRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy NCC: " + id));
        String name = body.getOrDefault("name", "").trim();
        if (!name.isBlank()) v.setName(name);
        v.setContactPerson(body.getOrDefault("contactPerson", v.getContactPerson()));
        v.setContactPhone(body.getOrDefault("contactPhone", v.getContactPhone()));
        v.setAddress(body.getOrDefault("address", v.getAddress()));
        v.setTaxCode(body.getOrDefault("taxCode", v.getTaxCode()));
        materialVendorRepository.save(v);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId()); m.put("name", v.getName());
        m.put("contactPerson", v.getContactPerson()); m.put("contactPhone", v.getContactPhone());
        m.put("address", v.getAddress()); m.put("taxCode", v.getTaxCode());
        return ApiResponse.ok(m);
    }

    /** DELETE /api/factory/material-vendors/{id} (soft delete) */
    @DeleteMapping("/api/factory/material-vendors/{id}")
    public ApiResponse<Void> deleteVendor(@PathVariable Long id) {
        materialVendorRepository.findById(id).ifPresent(v -> {
            v.setActive(false);
            materialVendorRepository.save(v);
        });
        return ApiResponse.ok(null);
    }

    // ── Batch Step Templates (preset bước sản xuất) ─────────────────────────

    /** GET /api/factory/step-templates — danh sách preset bước */
    @GetMapping("/api/factory/step-templates")
    public ApiResponse<java.util.List<java.util.Map<String, Object>>> listStepTemplates() {
        return ApiResponse.ok(stepTemplateRepo.findByIsActiveTrueOrderBySortOrderAscNameAsc()
                .stream().map(t -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("name", t.getName());
                    m.put("requiresQc", t.isRequiresQc());
                    m.put("sortOrder", t.getSortOrder());
                    return m;
                }).collect(java.util.stream.Collectors.toList()));
    }

    /** POST /api/factory/step-templates — tạo preset bước mới */
    @PostMapping("/api/factory/step-templates")
    public ApiResponse<java.util.Map<String, Object>> createStepTemplate(
            @RequestBody java.util.Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên bước không được để trống");
        }
        // Tránh trùng tên
        com.nhatnam.server.entity.BatchStepTemplate template =
                stepTemplateRepo.findByNameIgnoreCase(name.trim())
                        .orElseGet(() -> {
                            boolean requiresQc = Boolean.TRUE.equals(body.get("requiresQc"));
                            int maxOrder = stepTemplateRepo.findByIsActiveTrueOrderBySortOrderAscNameAsc()
                                    .stream().mapToInt(t -> t.getSortOrder()).max().orElse(0);
                            return stepTemplateRepo.save(
                                    com.nhatnam.server.entity.BatchStepTemplate.builder()
                                            .name(name.trim())
                                            .requiresQc(requiresQc)
                                            .sortOrder(maxOrder + 1)
                                            .build());
                        });
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", template.getId());
        result.put("name", template.getName());
        result.put("requiresQc", template.isRequiresQc());
        return ApiResponse.ok(result);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private List<String> uploadMultiple(List<MultipartFile> files,
                                        java.util.function.Function<MultipartFile, String> saver) {
        List<String> urls = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f != null && !f.isEmpty()) urls.add(saver.apply(f));
        }
        return urls;
    }

    private Long getUserId(Authentication auth) {
        try {
            if (auth.getPrincipal() instanceof com.nhatnam.server.entity.User u) return u.getId();
        } catch (Exception ignored) {}
        return null;
    }
}