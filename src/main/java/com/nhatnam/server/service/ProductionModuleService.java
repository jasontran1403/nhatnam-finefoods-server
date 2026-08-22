package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.production.ProductionModuleDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.entity.ProductionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Log4j2
public class ProductionModuleService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    private final ProductionPlanRepository planRepo;
    private final WorkOrderRepository workOrderRepo;
    private final WorkOrderPlanRepository workOrderPlanRepo;
    private final ProductionBatchRepository batchRepo;
    private final BatchStepRepository stepRepo;
    private final WorkOrderStepRepository woStepRepo;
    private final MachineRepository machineRepo;
    private final MaintenanceScheduleRepository maintenanceRepo;
    private final FactoryProductRepository productRepo;
    private final ProductionRecipeRepository recipeRepo;
    private final UserRepository userRepo;
    private final ProductionFactoryRepository factoryRepo;
    private final NotificationService notificationService;
    private final MachineWorkScheduleRepository workScheduleRepo;
    private final ObjectMapper objectMapper;
    private final com.nhatnam.server.repository.ProductionPlanProductRepository planProductRepo;
    // ── Mới: kho xưởng + lịch sử trừ kho theo lệnh sản xuất ────────────────────
    private final FactoryMaterialStockRepository stockRepo;
    private final com.nhatnam.server.repository.FactoryMaterialRepository factoryMaterialRepo;
    private final WorkOrderStockDeductionRepository deductionRepo;
    private final ProductionCostService productionCostService;
    // ── Mới: biến thể sản xuất + tính toán mẻ/nguyên liệu ──────────────────────
    private final ProductionBatchPlanningService planningService;
    private final WorkOrderPlanBatchMaterialRepository planBatchMaterialRepo;
    // ── Mới: kho thành phẩm (Issue #1 + #2) ─────────────────────────────────────
    private final FinishedGoodsService finishedGoodsService;
    // ── Mới: kho bán thành phẩm + kho scrap (quy trình đóng gói & hao hụt) ──────
    private final SemiFinishedGoodsService semiFinishedGoodsService;
    private final SemiFinishedTransferSourceBatchRepository transferSourceBatchRepo;

    // ─── ProductionPlan ───────────────────────────────────────────────────────

    public Page<ProductionPlanDto> listPlans(int page, int size, String status) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ProductionPlan> raw = (status != null && !status.isBlank())
                ? planRepo.findByStatusOrderByCreatedAtDesc(ProductionPlan.PlanStatus.valueOf(status), pageable)
                : planRepo.findAllByOrderByCreatedAtDesc(pageable);
        return raw.map(this::toPlanDto);
    }

    public ProductionPlanDto getPlan(Long id) {
        return toPlanDto(planRepo.findByIdWithProduct(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kế hoạch")));
    }

    public ProductionPlanDto createPlan(CreatePlanRequest req, String username) {
        User owner = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        // Ưu tiên factoryProductIds nếu có, fallback về factoryProductId đơn
        List<Long> productIds = (req.getFactoryProductIds() != null && !req.getFactoryProductIds().isEmpty())
                ? req.getFactoryProductIds()
                : (req.getFactoryProductId() != null ? List.of(req.getFactoryProductId()) : List.of());

        if (productIds.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn ít nhất 1 sản phẩm");
        }

        // Sản phẩm chính = sản phẩm đầu tiên
        FactoryProduct primaryProduct = productRepo.findById(productIds.get(0))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành phẩm: " + productIds.get(0)));

        String code = generatePlanCode();
        ProductionFactory planFactory = req.getProductionFactoryId() != null
                ? factoryRepo.findById(req.getProductionFactoryId()).orElse(null)
                : null;
        ProductionPlan plan = ProductionPlan.builder()
                .planCode(code)
                .title(req.getTitle())
                .factoryProduct(primaryProduct)
                .productName(primaryProduct.getName())
                .productionFactory(planFactory)
                .productionFactoryName(planFactory != null ? planFactory.getName() : null)
                .targetQty(req.getTargetQty())
                .outputUnit(primaryProduct.getUnit())
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .notes(req.getNotes())
                .status(ProductionPlan.PlanStatus.ACTIVE)
                .createdBy(owner)
                .createdByName(owner.getFullName())
                .build();

        ProductionPlan saved = planRepo.save(plan);

        // Lưu tất cả sản phẩm vào production_plan_product
        int order = 0;
        for (Long pid : productIds) {
            FactoryProduct fp = productRepo.findById(pid)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành phẩm: " + pid));
            planProductRepo.save(com.nhatnam.server.entity.ProductionPlanProduct.builder()
                    .productionPlan(saved)
                    .factoryProduct(fp)
                    .sortOrder(order++)
                    .build());
        }

        // WS notify SUPER_FACTORY_WORKER — kế hoạch mới cần được lập lệnh sản xuất
        String planMsg = String.format("Kế hoạch %s — %s vừa được tạo (Mục tiêu: %s %s). Vui lòng tạo lệnh sản xuất.",
                saved.getPlanCode(), saved.getTitle(),
                saved.getTargetQty(), saved.getOutputUnit());
        String planPayload = String.format(
                "{\"planId\":%d,\"planCode\":\"%s\",\"title\":\"%s\"}",
                saved.getId(), saved.getPlanCode(), saved.getTitle());
        // Gửi tới từng user có chứa role SUPER_FACTORY_WORKER trong danh sách roles
        // (kể cả khi role đang active của họ không phải SUPER_FACTORY_WORKER)
        List<User> superFactoryWorkers = userRepo.findByRolesContaining(com.nhatnam.server.enumtype.Role.SUPER_FACTORY_WORKER);

        // Người đã nhận thông báo — dùng để không gửi trùng cho cùng một người khi họ
        // vừa là SUPER_FACTORY_WORKER vừa được gán quản lý xưởng của kế hoạch này.
        java.util.Set<Long> notified = new java.util.HashSet<>();

        for (User u : superFactoryWorkers) {
            notificationService.sendToUser(u, "SUPER_FACTORY_WORKER", "PRODUCTION_PLAN_CREATED", planMsg, planPayload);
            notified.add(u.getId());
        }

        // TRƯỞNG XƯỞNG / TRỢ LÝ ĐƯỢC GÁN VÀO XƯỞNG CỦA KẾ HOẠCH.
        //
        // Gửi riêng cho họ thay vì chỉ dựa vào nhóm SUPER_FACTORY_WORKER ở trên: một
        // người quản lý xưởng có thể không mang role đó, và ngược lại nhóm role kia
        // gồm cả người phụ trách xưởng khác — không liên quan tới kế hoạch này.
        if (planFactory != null && planFactory.getManagers() != null) {
            String factoryMsg = String.format(
                    "Xưởng %s vừa nhận kế hoạch %s — %s (Mục tiêu: %s %s).",
                    planFactory.getName(), saved.getPlanCode(), saved.getTitle(),
                    saved.getTargetQty(), saved.getOutputUnit());

            for (User m : planFactory.getManagers()) {
                if (m == null || !notified.add(m.getId())) continue;
                String activeRole = m.getRole() != null ? m.getRole().name() : "FACTORY_WORKER";
                notificationService.sendToUser(m, activeRole,
                        "PRODUCTION_PLAN_ASSIGNED", factoryMsg, planPayload);
            }
        }

        return toPlanDto(saved);
    }

    public ProductionPlanDto updatePlanStatus(Long id, String status) {
        ProductionPlan plan = planRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kế hoạch"));
        plan.setStatus(ProductionPlan.PlanStatus.valueOf(status));
        // Nếu huỷ kế hoạch → huỷ tất cả lệnh sản xuất thuộc kế hoạch
        if ("CANCELLED".equals(status)) {
            List<WorkOrder> orders = workOrderRepo.findByProductionPlan_IdOrderByCreatedAtDesc(plan.getId());
            for (WorkOrder wo : orders) {
                if (wo.getStatus() != WorkOrder.WorkOrderStatus.COMPLETED) {
                    wo.setStatus(WorkOrder.WorkOrderStatus.CANCELLED);
                    wo.setActualEndDate(System.currentTimeMillis());
                    workOrderRepo.save(wo);
                }
            }
        }
        return toPlanDto(planRepo.save(plan));
    }

    // ─── WorkOrder ────────────────────────────────────────────────────────────

    public List<WorkOrderDto> listWorkOrdersByPlan(Long planId) {
        return workOrderRepo.findByProductionPlan_IdOrderByCreatedAtDesc(planId)
                .stream().map(this::toWorkOrderDto).collect(Collectors.toList());
    }

    public Page<WorkOrderDto> listWorkOrders(int page, int size, String status) {
        Pageable pageable = PageRequest.of(page, size);
        Page<WorkOrder> raw = (status != null && !status.isBlank())
                ? workOrderRepo.findByStatusOrderByCreatedAtDesc(WorkOrder.WorkOrderStatus.valueOf(status), pageable)
                : workOrderRepo.findAllByOrderByCreatedAtDesc(pageable);
        return raw.map(this::toWorkOrderDto);
    }

    public WorkOrderDetailDto getWorkOrderDetail(Long id) {
        WorkOrder wo = workOrderRepo.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh"));

        List<ProductionBatch> batches = batchRepo.findByWorkOrder_IdOrderByBatchNumberAsc(id);
        WorkOrderPlan plan = workOrderPlanRepo.findByWorkOrderIdWithMaterials(id).orElse(null);

        // Công đoạn cấp lệnh (bước chung + bước riêng)
        List<WorkOrderStep> allSteps = woStepRepo.findByWorkOrder_IdOrderByStageSequenceAscRunNumberAsc(id);
        List<WorkOrderStageDto> stages = buildStageDtos(allSteps);

        // Công đoạn đang thực hiện (để hiển thị nhanh ở đầu trang)
        String currentStep = allSteps.stream()
                .filter(s -> s.getStatus() == WorkOrderStep.Status.IN_PROGRESS)
                .findFirst()
                .map(WorkOrderStep::getStageName)
                .orElseGet(() -> allSteps.stream()
                        .filter(s -> s.getStatus() == WorkOrderStep.Status.PENDING)
                        .findFirst()
                        .map(WorkOrderStep::getStageName)
                        .orElse(null));

        ProductionBatch inProgressBatch = batches.stream()
                .filter(b -> b.getStatus() == ProductionBatch.BatchStatus.IN_PROGRESS)
                .findFirst().orElse(null);
        int currentBatchNum = inProgressBatch != null && inProgressBatch.getBatchNumber() != null
                ? inProgressBatch.getBatchNumber() : 0;

        BigDecimal progress = calcProgressPct(wo.getAccumulatedQty(), wo.getPlannedQty());

        return WorkOrderDetailDto.builder()
                .workOrder(toWorkOrderDto(wo))
                .plan(plan != null ? toPlanDto(plan) : null)
                .batches(batches.stream().map(this::toBatchDto).collect(Collectors.toList()))
                .stages(stages)
                .progressPct(progress)
                .currentBatchNumber(currentBatchNum)
                .currentStepName(currentStep)
                .hasNextBatch(false)   // mẻ được tạo sẵn khi bắt đầu lệnh — không còn "bắt đầu mẻ tiếp"
                .nextBatchNumber(null)
                .packagingLoss(calcWorkOrderLoss(batches))
                .build();
    }

    /** Gom các WorkOrderStep thành danh sách công đoạn (mỗi công đoạn nhiều lần chạy). */
    private List<WorkOrderStageDto> buildStageDtos(List<WorkOrderStep> allSteps) {
        Map<Integer, List<WorkOrderStep>> byStage = new java.util.TreeMap<>();
        for (WorkOrderStep s : allSteps) {
            byStage.computeIfAbsent(s.getStageSequence(), k -> new ArrayList<>()).add(s);
        }
        List<WorkOrderStageDto> stages = new ArrayList<>();
        for (Map.Entry<Integer, List<WorkOrderStep>> e : byStage.entrySet()) {
            List<WorkOrderStep> runs = e.getValue();
            WorkOrderStep first = runs.get(0);
            int completed = (int) runs.stream().filter(s -> s.getStatus() == WorkOrderStep.Status.COMPLETED).count();
            boolean anyRunning = runs.stream().anyMatch(s -> s.getStatus() == WorkOrderStep.Status.IN_PROGRESS);
            String status = completed == runs.size() ? "COMPLETED" : (anyRunning || completed > 0 ? "IN_PROGRESS" : "PENDING");
            BigDecimal totalQty = runs.stream()
                    .map(s -> s.getRunQty() != null ? s.getRunQty() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            stages.add(WorkOrderStageDto.builder()
                    .stageSequence(first.getStageSequence())
                    .stageName(first.getStageName())
                    .shared(first.isShared())
                    .controlType(first.getControlType() != null ? first.getControlType().name() : "NONE")
                    .machineId(first.getMachine() != null ? first.getMachine().getId() : null)
                    .machineName(first.getMachineName())
                    .durationMinutes(first.getDurationMinutes())
                    .totalRuns(runs.size())
                    .completedRuns(completed)
                    .status(status)
                    .totalQty(totalQty)
                    .runs(runs.stream().map(r -> toStageRunDto(r, allSteps)).collect(Collectors.toList()))
                    .build());
        }
        return stages;
    }

    private WorkOrderStepRunDto toStageRunDto(WorkOrderStep s, List<WorkOrderStep> all) {
        boolean canStart = s.getStatus() == WorkOrderStep.Status.PENDING && canStartRun(s, all);
        return WorkOrderStepRunDto.builder()
                .id(s.getId())
                .runNumber(s.getRunNumber())
                .totalRuns(s.getTotalRuns())
                .runQty(s.getRunQty())
                .batchNumber(s.getBatchNumber())
                .status(s.getStatus().name())
                .canStart(canStart)
                .requiresQc(s.isRequiresQc())
                .controlType(s.getControlType() != null ? s.getControlType().name() : "NONE")
                .machineId(s.getMachine() != null ? s.getMachine().getId() : null)
                .machineName(s.getMachineName())
                .durationMinutes(s.getDurationMinutes())
                .startedByName(s.getStartedByName())
                .startedAt(s.getStartedAt())
                .attachments(fromJsonList(s.getAttachments()))
                .notes(s.getNotes())
                .damagedQty(s.getDamagedQty())
                .completedByName(s.getCompletedByName())
                .completedAt(s.getCompletedAt())
                .build();
    }

    /**
     * Tính hao hụt đóng gói tổng hợp cho 1 lệnh sản xuất — CHỈ trả về giá trị
     * (non-null) khi TẤT CẢ mẻ của lệnh đã COMPLETED (không còn mẻ nào PENDING
     * hoặc IN_PROGRESS). Nếu lệnh không có mẻ nào, hoặc còn mẻ chưa xong, trả null.
     *
     * Cách tính: với mỗi mẻ COMPLETED, tìm các dòng nguồn (SemiFinishedTransferSourceBatch)
     * đã được chuyển kho VÀ đã được kế toán kho xác nhận nhận (transferNote.status=RECEIVED).
     * Mỗi dòng nguồn ghi rõ "lấy bao nhiêu kg từ batch này" trong 1 dòng phiếu chuyển — dòng
     * phiếu đó có actualReceivedWeight là TỔNG kg thực cân của CẢ DÒNG (có thể gộp nhiều batch
     * từ nhiều lệnh sản xuất khác nhau nếu cùng sản phẩm). Để tính đúng phần hao hụt riêng cho
     * lệnh này, ta PHÂN BỔ theo tỷ trọng:
     *   kg thực nhận phân bổ cho batch X = actualReceivedWeight(dòng) × (kg lấy từ X / transferredQty(dòng))
     */
    private WorkOrderLossDto calcWorkOrderLoss(List<ProductionBatch> batches) {
        if (batches == null || batches.isEmpty()) return null;
        boolean allCompleted = batches.stream().allMatch(b -> b.getStatus() == ProductionBatch.BatchStatus.COMPLETED
                || b.getStatus() == ProductionBatch.BatchStatus.CANCELLED);
        boolean anyCompleted = batches.stream().anyMatch(b -> b.getStatus() == ProductionBatch.BatchStatus.COMPLETED);
        if (!allCompleted || !anyCompleted) return null; // còn mẻ chưa xong → không hiển thị

        List<ProductionBatch> completedBatches = batches.stream()
                .filter(b -> b.getStatus() == ProductionBatch.BatchStatus.COMPLETED)
                .collect(Collectors.toList());

        BigDecimal totalActualOutput = completedBatches.stream()
                .map(b -> b.getActualOutputQty() != null ? b.getActualOutputQty() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalActualOutput.compareTo(BigDecimal.ZERO) <= 0) return null;

        List<Long> batchIds = completedBatches.stream().map(ProductionBatch::getId).collect(Collectors.toList());
        List<SemiFinishedTransferSourceBatch> sourceRows = transferSourceBatchRepo.findReceivedByBatchIds(batchIds);

        BigDecimal totalReceivedWeight = BigDecimal.ZERO;
        BigDecimal totalPackagedQty = BigDecimal.ZERO;
        BigDecimal totalTransferredReconciled = BigDecimal.ZERO; // kg đã thực sự đối soát xong (phiếu RECEIVED)
        String packagedUnit = null;

        for (SemiFinishedTransferSourceBatch sb : sourceRows) {
            SemiFinishedTransferNoteLine line = sb.getTransferLine();
            if (line.getActualReceivedWeight() == null || line.getTransferredQty() == null
                    || line.getTransferredQty().compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal ratio = sb.getQuantity().divide(line.getTransferredQty(), 10, RoundingMode.HALF_UP);
            BigDecimal allocatedReceived = line.getActualReceivedWeight().multiply(ratio);
            BigDecimal allocatedPackaged = line.getPackagedQty() != null
                    ? line.getPackagedQty().multiply(ratio) : BigDecimal.ZERO;

            totalReceivedWeight = totalReceivedWeight.add(allocatedReceived);
            totalPackagedQty = totalPackagedQty.add(allocatedPackaged);
            totalTransferredReconciled = totalTransferredReconciled.add(sb.getQuantity());
            if (packagedUnit == null) packagedUnit = line.getPackagedUnit();
        }

        // Chưa có dòng nào được đối soát (chưa chuyển kho hoặc chưa xác nhận nhận) → chưa đủ data
        if (totalReceivedWeight.compareTo(BigDecimal.ZERO) <= 0) return null;

        // Chỉ coi là "đã đối soát đủ" khi tổng kg đã đối soát ≈ tổng sản lượng thực tế
        // (cho phép sai số nhỏ do làm tròn số học, dưới 0.01 kg)
        boolean fullyReconciled = totalTransferredReconciled.subtract(totalActualOutput).abs()
                .compareTo(new BigDecimal("0.01")) <= 0;
        if (!fullyReconciled) return null; // còn phần chưa chuyển/chưa xác nhận → chưa đủ dữ liệu để kết luận

        BigDecimal loss = totalActualOutput.subtract(totalReceivedWeight);
        BigDecimal lossQty = loss.compareTo(BigDecimal.ZERO) > 0 ? loss : BigDecimal.ZERO;
        BigDecimal lossPct = totalActualOutput.compareTo(BigDecimal.ZERO) > 0
                ? lossQty.multiply(new BigDecimal("100")).divide(totalActualOutput, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return WorkOrderLossDto.builder()
                .totalActualOutputQty(totalActualOutput)
                .totalActualReceivedWeight(totalReceivedWeight)
                .totalPackagedQty(totalPackagedQty)
                .packagedUnit(packagedUnit != null ? packagedUnit : "túi")
                .lossQty(lossQty)
                .lossPct(lossPct)
                .fullyReconciled(true)
                .build();
    }

    public WorkOrderDto createWorkOrder(CreateWorkOrderRequest req, String username) {
        User owner = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        ProductionPlan plan = planRepo.findById(req.getProductionPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kế hoạch"));
        FactoryProduct fp = productRepo.findById(req.getFactoryProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành phẩm"));
        User assignee = req.getAssignedToId() != null
                ? userRepo.findById(req.getAssignedToId()).orElse(null) : null;

        long scheduledStart = req.getScheduledStartDate() != null
                ? req.getScheduledStartDate() : System.currentTimeMillis();
        long planDeadline = scheduledStart + ONE_DAY_MS;

        // ── Validate: scheduledStartDate không được trước ngày bắt đầu kế hoạch ──
        if (plan.getStartDate() != null && scheduledStart < plan.getStartDate()) {
            throw new IllegalArgumentException(
                    "Ngày bắt đầu lệnh sản xuất không được trước ngày bắt đầu kế hoạch ("
                            + formatDate(plan.getStartDate()) + ")");
        }

        // ── Validate: tổng sản lượng các lệnh + lệnh mới có vượt kế hoạch không ──
        // (chỉ cảnh báo nếu forceCreate=false — frontend gửi flag confirm)
        if (!Boolean.TRUE.equals(req.getForceCreate())) {
            List<WorkOrder> existingOrders = workOrderRepo.findByProductionPlan_IdOrderByCreatedAtDesc(plan.getId());
            // Tính "đã chiếm" theo cùng logic toPlanDto:
            // COMPLETED → dùng actualQty, IN_PROGRESS/PLANNED/SCHEDULED → dùng plannedQty, CANCELLED → bỏ
            BigDecimal totalExisting = existingOrders.stream()
                    .filter(w -> w.getStatus() != WorkOrder.WorkOrderStatus.CANCELLED)
                    .map(w -> {
                        if (w.getStatus() == WorkOrder.WorkOrderStatus.COMPLETED) {
                            return w.getAccumulatedQty() != null ? w.getAccumulatedQty() : BigDecimal.ZERO;
                        }
                        return w.getPlannedQty() != null ? w.getPlannedQty() : BigDecimal.ZERO;
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal newTotal = totalExisting.add(req.getPlannedQty() != null ? req.getPlannedQty() : BigDecimal.ZERO);
            if (plan.getTargetQty() != null && newTotal.compareTo(plan.getTargetQty()) > 0) {
                throw new IllegalArgumentException(
                        "OVER_PLAN_QTY:" + newTotal + ":" + plan.getTargetQty());
            }
        }

        // Nếu scheduledStart là tương lai → SCHEDULED, ngược lại → PENDING_PLAN
        // Nếu scheduledMode=true thì luôn dùng SCHEDULED cho đến khi đến ngày
        boolean isScheduledMode = Boolean.TRUE.equals(req.getScheduledMode());
        WorkOrder.WorkOrderStatus initStatus =
                (isScheduledMode || scheduledStart > System.currentTimeMillis())
                        ? WorkOrder.WorkOrderStatus.SCHEDULED
                        : WorkOrder.WorkOrderStatus.PENDING_PLAN;

        // Resolve factory
        ProductionFactory factory = req.getProductionFactoryId() != null
                ? factoryRepo.findById(req.getProductionFactoryId()).orElse(null) : null;

        WorkOrder wo = WorkOrder.builder()
                .workOrderCode(generateWorkOrderCode())
                .productionPlan(plan)
                .factoryProduct(fp)
                .productName(fp.getName())
                .outputUnit(fp.getUnit())
                .plannedQty(req.getPlannedQty())
                .accumulatedQty(BigDecimal.ZERO)
                .scheduledStartDate(scheduledStart)
                .planDeadline(planDeadline)
                .plannedEndDate(req.getPlannedEndDate())
                .notes(req.getNotes())
                .productionFactory(factory)
                .productionFactoryName(factory != null ? factory.getName() : null)
                .scheduledMode(isScheduledMode)
                .createdBy(owner)
                .createdByName(owner.getFullName())
                .status(initStatus)
                .build();

        WorkOrder saved = workOrderRepo.save(wo);

        // WS notify FACTORY_WORKER
        String factoryStr = factory != null ? " | Xưởng: " + factory.getName() : "";
        String msg = String.format("Lệnh sản xuất %s — %s vừa được tạo (KH: %s %s, bắt đầu: %s%s)",
                saved.getWorkOrderCode(), saved.getProductName(),
                saved.getPlannedQty(), saved.getOutputUnit(),
                formatDate(saved.getScheduledStartDate()), factoryStr);
        String payload = String.format(
                "{\"workOrderId\":%d,\"workOrderCode\":\"%s\",\"productName\":\"%s\"}",
                saved.getId(), saved.getWorkOrderCode(), saved.getProductName());
        notificationService.sendToRole("FACTORY_WORKER", "WORK_ORDER_CREATED", msg, payload);

        return toWorkOrderDto(saved);
    }

    // ─── WorkOrderPlan (Factory lập phương án) ────────────────────────────────

    /**
     * Lệnh active cho factory — lọc theo factoryId nếu có.
     * Không còn lọc theo userId.
     */
    public List<WorkOrderDto> listActiveOrdersForFactory(Long factoryId) {
        long thirtyDaysAgo = System.currentTimeMillis() - 30L * ONE_DAY_MS;
        List<WorkOrder> list = workOrderRepo.findActiveOrdersByFactory(factoryId, thirtyDaysAgo);
        return list.stream().map(this::toWorkOrderDto).collect(Collectors.toList());
    }

    /** Danh sách xưởng mà user này là manager */
    public List<ProductionFactoryDto> listMyFactories(Long userId) {
        List<ProductionFactory> factories = userId != null
                ? factoryRepo.findByManagerId(userId)
                : factoryRepo.findByStatusOrderByNameAsc(ProductionFactory.FactoryStatus.ACTIVE);
        return factories.stream().map(this::toFactoryDto).collect(Collectors.toList());
    }

    // ─── Factory CRUD ─────────────────────────────────────────────────────────

    public List<ProductionFactoryDto> listFactories() {
        return factoryRepo.findAllByOrderByNameAsc().stream()
                .map(this::toFactoryDto).collect(Collectors.toList());
    }

    public ProductionFactoryDto createFactory(CreateFactoryRequest req, String username) {
        User creator = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        ProductionFactory factory = ProductionFactory.builder()
                .name(req.getName())
                .address(req.getAddress())
                .description(req.getDescription())
                .status(ProductionFactory.FactoryStatus.ACTIVE)
                .createdByName(creator.getFullName())
                .build();

        if (req.getManagerIds() != null && !req.getManagerIds().isEmpty()) {
            List<User> managers = userRepo.findAllById(req.getManagerIds());
            factory.setManagers(managers);
        }
        ProductionFactory saved = factoryRepo.save(factory);

        // ── Đồng bộ DANH SÁCH nguyên liệu sang xưởng mới ───────────────────────
        // Yêu cầu: xưởng mới có đủ tất cả nguyên liệu đang hoạt động (giống các xưởng
        // hiện có). CHỈ đồng bộ danh sách nguyên liệu:
        //   - gắn xưởng mới vào từng FactoryMaterial (bảng nối factory_material_factories),
        //   - tạo bản ghi tồn kho = 0 cho xưởng mới để nguyên liệu hiển thị ngay ở kho xưởng đó.
        // KHÔNG copy lô / số lượng tồn kho thực tế từ xưởng khác.
        List<com.nhatnam.server.entity.FactoryMaterial> materials =
                factoryMaterialRepo.findByIsActiveTrueOrderByNameAsc();
        if (!materials.isEmpty()) {
            long now = System.currentTimeMillis();
            for (com.nhatnam.server.entity.FactoryMaterial mat : materials) {
                if (mat.getFactories() == null) mat.setFactories(new java.util.ArrayList<>());
                boolean already = mat.getFactories().stream()
                        .anyMatch(f -> f.getId() != null && f.getId().equals(saved.getId()));
                if (!already) mat.getFactories().add(saved);
                stockRepo.save(_zeroFactoryMaterialStock(mat, saved, now));
            }
            factoryMaterialRepo.saveAll(materials);
        }

        return toFactoryDto(saved);
    }

    /** Bản ghi tồn kho = 0 cho 1 nguyên liệu ở 1 xưởng (để NL hiển thị ngay dù chưa nhập hàng). */
    private FactoryMaterialStock _zeroFactoryMaterialStock(
            com.nhatnam.server.entity.FactoryMaterial m, ProductionFactory f, long now) {
        FactoryMaterialStock fms = new FactoryMaterialStock();
        fms.setCreatedAt(now);
        fms.setUpdatedAt(now);
        fms.setQuantity(BigDecimal.ZERO);
        fms.setInitialQuantity(BigDecimal.ZERO);
        fms.setUnit(m.getUnit());
        fms.setMaterialName(m.getName());
        fms.setIsActive(true);
        fms.setProductionFactory(f);
        return fms;
    }

    public ProductionFactoryDto updateFactoryManagers(Long factoryId, UpdateFactoryManagersRequest req) {
        ProductionFactory factory = factoryRepo.findById(factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy xưởng"));
        List<User> managers = req.getManagerIds() != null
                ? userRepo.findAllById(req.getManagerIds()) : List.of();
        factory.setManagers(managers);
        return toFactoryDto(factoryRepo.save(factory));
    }

    public ProductionFactoryDto toggleFactory(Long id, boolean active) {
        ProductionFactory factory = factoryRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy xưởng"));
        factory.setStatus(active ? ProductionFactory.FactoryStatus.ACTIVE : ProductionFactory.FactoryStatus.INACTIVE);
        return toFactoryDto(factoryRepo.save(factory));
    }

    /**
     * Preview phương án theo biến thể sản xuất — KHÔNG lưu DB, chỉ tính toán
     * để hiển thị cho nhân viên xem trước (nguyên liệu từng mẻ + tổng, các bước)
     * trước khi quyết định submit.
     */
    public PlanPreviewDto previewPlanByRecipe(Long workOrderId, Long recipeId, BigDecimal requestedQty) {
        WorkOrder wo = workOrderRepo.findById(workOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh"));

        // Load recipe với items trước
        ProductionRecipe recipe = recipeRepo.findByIdWithItems(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy biến thể sản xuất"));

        // Load steps sau (Hibernate sẽ tự merge vào đối tượng đã load)
        recipeRepo.findByIdWithSteps(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy biến thể sản xuất"));

        validateRecipeBelongsToWorkOrderProduct(wo, recipe);

        ProductionBatchPlanningService.PlanCalculation calc = planningService.calculate(recipe, requestedQty);
        return toPlanPreviewDto(recipe, calc);
    }

    /**
     * Lập phương án sản xuất theo Biến thể sản xuất (THAY THẾ cách nhập tay cũ).
     * Nhân viên chỉ cần chọn 1 biến thể (đúng FactoryProduct của lệnh) + nhập
     * sản lượng cần sản xuất — số mẻ, sản lượng từng mẻ, nguyên liệu từng mẻ
     * và tổng nguyên liệu cho cả lệnh được TÍNH TỰ ĐỘNG, không cho sửa tay.
     */
    @Transactional
    public WorkOrderPlanDto submitPlanByRecipe(Long workOrderId, SubmitPlanByRecipeRequest req, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        WorkOrder wo = workOrderRepo.findById(workOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh"));

        if (wo.getStatus() != WorkOrder.WorkOrderStatus.PENDING_PLAN
                && wo.getStatus() != WorkOrder.WorkOrderStatus.SCHEDULED) {
            throw new IllegalStateException("Lệnh không ở trạng thái chờ lập phương án");
        }

        // Load recipe với items trước
        ProductionRecipe recipe = recipeRepo.findByIdWithItems(req.getRecipeId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy biến thể sản xuất"));

        // Load steps sau (Hibernate sẽ merge vào đối tượng đã load)
        recipeRepo.findByIdWithSteps(req.getRecipeId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy biến thể sản xuất"));

        validateRecipeBelongsToWorkOrderProduct(wo, recipe);

        ProductionBatchPlanningService.PlanCalculation calc = planningService.calculate(recipe, req.getRequestedQty());

        // Xoá phương án cũ nếu có (kèm batchMaterials do cascade)
        workOrderPlanRepo.findByWorkOrder_Id(workOrderId).ifPresent(workOrderPlanRepo::delete);

        // Snapshot tên các bước + chi tiết bước (để startBatch tạo BatchStep đúng như lúc lập phương án)
        List<String> stepNames = recipe.getSteps().stream()
                .sorted(Comparator.comparingInt(s -> s.getSortOrder() == null ? 0 : s.getSortOrder()))
                .map(ProductionRecipeStep::getStepName)
                .collect(Collectors.toList());
        List<Map<String, Object>> stepDetails = recipe.getSteps().stream()
                .sorted(Comparator.comparingInt(s -> s.getSortOrder() == null ? 0 : s.getSortOrder()))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s.getStepName());
                    m.put("requiresQC", s.isRequiresQc());
                    ProductionRecipeStep.ControlType ct = s.getControlType() != null ? s.getControlType()
                            : (s.isRequiresQc() ? ProductionRecipeStep.ControlType.PHOTO_WEIGHT : ProductionRecipeStep.ControlType.NONE);
                    m.put("controlType", ct.name());
                    m.put("durationMinutes", s.getDurationMinutes());
                    m.put("shared", s.isShared());
                    if (s.isShared() && s.getCapacityPerRun() != null) {
                        m.put("capacityPerRun", s.getCapacityPerRun());
                    }
                    if (s.getMachine() != null) m.put("machineId", s.getMachine().getId());
                    return m;
                })
                .collect(Collectors.toList());

        List<BigDecimal> batchQtyList = calc.getBatches().stream()
                .map(ProductionBatchPlanningService.BatchPlan::getOutputQty)
                .collect(Collectors.toList());

        WorkOrderPlan plan = WorkOrderPlan.builder()
                .workOrder(wo)
                .recipe(recipe)
                .requestedQty(req.getRequestedQty())
                .totalBatches(calc.getTotalBatches())
                .batchQtyPerRun(recipe.getStandardOutputQty())
                .batchQtyPerRunList(toJson(batchQtyList))
                .plannedStaff(req.getPlannedStaff())
                .batchSteps(toJson(stepNames))
                .batchStepDetails(toJson(stepDetails))
                .notes(req.getNotes())
                .submittedBy(worker)
                .submittedByName(worker.getFullName())
                .submittedAt(System.currentTimeMillis())
                .build();

        // Tổng nguyên liệu cho cả lệnh (dùng để trừ kho FIFO khi startWorkOrder)
        int order = 0;
        for (ProductionBatchPlanningService.MaterialLine line : calc.getTotalMaterials()) {
            plan.getMaterials().add(WorkOrderPlanMaterial.builder()
                    .plan(plan)
                    .materialName(line.getMaterialName())
                    .quantity(line.getQty())
                    .unit(line.getUnit())
                    .sortOrder(order++)
                    .build());
        }

        WorkOrderPlan saved = workOrderPlanRepo.save(plan);

        // Lưu nguyên liệu riêng từng mẻ (preview chi tiết theo mẻ)
        for (ProductionBatchPlanningService.BatchPlan bp : calc.getBatches()) {
            int sort = 0;
            for (ProductionBatchPlanningService.MaterialLine line : bp.getMaterials()) {
                FactoryMaterial mat = recipe.getItems().stream()
                        .map(ProductionRecipeItem::getFactoryMaterial)
                        .filter(m -> m.getId().equals(line.getFactoryMaterialId()))
                        .findFirst()
                        .orElse(null);
                planBatchMaterialRepo.save(WorkOrderPlanBatchMaterial.builder()
                        .plan(saved)
                        .batchNumber(bp.getBatchNumber())
                        .factoryMaterial(mat)
                        .materialName(line.getMaterialName())
                        .qty(line.getQty())
                        .unit(line.getUnit())
                        .sortOrder(sort++)
                        .build());
            }
        }

        // Update WO status → PLANNED
        wo.setStatus(WorkOrder.WorkOrderStatus.PLANNED);
        workOrderRepo.save(wo);

        // Notify OWNER + SUPER_ACCOUNTANT
        String msg = String.format("Phương án lệnh %s đã được lập bởi %s — biến thể \"%s\", %d mẻ, sản lượng %s %s",
                wo.getWorkOrderCode(), worker.getFullName(), recipe.getName(),
                calc.getTotalBatches(), req.getRequestedQty().stripTrailingZeros().toPlainString(), recipe.getOutputUnit());
        String payload = String.format(
                "{\"workOrderId\":%d,\"workOrderCode\":\"%s\",\"recipeId\":%d,\"totalBatches\":%d}",
                wo.getId(), wo.getWorkOrderCode(), recipe.getId(), calc.getTotalBatches());
        notificationService.sendToRole("OWNER", "WORK_ORDER_PLAN_SUBMITTED", msg, payload);
        notificationService.sendToRole("SUPER_ACCOUNTANT", "WORK_ORDER_PLAN_SUBMITTED", msg, payload);

        return toPlanDto(workOrderPlanRepo.findByWorkOrderIdWithMaterials(wo.getId()).orElse(saved));
    }

    /** Biến thể phải thuộc đúng FactoryProduct của WorkOrder — khoá cứng, không cho chọn chéo sản phẩm */
    private void validateRecipeBelongsToWorkOrderProduct(WorkOrder wo, ProductionRecipe recipe) {
        Long woProductId = wo.getFactoryProduct() != null ? wo.getFactoryProduct().getId() : null;
        Long recipeProductId = recipe.getFactoryProduct() != null ? recipe.getFactoryProduct().getId() : null;
        if (woProductId == null || recipeProductId == null || !woProductId.equals(recipeProductId)) {
            throw new IllegalArgumentException(
                    "Biến thể \"" + recipe.getName() + "\" không thuộc thành phẩm của lệnh sản xuất này");
        }
        if (!Boolean.TRUE.equals(recipe.getIsActive())) {
            throw new IllegalArgumentException("Biến thể \"" + recipe.getName() + "\" đã bị tắt, không thể sử dụng");
        }
    }

    private PlanPreviewDto toPlanPreviewDto(ProductionRecipe recipe, ProductionBatchPlanningService.PlanCalculation calc) {
        List<BatchPlanPreviewDto> batches = calc.getBatches().stream()
                .map(bp -> BatchPlanPreviewDto.builder()
                        .batchNumber(bp.getBatchNumber())
                        .outputQty(bp.getOutputQty())
                        .materials(bp.getMaterials().stream()
                                .map(l -> MaterialLineDto.builder()
                                        .factoryMaterialId(l.getFactoryMaterialId())
                                        .materialName(l.getMaterialName())
                                        .unit(l.getUnit())
                                        .qty(l.getQty())
                                        .build())
                                .collect(Collectors.toList()))
                        .build())
                .collect(Collectors.toList());

        List<MaterialLineDto> totals = calc.getTotalMaterials().stream()
                .map(l -> MaterialLineDto.builder()
                        .factoryMaterialId(l.getFactoryMaterialId())
                        .materialName(l.getMaterialName())
                        .unit(l.getUnit())
                        .qty(l.getQty())
                        .build())
                .collect(Collectors.toList());

        List<RecipeStepPreviewDto> steps = recipe.getSteps().stream()
                .sorted(Comparator.comparingInt(s -> s.getSortOrder() == null ? 0 : s.getSortOrder()))
                .map(s -> RecipeStepPreviewDto.builder()
                        .stepName(s.getStepName())
                        .requiresQc(s.isRequiresQc())
                        .controlType((s.getControlType() != null ? s.getControlType()
                                : (s.isRequiresQc() ? ProductionRecipeStep.ControlType.PHOTO_WEIGHT : ProductionRecipeStep.ControlType.NONE)).name())
                        .durationMinutes(s.getDurationMinutes())
                        .machineId(s.getMachine() != null ? s.getMachine().getId() : null)
                        .machineName(s.getMachineName())
                        .shared(s.isShared())
                        .capacityPerRun(s.getCapacityPerRun())
                        .build())
                .collect(Collectors.toList());

        return PlanPreviewDto.builder()
                .recipeId(recipe.getId())
                .recipeName(recipe.getName())
                .standardOutputQty(recipe.getStandardOutputQty())
                .outputUnit(recipe.getOutputUnit())
                .requestedQty(calc.getRequestedQty())
                .totalBatches(calc.getTotalBatches())
                .batches(batches)
                .totalMaterials(totals)
                .steps(steps)
                .build();
    }

    /**
     * Bắt đầu lệnh sản xuất:
     *  - Chuyển trạng thái PLANNED → IN_PROGRESS
     *  - Trừ kho xưởng theo FIFO (ưu tiên lô gần hết hạn sử dụng nhất trừ trước)
     *    dựa trên nguyên liệu trong phương án (WorkOrderPlanMaterial).
     *    Nếu kho không đủ một loại NVL nào → ném lỗi, KHÔNG bắt đầu được lệnh.
     */
    public WorkOrderDto startWorkOrder(Long workOrderId, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        WorkOrder wo = workOrderRepo.findByIdWithPlan(workOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh"));
        if (wo.getStatus() != WorkOrder.WorkOrderStatus.PLANNED) {
            throw new IllegalStateException("Lệnh chưa có phương án hoặc không thể bắt đầu");
        }
        // Kiểm tra scheduledStartDate
        if (wo.getScheduledStartDate() != null && wo.getScheduledStartDate() > System.currentTimeMillis()) {
            throw new IllegalStateException("Chưa đến ngày được phép bắt đầu: " + formatDate(wo.getScheduledStartDate()));
        }

        WorkOrderPlan plan = wo.getWorkOrderPlan();
        if (plan == null || plan.getRecipe() == null) {
            throw new IllegalStateException("Lệnh chưa có phương án sản xuất với biến thể hợp lệ");
        }

        // ── Trừ kho xưởng theo FIFO (theo hạn sử dụng gần nhất → xa nhất) ─────
        if (plan.getMaterials() != null && !plan.getMaterials().isEmpty()) {
            deductStockFifo(wo, plan.getMaterials());
        }

        wo.setStatus(WorkOrder.WorkOrderStatus.IN_PROGRESS);
        wo.setActualStartDate(System.currentTimeMillis());
        workOrderRepo.save(wo);

        // ── Tạo sẵn TẤT CẢ các mẻ (lô 30kg) + TẤT CẢ công đoạn của lệnh ─────────
        // (Không còn "bắt đầu mẻ" thủ công — worker chỉ thực hiện các công đoạn theo thứ tự,
        //  rồi ghi sản lượng/HSD cho từng lô khi các công đoạn phủ lô đó đã xong.)
        createBatchesAndStages(wo, plan, worker);

        return toWorkOrderDto(workOrderRepo.findById(wo.getId()).orElse(wo));
    }

    /**
     * Sinh sẵn toàn bộ mẻ (ProductionBatch) và công đoạn (WorkOrderStep) cho lệnh khi bắt đầu.
     * - Mẻ: theo batchQtyPerRunList của phương án (mỗi mẻ = 1 lô truy xuất HSD).
     * - Công đoạn:
     *     • Bước RIÊNG → 1 lần chạy / mẻ (gắn batchNumber, khối lượng = sản lượng mẻ).
     *     • Bước CHUNG → số lần = ceil(tổng / công suất); công suất null/≤0 = 1 lần cả lệnh.
     */
    private void createBatchesAndStages(WorkOrder wo, WorkOrderPlan plan, User worker) {
        // Không tạo trùng nếu đã có
        if (!batchRepo.findByWorkOrder_IdOrderByBatchNumberAsc(wo.getId()).isEmpty()) return;

        ProductionRecipe recipe = recipeRepo.findById(plan.getRecipe().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy biến thể sản xuất"));

        // Sản lượng từng mẻ
        List<BigDecimal> batchQtys = new ArrayList<>();
        if (plan.getBatchQtyPerRunList() != null) {
            try {
                batchQtys = objectMapper.readValue(plan.getBatchQtyPerRunList(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, BigDecimal.class));
            } catch (Exception ignored) {}
        }
        int totalBatches = plan.getTotalBatches() != null ? plan.getTotalBatches() : batchQtys.size();
        if (totalBatches <= 0) totalBatches = 1;
        while (batchQtys.size() < totalBatches) batchQtys.add(plan.getBatchQtyPerRun());

        BigDecimal totalQty = batchQtys.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1) Tạo các mẻ
        String batchPrefix = "BATCH-" + LocalDate.now(VN).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        long baseSeq = batchRepo.countByBatchCodePrefix(batchPrefix);
        for (int i = 0; i < totalBatches; i++) {
            ProductionBatch batch = ProductionBatch.builder()
                    .batchCode(batchPrefix + String.format("%04d", baseSeq + i + 1))
                    .workOrder(wo)
                    .batchNumber(i + 1)
                    .recipe(recipe)
                    .productName(wo.getProductName())
                    .recipeName(recipe.getName())
                    .outputUnit(wo.getOutputUnit())
                    .status(ProductionBatch.BatchStatus.IN_PROGRESS)
                    .createdBy(worker)
                    .createdByName(worker.getFullName())
                    .build();
            batchRepo.save(batch);
        }

        // 2) Tạo công đoạn từ snapshot bước của phương án
        List<String> stepNames = fromJsonList(plan.getBatchSteps());
        List<Map<String, Object>> details = new ArrayList<>();
        if (plan.getBatchStepDetails() != null) {
            try {
                details = objectMapper.readValue(plan.getBatchStepDetails(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            } catch (Exception ignored) {}
        }

        for (int i = 0; i < stepNames.size(); i++) {
            Map<String, Object> d = i < details.size() ? details.get(i) : Map.of();
            boolean requiresQc = boolVal(d.get("requiresQC") != null ? d.get("requiresQC") : d.get("requiresQc"));
            ProductionRecipeStep.ControlType ct = parseControlType(d.get("controlType"), requiresQc);
            Integer durationMin = intVal(d.get("durationMinutes"));
            boolean shared = boolVal(d.get("shared"));
            BigDecimal capacity = decVal(d.get("capacityPerRun"));
            Machine machine = null;
            Long machineId = longVal(d.get("machineId"));
            if (machineId != null) machine = machineRepo.findById(machineId).orElse(null);

            int stageSeq = i + 1;

            if (shared) {
                // Bước CHUNG — chia theo công suất
                List<BigDecimal> runQtys = new ArrayList<>();
                if (capacity == null || capacity.compareTo(BigDecimal.ZERO) <= 0
                        || capacity.compareTo(totalQty) >= 0) {
                    runQtys.add(totalQty);
                } else {
                    BigDecimal remaining = totalQty;
                    while (remaining.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal take = remaining.min(capacity);
                        runQtys.add(take.setScale(3, RoundingMode.HALF_UP));
                        remaining = remaining.subtract(take);
                    }
                }
                int totalRuns = runQtys.size();
                for (int r = 0; r < totalRuns; r++) {
                    woStepRepo.save(buildStage(wo, stageSeq, stepNames.get(i), true,
                            r + 1, totalRuns, runQtys.get(r), null,
                            requiresQc, ct, durationMin, machine));
                }
            } else {
                // Bước RIÊNG — 1 lần / mẻ
                for (int b = 0; b < totalBatches; b++) {
                    woStepRepo.save(buildStage(wo, stageSeq, stepNames.get(i), false,
                            b + 1, totalBatches, batchQtys.get(b), b + 1,
                            requiresQc, ct, durationMin, machine));
                }
            }
        }
    }

    private WorkOrderStep buildStage(WorkOrder wo, int stageSeq, String name, boolean shared,
                                     int runNumber, int totalRuns, BigDecimal runQty, Integer batchNumber,
                                     boolean requiresQc, ProductionRecipeStep.ControlType ct,
                                     Integer durationMin, Machine machine) {
        return WorkOrderStep.builder()
                .workOrder(wo)
                .stageSequence(stageSeq)
                .stageName(name)
                .shared(shared)
                .runNumber(runNumber)
                .totalRuns(totalRuns)
                .runQty(runQty)
                .batchNumber(batchNumber)
                .status(WorkOrderStep.Status.PENDING)
                .requiresQc(requiresQc)
                .controlType(ct)
                .durationMinutes(durationMin)
                .machine(machine)
                .machineName(machine != null ? machine.getName() : null)
                .build();
    }

    // ── Helpers parse JSON snapshot ──────────────────────────────────────────
    private boolean boolVal(Object v) {
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }
    private Integer intVal(Object v) {
        if (v == null) return null;
        try { return Integer.valueOf(v.toString().trim()); } catch (Exception e) { return null; }
    }
    private Long longVal(Object v) {
        if (v == null) return null;
        try { return Long.valueOf(v.toString().trim()); } catch (Exception e) { return null; }
    }
    private BigDecimal decVal(Object v) {
        if (v == null) return null;
        try { return new BigDecimal(v.toString().trim()); } catch (Exception e) { return null; }
    }
    private ProductionRecipeStep.ControlType parseControlType(Object v, boolean requiresQc) {
        try {
            return v != null ? ProductionRecipeStep.ControlType.valueOf(String.valueOf(v))
                    : (requiresQc ? ProductionRecipeStep.ControlType.PHOTO_WEIGHT : ProductionRecipeStep.ControlType.NONE);
        } catch (Exception e) {
            return requiresQc ? ProductionRecipeStep.ControlType.PHOTO_WEIGHT : ProductionRecipeStep.ControlType.NONE;
        }
    }

    /**
     * Trừ kho xưởng theo FIFO cho từng loại nguyên liệu trong phương án.
     * FIFO ở đây = ưu tiên lô có expiryDate nhỏ nhất (gần hết hạn nhất) trừ trước,
     * lô không có hạn sử dụng (expiryDate = null) xếp cuối cùng.
     *
     * Ví dụ: 3 lô hết hạn lần lượt 1/12, 2/12, 3/12
     *   → trừ hết lô 1/12 trước, rồi mới trừ tiếp lô 2/12, sau đó 3/12.
     *
     * Nếu tổng tồn kho của một loại NVL không đủ so với yêu cầu → ném exception,
     * toàn bộ transaction rollback (không trừ dở dang).
     */
    private void deductStockFifo(WorkOrder wo, List<WorkOrderPlanMaterial> materials) {
        for (WorkOrderPlanMaterial mat : materials) {
            if (mat.getQuantity() == null || mat.getQuantity().compareTo(BigDecimal.ZERO) <= 0) continue;

            // Lấy tất cả lô còn hàng (isActive=true) của loại NVL này
            List<FactoryMaterialStock> batches = stockRepo
                    .findByMaterialNameAndUnitAndIsActiveTrueOrderByCreatedAtAsc(
                            mat.getMaterialName(), mat.getUnit());

            // Sắp xếp FIFO theo expiryDate tăng dần — null (không hạn) xếp cuối
            batches.sort((a, b) -> {
                if (a.getExpiryDate() == null && b.getExpiryDate() == null) return 0;
                if (a.getExpiryDate() == null) return 1;
                if (b.getExpiryDate() == null) return -1;
                return a.getExpiryDate().compareTo(b.getExpiryDate());
            });

            BigDecimal totalAvailable = batches.stream()
                    .map(FactoryMaterialStock::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalAvailable.compareTo(mat.getQuantity()) < 0) {
                throw new IllegalStateException(
                        String.format("Kho xưởng không đủ nguyên liệu '%s': cần %s %s, tồn %s %s",
                                mat.getMaterialName(),
                                mat.getQuantity().stripTrailingZeros().toPlainString(), mat.getUnit(),
                                totalAvailable.stripTrailingZeros().toPlainString(), mat.getUnit()));
            }

            BigDecimal remaining = mat.getQuantity();
            for (FactoryMaterialStock lot : batches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

                BigDecimal take = remaining.min(lot.getQuantity());
                lot.setQuantity(lot.getQuantity().subtract(take));
                if (lot.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
                    lot.setIsActive(false); // lô đã dùng hết
                }
                stockRepo.save(lot);

                // Ghi nhận deduction để có thể hoàn kho khi lệnh/mẻ bị hủy
                deductionRepo.save(WorkOrderStockDeduction.builder()
                        .workOrder(wo)
                        .stock(lot)
                        .materialName(mat.getMaterialName())
                        .unit(mat.getUnit())
                        .deductedQty(take)
                        .returned(false)
                        .build());

                remaining = remaining.subtract(take);
            }
        }
    }

    // ─── ProductionBatch (Factory thực hiện) ──────────────────────────────────

    public ProductionBatchDto startBatch(StartBatchRequest req, String username) {
        // Từ bản cập nhật "bước chung / bước riêng": tất cả các mẻ được TẠO SẴN khi
        // bắt đầu lệnh (startWorkOrder). Không còn thao tác "bắt đầu mẻ" thủ công.
        throw new IllegalStateException("Các mẻ đã được tạo sẵn khi bắt đầu lệnh — không cần bắt đầu mẻ thủ công");
    }

    // ─── Công đoạn cấp lệnh: bắt đầu / hoàn thành 1 lần chạy ───────────────────

    /**
     * Bắt đầu 1 lần chạy của công đoạn.
     * Ràng buộc thứ tự (barrier):
     *  - Công đoạn 1 → luôn bắt đầu được.
     *  - Nếu công đoạn này CHUNG hoặc công đoạn trước CHUNG → phải chờ TOÀN BỘ công đoạn
     *    trước hoàn tất (đủ khối lượng để gom).
     *  - Nếu cả 2 công đoạn liền kề đều RIÊNG → chỉ cần lần chạy cùng mẻ ở công đoạn trước xong
     *    (cho phép chạy dây chuyền: mẻ 3 đang nhồi trong khi mẻ 1 đã sang đóng gói).
     *  - Máy độc quyền: máy đang chạy lần khác thì phải chờ.
     */
    public WorkOrderStepRunDto startStageRun(Long stepId, StartStageRunRequest req, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        WorkOrderStep step = woStepRepo.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy công đoạn"));
        if (step.getStatus() != WorkOrderStep.Status.PENDING) {
            throw new IllegalStateException("Công đoạn này đã được bắt đầu hoặc đã hoàn thành");
        }

        List<WorkOrderStep> all = woStepRepo.findByWorkOrder_IdOrderByStageSequenceAscRunNumberAsc(step.getWorkOrder().getId());
        if (!canStartRun(step, all)) {
            throw new IllegalStateException("Chưa thể bắt đầu — công đoạn trước chưa hoàn tất");
        }
        if (step.getMachine() != null) {
            checkMachineAvailableWO(step.getMachine(), step.getId());
        }

        step.setStatus(WorkOrderStep.Status.IN_PROGRESS);
        step.setStartedBy(worker);
        step.setStartedByName(worker.getFullName());
        step.setStartedAt(System.currentTimeMillis());
        if (req != null && req.getNotes() != null) step.setNotes(req.getNotes());
        return toStageRunDto(woStepRepo.save(step), all);
    }

    public WorkOrderStepRunDto completeStageRun(Long stepId, CompleteStageRunRequest req, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        WorkOrderStep step = woStepRepo.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy công đoạn"));
        if (step.getStatus() == WorkOrderStep.Status.COMPLETED) {
            String by = step.getCompletedByName() != null ? step.getCompletedByName() : "người khác";
            throw new IllegalStateException("Công đoạn này đã được xác nhận bởi " + by);
        }
        if (step.getStatus() != WorkOrderStep.Status.IN_PROGRESS) {
            throw new IllegalStateException("Công đoạn chưa được bắt đầu, không thể hoàn thành");
        }
        // Bắt buộc ảnh nếu kiểm soát PHOTO_WEIGHT
        boolean requirePhoto = step.getControlType() == ProductionRecipeStep.ControlType.PHOTO_WEIGHT;
        List<String> attachments = req != null ? req.getAttachments() : null;
        if (requirePhoto && (attachments == null || attachments.isEmpty())) {
            throw new IllegalArgumentException("Bước kiểm soát hình ảnh cân ký — vui lòng chụp ảnh trước khi xác nhận");
        }

        // ── SỐ HƯ HỎNG — chỉ áp dụng cho bước CÓ kiểm soát (VISUAL / PHOTO_WEIGHT) ──
        boolean hasControl = step.getControlType() != null
                && step.getControlType() != ProductionRecipeStep.ControlType.NONE;
        BigDecimal damaged = req != null ? req.getDamagedQty() : null;
        if (damaged != null && damaged.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Số hư hỏng không được âm");
        }
        if (!hasControl && damaged != null && damaged.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException("Bước không có kiểm soát thì không ghi nhận số hư hỏng");
        }
        step.setDamagedQty(hasControl && damaged != null ? damaged : BigDecimal.ZERO);

        step.setStatus(WorkOrderStep.Status.COMPLETED);
        step.setAttachments(toJson(attachments));
        if (req != null && req.getNotes() != null) step.setNotes(req.getNotes());
        step.setCompletedBy(worker);
        step.setCompletedByName(worker.getFullName());
        step.setCompletedAt(System.currentTimeMillis());

        List<WorkOrderStep> all = woStepRepo.findByWorkOrder_IdOrderByStageSequenceAscRunNumberAsc(step.getWorkOrder().getId());
        return toStageRunDto(woStepRepo.save(step), all);
    }

    /** Đủ điều kiện bắt đầu lần chạy này chưa? */
    private boolean canStartRun(WorkOrderStep step, List<WorkOrderStep> all) {
        int stageSeq = step.getStageSequence();
        if (stageSeq <= 1) return true;
        int prevSeq = stageSeq - 1;
        List<WorkOrderStep> prevRuns = all.stream()
                .filter(s -> s.getStageSequence() == prevSeq)
                .collect(Collectors.toList());
        if (prevRuns.isEmpty()) return true;
        boolean prevShared = prevRuns.get(0).isShared();
        if (step.isShared() || prevShared) {
            // Barrier: toàn bộ công đoạn trước phải xong
            return prevRuns.stream().allMatch(s -> s.getStatus() == WorkOrderStep.Status.COMPLETED);
        }
        // Cả 2 đều riêng → chỉ cần lần chạy cùng mẻ ở công đoạn trước xong
        return prevRuns.stream()
                .filter(s -> java.util.Objects.equals(s.getBatchNumber(), step.getBatchNumber()))
                .allMatch(s -> s.getStatus() == WorkOrderStep.Status.COMPLETED);
    }

    /** Kiểm tra máy có đang busy không (bảo trì / hỏng / đang chạy lần khác) — bản cho WorkOrderStep */
    private void checkMachineAvailableWO(Machine machine, Long excludeStepId) {
        Machine fresh = machineRepo.findById(machine.getId()).orElse(machine);
        if (fresh.getStatus() == Machine.MachineStatus.UNDER_MAINTENANCE) {
            throw new IllegalStateException("Máy \"" + fresh.getName() + "\" đang bảo trì, không thể sử dụng");
        }
        if (fresh.getStatus() == Machine.MachineStatus.INACTIVE) {
            throw new IllegalStateException("Máy \"" + fresh.getName() + "\" không hoạt động (đã tắt), không thể sử dụng");
        }
        long nowMs = System.currentTimeMillis();
        List<MaintenanceSchedule> activeMaints = maintenanceRepo.findActiveMaintenanceForMachineAt(fresh.getId(), nowMs);
        if (!activeMaints.isEmpty()) {
            MaintenanceSchedule m = activeMaints.get(0);
            String typeLabel = m.getMaintenanceType() == MaintenanceSchedule.MaintenanceType.CORRECTIVE
                    ? "sửa chữa/hỏng" : "bảo trì định kỳ";
            throw new IllegalStateException(
                    "Máy \"" + fresh.getName() + "\" đang trong lịch " + typeLabel
                            + ": \"" + m.getTitle() + "\" (đến " + formatDate(m.getPlannedEnd()) + "), không thể sử dụng");
        }
        List<WorkOrderStep> running = woStepRepo.findByMachine_IdAndStatus(fresh.getId(), WorkOrderStep.Status.IN_PROGRESS);
        WorkOrderStep busy = running.stream().filter(s -> !s.getId().equals(excludeStepId)).findFirst().orElse(null);
        if (busy != null) {
            String woCode = busy.getWorkOrder() != null ? busy.getWorkOrder().getWorkOrderCode() : "";
            throw new IllegalStateException(
                    "Máy \"" + fresh.getName() + "\" đang được sử dụng ở công đoạn \"" + busy.getStageName() + "\""
                            + (woCode.isEmpty() ? "" : " (lệnh " + woCode + ")") + ", vui lòng chờ máy rảnh");
        }
    }


    /**
     * Bắt đầu 1 bước trong mẻ.
     * Ràng buộc:
     *  - Tuần tự: chỉ bắt đầu được khi bước liền trước đã COMPLETED.
     *  - Máy độc quyền: nếu bước có gán máy, máy đó KHÔNG được đang busy
     *    (đang chạy 1 bước khác ở bất kỳ mẻ/lệnh nào, hoặc đang bảo trì).
     */
    public BatchStepDto startStep(Long batchId, int stepSeq, StartStepRequest req, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        BatchStep step = stepRepo.findByBatch_IdAndStepSequence(batchId, stepSeq)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bước"));

        if (step.getStatus() != BatchStep.StepStatus.PENDING) {
            throw new IllegalStateException("Bước này đã được bắt đầu hoặc đã hoàn thành");
        }
        // Đã bỏ ràng buộc "bước trước phải COMPLETED mới được bắt đầu bước sau":
        // nhiều bước có thể chạy song song (VD: rửa thịt và luộc thịt cùng lúc,
        // rửa được bao nhiêu cho vào luộc bấy nhiêu, không cần rửa hết mới luộc).

        if (step.getMachine() != null) {
            checkMachineAvailable(step.getMachine(), step.getId());
        }

        step.setStatus(BatchStep.StepStatus.IN_PROGRESS);
        step.setStartedBy(worker);
        step.setStartedByName(worker.getFullName());
        step.setStartedAt(System.currentTimeMillis());
        if (req != null && req.getNotes() != null) step.setNotes(req.getNotes());

        return toStepDto(stepRepo.save(step));
    }

    /**
     * Kiểm tra máy có đang busy không (đang bảo trì HOẶC sửa chữa hỏng HOẶC
     * nằm trong lịch bảo trì/bảo dưỡng đang active, HOẶC đang chạy 1 bước khác
     * — bất kỳ mẻ/lệnh nào — chưa hoàn thành). Ném lỗi rõ ràng nếu busy.
     */
    private void checkMachineAvailable(Machine machine, Long excludeStepId) {
        Machine fresh = machineRepo.findById(machine.getId()).orElse(machine);

        // 1. Kiểm tra trạng thái máy
        if (fresh.getStatus() == Machine.MachineStatus.UNDER_MAINTENANCE) {
            throw new IllegalStateException("Máy \"" + fresh.getName() + "\" đang bảo trì, không thể sử dụng");
        }
        if (fresh.getStatus() == Machine.MachineStatus.INACTIVE) {
            throw new IllegalStateException("Máy \"" + fresh.getName() + "\" không hoạt động (đã tắt), không thể sử dụng");
        }

        // 2. Kiểm tra lịch bảo trì/bảo dưỡng đang active ngay lúc này
        long nowMs = System.currentTimeMillis();
        List<MaintenanceSchedule> activeMaints = maintenanceRepo.findActiveMaintenanceForMachineAt(fresh.getId(), nowMs);
        if (!activeMaints.isEmpty()) {
            MaintenanceSchedule m = activeMaints.get(0);
            String typeLabel = m.getMaintenanceType() == MaintenanceSchedule.MaintenanceType.CORRECTIVE
                    ? "sửa chữa/hỏng" : "bảo trì định kỳ";
            throw new IllegalStateException(
                    "Máy \"" + fresh.getName() + "\" đang trong lịch " + typeLabel
                            + ": \"" + m.getTitle() + "\" (đến " + formatDate(m.getPlannedEnd()) + "), không thể sử dụng");
        }

        // 3. Kiểm tra đang được dùng bởi bước sản xuất khác
        List<BatchStep> running = stepRepo.findByMachine_IdAndStatus(fresh.getId(), BatchStep.StepStatus.IN_PROGRESS);
        boolean busy = running.stream().anyMatch(s -> !s.getId().equals(excludeStepId));
        if (busy) {
            BatchStep busyStep = running.stream().filter(s -> !s.getId().equals(excludeStepId)).findFirst().orElse(null);
            String batchCode = busyStep != null && busyStep.getBatch() != null ? busyStep.getBatch().getBatchCode() : "?";
            String woCode = busyStep != null && busyStep.getBatch() != null && busyStep.getBatch().getWorkOrder() != null
                    ? busyStep.getBatch().getWorkOrder().getWorkOrderCode() : "";
            throw new IllegalStateException(
                    "Máy \"" + fresh.getName() + "\" đang được sử dụng cho mẻ " + batchCode
                            + (woCode.isEmpty() ? "" : " (lệnh " + woCode + ")")
                            + ", vui lòng chờ máy rảnh");
        }
    }

    public BatchStepDto completeStep(Long batchId, int stepSeq,
                                     CompleteStepRequest req, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        BatchStep step = stepRepo.findByBatch_IdAndStepSequence(batchId, stepSeq)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bước"));
        if (step.getStatus() == BatchStep.StepStatus.COMPLETED) {
            String confirmedBy = step.getCompletedByName() != null ? step.getCompletedByName() : "người khác";
            throw new IllegalStateException("Bước này đã được xác nhận bởi " + confirmedBy);
        }
        if (step.getStatus() != BatchStep.StepStatus.IN_PROGRESS) {
            throw new IllegalStateException("Bước chưa được bắt đầu, không thể hoàn thành");
        }

        step.setStatus(BatchStep.StepStatus.COMPLETED);
        step.setAttachments(toJson(req.getAttachments()));
        step.setNotes(req.getNotes());
        step.setCompletedBy(worker);
        step.setCompletedByName(worker.getFullName());
        step.setCompletedAt(System.currentTimeMillis());

        return toStepDto(stepRepo.save(step));
    }

    public ProductionBatchDto completeBatch(Long batchId, CompleteBatchRequest req, String username) {
        ProductionBatch batch = batchRepo.findByIdWithDetails(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mẻ"));
        if (batch.getStatus() != ProductionBatch.BatchStatus.IN_PROGRESS) {
            throw new IllegalStateException("Mẻ không ở trạng thái đang thực hiện");
        }
        if (req.getActualOutputQty() == null || req.getActualOutputQty().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Vui lòng nhập sản lượng thực tế hợp lệ");
        }
        BigDecimal scrapQty = req.getScrapQty() != null ? req.getScrapQty() : BigDecimal.ZERO;
        if (scrapQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Sản lượng lỗi không hợp lệ");
        }
        if (scrapQty.compareTo(BigDecimal.ZERO) > 0 && (req.getScrapReason() == null || req.getScrapReason().isBlank())) {
            throw new IllegalArgumentException("Vui lòng nhập lý do lỗi/huỷ cho sản lượng không đạt");
        }
        // Issue #1: bắt buộc nhập ngày sản xuất + hạn sử dụng để nhập kho bán thành phẩm
        if (req.getActualOutputQty().compareTo(BigDecimal.ZERO) > 0) {
            if (req.getManufactureDate() == null) {
                throw new IllegalArgumentException("Vui lòng nhập ngày sản xuất cho lô bán thành phẩm");
            }
            if (req.getExpiryDate() == null) {
                throw new IllegalArgumentException("Vui lòng nhập hạn sử dụng cho lô bán thành phẩm");
            }
            if (req.getExpiryDate() < req.getManufactureDate()) {
                throw new IllegalArgumentException("Hạn sử dụng không được trước ngày sản xuất");
            }
        }

        // Tất cả CÔNG ĐOẠN phủ mẻ này phải đã hoàn tất mới cho hoàn thành mẻ:
        //  - Bước chung: toàn bộ lần chạy đã COMPLETED.
        //  - Bước riêng: lần chạy của đúng mẻ này đã COMPLETED.
        if (batch.getWorkOrder() != null) {
            List<WorkOrderStep> stages = woStepRepo
                    .findByWorkOrder_IdOrderByStageSequenceAscRunNumberAsc(batch.getWorkOrder().getId());
            boolean coveringDone = stages.stream()
                    .filter(s -> s.isShared() || java.util.Objects.equals(s.getBatchNumber(), batch.getBatchNumber()))
                    .allMatch(s -> s.getStatus() == WorkOrderStep.Status.COMPLETED);
            if (!stages.isEmpty() && !coveringDone) {
                throw new IllegalStateException("Vẫn còn công đoạn chưa hoàn thành cho mẻ này");
            }
        }

        batch.setActualOutputQty(req.getActualOutputQty());
        batch.setScrapQty(scrapQty.compareTo(BigDecimal.ZERO) > 0 ? scrapQty : null);
        batch.setScrapReason(scrapQty.compareTo(BigDecimal.ZERO) > 0 ? req.getScrapReason() : null);
        batch.setProducedAt(System.currentTimeMillis());
        batch.setStatus(ProductionBatch.BatchStatus.COMPLETED);
        if (req.getNotes() != null) batch.setNotes(req.getNotes());

        // ── GIÁ VỐN NGUYÊN LIỆU CỦA MẺ ────────────────────────────────────────
        // Lấy từ các lô kho đã bị trừ FIFO khi bắt đầu lệnh × giá vốn của từng lô.
        // Toàn bộ chi phí dồn vào sản lượng ĐẠT — scrap không gánh giá vốn.
        // Nếu kế toán trưởng chưa chốt giá phiếu đặt hàng thì ở đây tạm ra 0;
        // giá vốn CHÍNH THỨC được tính lại ở bước nhập Kho thành phẩm.
        batch.setMaterialCost(productionCostService.batchMaterialCost(batch));
        batch.setUnitCost(productionCostService.batchUnitCostPerKg(batch));

        batchRepo.save(batch);

        // Sản lượng ĐẠT chất lượng → Kho bán thành phẩm (kg, theo batch, CHƯA đóng gói)
        if (req.getActualOutputQty().compareTo(BigDecimal.ZERO) > 0) {
            semiFinishedGoodsService.receiveFromBatch(batch, req.getActualOutputQty(),
                    req.getManufactureDate(), req.getExpiryDate());
        }
        // Sản lượng LỖI/huỷ → Kho Scrap (kg, theo batch, kèm lý do)
        if (scrapQty.compareTo(BigDecimal.ZERO) > 0) {
            semiFinishedGoodsService.receiveScrapFromBatch(batch, scrapQty, req.getScrapReason());
        }

        // Cảnh báo nếu hao hụt vượt ±5% so với kế hoạch của mẻ (cho phép, chỉ cảnh báo Owner)
        BigDecimal standardQty = batch.getRecipe() != null ? batch.getRecipe().getStandardOutputQty() : null;
        WorkOrderPlan woPlan = batch.getWorkOrder() != null ? batch.getWorkOrder().getWorkOrderPlan() : null;
        BigDecimal plannedBatchQty = null;
        if (woPlan != null && woPlan.getBatchQtyPerRunList() != null) {
            try {
                List<BigDecimal> qtyList = objectMapper.readValue(woPlan.getBatchQtyPerRunList(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, BigDecimal.class));
                int idx = (batch.getBatchNumber() != null ? batch.getBatchNumber() : 1) - 1;
                if (idx >= 0 && idx < qtyList.size()) plannedBatchQty = qtyList.get(idx);
            } catch (Exception ignored) {}
        }
        if (plannedBatchQty == null) plannedBatchQty = standardQty;
        if (plannedBatchQty != null && plannedBatchQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal variancePct = req.getActualOutputQty().subtract(plannedBatchQty)
                    .divide(plannedBatchQty, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            if (variancePct.abs().compareTo(BigDecimal.valueOf(5)) > 0) {
                String warnMsg = String.format("Mẻ %s lệch %s%% so với kế hoạch (%s/%s %s) — vượt ngưỡng ±5%%",
                        batch.getBatchCode(), variancePct.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                        req.getActualOutputQty().stripTrailingZeros().toPlainString(),
                        plannedBatchQty.stripTrailingZeros().toPlainString(), batch.getOutputUnit());
                notificationService.sendToRole("OWNER", "BATCH_OUTPUT_VARIANCE_HIGH", warnMsg,
                        "{\"batchId\":" + batch.getId() + "}");
            }
        }

        // Cập nhật accumulatedQty của WorkOrder
        if (batch.getWorkOrder() != null) {
            WorkOrder wo = workOrderRepo.findByIdWithPlan(batch.getWorkOrder().getId())
                    .orElse(batch.getWorkOrder());
            BigDecimal accumulated = batchRepo.sumCompletedQtyByWorkOrder(wo.getId());
            wo.setAccumulatedQty(accumulated);

            boolean shouldComplete = false;

            // Điều kiện 1: đã đủ hoặc vượt sản lượng kế hoạch
            if (accumulated.compareTo(wo.getPlannedQty()) >= 0) {
                shouldComplete = true;
            }

            // Điều kiện 2: đã chạy hết số mẻ theo phương án (mẻ cuối cùng vừa xong)
            if (!shouldComplete && wo.getWorkOrderPlan() != null) {
                int plannedBatches = wo.getWorkOrderPlan().getTotalBatches() != null
                        ? wo.getWorkOrderPlan().getTotalBatches() : 0;
                if (plannedBatches > 0) {
                    long doneBatches = batchRepo.countByWorkOrder_IdAndStatus(wo.getId(), ProductionBatch.BatchStatus.COMPLETED)
                            + batchRepo.countByWorkOrder_IdAndStatus(wo.getId(), ProductionBatch.BatchStatus.CANCELLED);
                    if (doneBatches >= plannedBatches) {
                        shouldComplete = true;
                    }
                }
            }

            if (shouldComplete) {
                wo.setStatus(WorkOrder.WorkOrderStatus.COMPLETED);
                wo.setActualEndDate(System.currentTimeMillis());
                String msg = String.format("Lệnh sản xuất %s đã hoàn thành! Sản lượng: %s/%s %s",
                        wo.getWorkOrderCode(), accumulated, wo.getPlannedQty(), wo.getOutputUnit());
                notificationService.sendToRole("OWNER", "WORK_ORDER_COMPLETED", msg,
                        "{\"workOrderId\":" + wo.getId() + "}");
            }
            workOrderRepo.save(wo);
        }

        return toBatchDto(batch);
    }

    /**
     * Lấy danh sách nguyên liệu đã trừ kho cho 1 lệnh sản xuất (chưa hoàn lại) —
     * dùng để hiển thị form nhập "đã thực tế dùng bao nhiêu" khi nhân viên huỷ mẻ.
     */
    public List<WorkOrderMaterialUsageDto> getMaterialUsageForWorkOrder(Long workOrderId) {
        List<WorkOrderStockDeduction> deductions = deductionRepo.findUnreturnedByWorkOrderId(workOrderId);
        Map<String, List<WorkOrderStockDeduction>> byMaterial = new LinkedHashMap<>();
        for (WorkOrderStockDeduction d : deductions) {
            byMaterial.computeIfAbsent(d.getMaterialName(), k -> new ArrayList<>()).add(d);
        }
        List<WorkOrderMaterialUsageDto> result = new ArrayList<>();
        for (Map.Entry<String, List<WorkOrderStockDeduction>> e : byMaterial.entrySet()) {
            List<WorkOrderStockDeduction> list = e.getValue();
            BigDecimal totalDeducted = list.stream()
                    .map(WorkOrderStockDeduction::getDeductedQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalUsed = list.stream()
                    .map(d -> d.getActualUsedQty() != null ? d.getActualUsedQty() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(WorkOrderMaterialUsageDto.builder()
                    .materialName(e.getKey())
                    .unit(list.get(0).getUnit())
                    .totalDeductedQty(totalDeducted)
                    .totalUsedQty(totalUsed)
                    .remainingQty(totalDeducted.subtract(totalUsed))
                    .build());
        }
        return result;
    }

    public ProductionBatchDto cancelBatch(Long batchId, CancelBatchRequest req, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        ProductionBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mẻ"));
        if (batch.getStatus() != ProductionBatch.BatchStatus.IN_PROGRESS) {
            throw new IllegalStateException("Chỉ huỷ được mẻ đang thực hiện");
        }

        // ── 1) Ghi nhận sản lượng thực tế đạt được (dù bị huỷ) ────────────────
        BigDecimal actualOutput = req.getActualOutputQty() != null ? req.getActualOutputQty() : BigDecimal.ZERO;
        batch.setActualOutputQty(actualOutput);
        batch.setProducedAt(System.currentTimeMillis());

        if (batch.getWorkOrder() != null) {
            WorkOrder woRef = workOrderRepo.findByIdWithPlan(batch.getWorkOrder().getId()).orElse(batch.getWorkOrder());
            BigDecimal prevAccumulated = woRef.getAccumulatedQty() != null ? woRef.getAccumulatedQty() : BigDecimal.ZERO;
            woRef.setAccumulatedQty(prevAccumulated.add(actualOutput));
            workOrderRepo.save(woRef);
        }

        // ── 2) Hoàn kho phần nguyên liệu chưa thực tế sử dụng ─────────────────
        List<StockDeductionDto> materialUsageDtos = new ArrayList<>();
        if (batch.getWorkOrder() != null) {
            materialUsageDtos = returnUnusedMaterials(batch.getWorkOrder().getId(), req.getUsedMaterialQtys());
        }

        // ── 3) Đánh dấu mẻ đã huỷ + lưu lý do ─────────────────────────────────
        batch.setStatus(ProductionBatch.BatchStatus.CANCELLED);

        BatchCancellation cancel = BatchCancellation.builder()
                .batch(batch)
                .reason(req.getReason())
                .attachments(toJson(req.getAttachments()))
                .resolution(BatchCancellation.Resolution.valueOf(req.getResolution()))
                .resolutionNotes(req.getResolutionNotes())
                .cancelledBy(worker)
                .cancelledByName(worker.getFullName())
                .cancelledAt(System.currentTimeMillis())
                .build();
        batch.setCancellation(cancel);

        batchRepo.save(batch);

        // ── 4) Ghi nhận luôn lệnh sản xuất bị huỷ nếu không còn mẻ nào có thể tiếp tục ──
        // Điều kiện huỷ lệnh: resolution = ABORT (dừng hẳn, không làm mẻ khác),
        // HOẶC đã chạy hết số mẻ theo phương án mà không còn mẻ nào IN_PROGRESS,
        // và lệnh vẫn chưa đạt sản lượng kế hoạch.
        boolean workOrderCancelled = false;
        if (batch.getWorkOrder() != null) {
            WorkOrder wo = workOrderRepo.findByIdWithPlan(batch.getWorkOrder().getId()).orElse(batch.getWorkOrder());

            boolean noBatchRunning = batchRepo.findByWorkOrder_IdOrderByBatchNumberAsc(wo.getId())
                    .stream().noneMatch(b -> b.getStatus() == ProductionBatch.BatchStatus.IN_PROGRESS);

            boolean reachedTarget = wo.getAccumulatedQty() != null && wo.getPlannedQty() != null
                    && wo.getAccumulatedQty().compareTo(wo.getPlannedQty()) >= 0;

            boolean exhaustedPlannedBatches = false;
            if (wo.getWorkOrderPlan() != null) {
                int plannedBatches = wo.getWorkOrderPlan().getTotalBatches() != null
                        ? wo.getWorkOrderPlan().getTotalBatches() : 0;
                if (plannedBatches > 0) {
                    long doneOrCancelled = batchRepo.countByWorkOrder_IdAndStatus(wo.getId(), ProductionBatch.BatchStatus.COMPLETED)
                            + batchRepo.countByWorkOrder_IdAndStatus(wo.getId(), ProductionBatch.BatchStatus.CANCELLED);
                    exhaustedPlannedBatches = doneOrCancelled >= plannedBatches;
                }
            }

            boolean isAbort = "ABORT".equals(req.getResolution());

            if (noBatchRunning && !reachedTarget && (isAbort || exhaustedPlannedBatches)) {
                wo.setStatus(WorkOrder.WorkOrderStatus.CANCELLED);
                wo.setActualEndDate(System.currentTimeMillis());
                workOrderRepo.save(wo);
                workOrderCancelled = true;
            }
        }

        // Notify Owner
        String msg = String.format("Mẻ %s thuộc lệnh %s bị huỷ bởi %s. Sản lượng thu được: %s %s. Lý do: %s",
                batch.getBatchCode(),
                batch.getWorkOrder() != null ? batch.getWorkOrder().getWorkOrderCode() : "—",
                worker.getFullName(),
                actualOutput.stripTrailingZeros().toPlainString(), batch.getOutputUnit(),
                req.getReason());
        notificationService.sendToRole("OWNER", "BATCH_CANCELLED", msg,
                "{\"batchId\":" + batchId + "}");

        if (workOrderCancelled && batch.getWorkOrder() != null) {
            String woMsg = String.format("Lệnh sản xuất %s đã bị huỷ do mẻ %s huỷ và không còn mẻ nào tiếp tục được. Sản lượng thu được: %s %s",
                    batch.getWorkOrder().getWorkOrderCode(), batch.getBatchCode(),
                    actualOutput.stripTrailingZeros().toPlainString(), batch.getOutputUnit());
            notificationService.sendToRole("OWNER", "WORK_ORDER_CANCELLED", woMsg,
                    "{\"workOrderId\":" + batch.getWorkOrder().getId() + "}");
        }

        ProductionBatchDto dto = toBatchDto(batch);
        if (dto.getCancellation() != null) {
            dto.getCancellation().setActualOutputQty(actualOutput);
            dto.getCancellation().setMaterialUsage(materialUsageDtos);
        }
        return dto;
    }

    /**
     * Hoàn lại phần nguyên liệu chưa thực tế sử dụng vào kho xưởng (đúng lô gốc).
     *
     * @param workOrderId      lệnh sản xuất đã trừ kho khi bắt đầu
     * @param usedMaterialQtys map materialName → số lượng đã thực tế dùng (do người dùng nhập khi hủy)
     * @return danh sách chi tiết để hiển thị cho người dùng (đã trừ / đã dùng / đã hoàn)
     */
    private List<StockDeductionDto> returnUnusedMaterials(Long workOrderId, Map<String, BigDecimal> usedMaterialQtys) {
        List<WorkOrderStockDeduction> deductions = deductionRepo.findUnreturnedByWorkOrderId(workOrderId);
        if (deductions.isEmpty()) return List.of();

        Map<String, BigDecimal> usedMap = usedMaterialQtys != null ? usedMaterialQtys : Map.of();

        // Nhóm các bản ghi trừ kho theo tên nguyên liệu, giữ thứ tự FIFO ban đầu (theo createdAt asc)
        Map<String, List<WorkOrderStockDeduction>> byMaterial = new LinkedHashMap<>();
        for (WorkOrderStockDeduction d : deductions) {
            byMaterial.computeIfAbsent(d.getMaterialName(), k -> new ArrayList<>()).add(d);
        }

        List<StockDeductionDto> result = new ArrayList<>();

        for (Map.Entry<String, List<WorkOrderStockDeduction>> entry : byMaterial.entrySet()) {
            String matName = entry.getKey();
            List<WorkOrderStockDeduction> matDeductions = entry.getValue(); // thứ tự FIFO: trừ trước → đứng trước

            // Số lượng đã thực tế dùng do người dùng nhập. Nếu không nhập (key vắng) → coi như dùng hết, không hoàn.
            BigDecimal usedQty = usedMap.containsKey(matName) && usedMap.get(matName) != null
                    ? usedMap.get(matName)
                    : null;

            BigDecimal totalDeducted = matDeductions.stream()
                    .map(WorkOrderStockDeduction::getDeductedQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (usedQty == null) {
                // Không có thông tin → coi như đã dùng hết, chỉ đánh dấu returned để không hoàn lại nữa
                for (WorkOrderStockDeduction d : matDeductions) {
                    d.setActualUsedQty(d.getDeductedQty());
                    d.setReturned(true);
                    deductionRepo.save(d);
                    result.add(toDeductionDto(d));
                }
                continue;
            }

            // Không cho usedQty âm hoặc vượt quá tổng đã trừ
            if (usedQty.compareTo(BigDecimal.ZERO) < 0) usedQty = BigDecimal.ZERO;
            if (usedQty.compareTo(totalDeducted) > 0) usedQty = totalDeducted;

            // Phân bổ usedQty vào từng deduction theo thứ tự FIFO (lô trừ trước được tính "dùng" trước)
            BigDecimal usedLeft = usedQty;
            for (WorkOrderStockDeduction d : matDeductions) {
                BigDecimal consume = usedLeft.min(d.getDeductedQty());
                d.setActualUsedQty(consume);
                usedLeft = usedLeft.subtract(consume);
            }

            // Phần chưa dùng = deductedQty - actualUsedQty của từng deduction → hoàn lại đúng lô gốc đó
            for (WorkOrderStockDeduction d : matDeductions) {
                BigDecimal notUsed = d.getDeductedQty().subtract(
                        d.getActualUsedQty() != null ? d.getActualUsedQty() : BigDecimal.ZERO);

                if (notUsed.compareTo(BigDecimal.ZERO) > 0) {
                    FactoryMaterialStock lot = d.getStock();
                    lot.setQuantity(lot.getQuantity().add(notUsed));
                    lot.setIsActive(true); // kích hoạt lại lô nếu trước đó đã hết (isActive=false)
                    stockRepo.save(lot);
                }

                d.setReturned(true);
                deductionRepo.save(d);
                result.add(toDeductionDto(d));
            }
        }

        return result;
    }

    private StockDeductionDto toDeductionDto(WorkOrderStockDeduction d) {
        return StockDeductionDto.builder()
                .id(d.getId())
                .stockId(d.getStock() != null ? d.getStock().getId() : null)
                .materialName(d.getMaterialName())
                .unit(d.getUnit())
                .deductedQty(d.getDeductedQty())
                .actualUsedQty(d.getActualUsedQty())
                .returned(d.getReturned())
                .stockExpiryDate(d.getStock() != null ? d.getStock().getExpiryDate() : null)
                .createdAt(d.getCreatedAt())
                .build();
    }

    // ─── Machine ──────────────────────────────────────────────────────────────

    public List<MachineDto> listMachines(boolean activeOnly) {
        List<Machine> list = activeOnly
                ? machineRepo.findByStatusOrderByNameAsc(Machine.MachineStatus.ACTIVE)
                : machineRepo.findAllByOrderByNameAsc();
        return list.stream().map(this::toMachineDtoWithStats).collect(Collectors.toList());
    }

    /**
     * Danh sách khoảng thời gian các máy bị chiếm bởi bước sản xuất (WorkOrder/Batch)
     * trong khoảng [fromMs, toMs) — dùng để vẽ sọc chéo xanh dương trên Gantt máy,
     * tương tự sọc chéo đỏ cho bảo trì.
     */
    public List<MachineOccupancyDto> listMachineOccupancy(long fromMs, long toMs) {
        List<WorkOrderStep> steps = woStepRepo.findMachineOccupancyInRange(fromMs, toMs);
        return steps.stream()
                .map(s -> {
                    Long completedAt = s.getCompletedAt();
                    Integer durationMin = s.getDurationMinutes();
                    Long estimatedEndAt;
                    if (completedAt != null) {
                        estimatedEndAt = completedAt;
                    } else if (s.getStartedAt() != null && durationMin != null) {
                        estimatedEndAt = s.getStartedAt() + durationMin * 60_000L;
                    } else {
                        estimatedEndAt = null;
                    }
                    WorkOrder swo = s.getWorkOrder();
                    return MachineOccupancyDto.builder()
                            .machineId(s.getMachine().getId())
                            .stepId(s.getId())
                            .batchId(null)
                            .batchCode(swo != null ? swo.getWorkOrderCode() : null)
                            .workOrderId(swo != null ? swo.getId() : null)
                            .workOrderCode(swo != null ? swo.getWorkOrderCode() : null)
                            .stepName(s.getStageName())
                            .startedAt(s.getStartedAt())
                            .completedAt(completedAt)
                            .durationMinutes(durationMin)
                            .estimatedEndAt(estimatedEndAt)
                            .build();
                })
                .collect(Collectors.toList());
    }

    public MachineDto saveMachine(Long id, SaveMachineRequest req) {
        Machine m = id == null ? new Machine()
                : machineRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy máy"));
        m.setName(req.getName());
        m.setCapacityHoursPerMonth(req.getCapacityHoursPerMonth());
        m.setDescription(req.getDescription());
        m.setPurchaseDate(req.getPurchaseDate());
        m.setPurchaseCost(req.getPurchaseCost());
        m.setManufacturer(req.getManufacturer());
        m.setSerialNumber(req.getSerialNumber());
        if (req.getFactoryId() != null) {
            ProductionFactory factory = factoryRepo.findById(req.getFactoryId()).orElse(null);
            m.setFactory(factory);
            m.setFactoryName(factory != null ? factory.getName() : null);
        }
        if (m.getStatus() == null) m.setStatus(Machine.MachineStatus.ACTIVE);
        // Mục 6: cho sửa trạng thái trực tiếp ở form (nếu gửi lên)
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            try {
                m.setStatus(Machine.MachineStatus.valueOf(req.getStatus().trim()));
            } catch (IllegalArgumentException ignore) { /* giữ nguyên nếu giá trị lạ */ }
        }
        Machine saved = machineRepo.save(m);

        // Nếu tạo mới, notify Owner
        if (id == null) {
            notificationService.sendToRole("OWNER", "MACHINE_CREATED",
                    "Máy mới được thêm: " + m.getName(),
                    "{\"machineId\":" + saved.getId() + "}");
        }
        return toMachineDtoWithStats(saved);
    }

    public MachineDto toggleMachine(Long id, boolean active) {
        Machine m = machineRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy máy"));
        m.setStatus(active ? Machine.MachineStatus.ACTIVE : Machine.MachineStatus.INACTIVE);
        return toMachineDtoWithStats(machineRepo.save(m));
    }

    // ─── MaintenanceSchedule ──────────────────────────────────────────────────

    public List<MaintenanceDto> listMaintenance(int year, Long machineId) {
        long fromMs = yearStart(year);
        long toMs = yearEnd(year);
        List<MaintenanceSchedule> list = machineId != null
                ? maintenanceRepo.findByYearRangeAndMachine(fromMs, toMs, machineId)
                : maintenanceRepo.findByYearRange(fromMs, toMs);
        return list.stream().map(this::toMaintenanceDto).collect(Collectors.toList());
    }

    public MaintenanceDto createMaintenance(CreateMaintenanceRequest req, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        Machine machine = machineRepo.findById(req.getMachineId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy máy"));

        MaintenanceSchedule m = MaintenanceSchedule.builder()
                .machine(machine)
                .machineName(machine.getName())
                .maintenanceType(MaintenanceSchedule.MaintenanceType.valueOf(req.getMaintenanceType()))
                .recurrenceType(req.getRecurrenceType() != null
                        ? MaintenanceSchedule.RecurrenceType.valueOf(req.getRecurrenceType()) : null)
                .title(req.getTitle())
                .description(req.getDescription())
                .plannedStart(req.getPlannedStart())
                .plannedEnd(req.getPlannedEnd())
                .plannedDowntimeHours(req.getPlannedDowntimeHours())
                .recurrenceDay(req.getRecurrenceDay())
                .recurrenceMonthInQuarter(req.getRecurrenceMonthInQuarter())
                .vendorName(req.getVendorName())
                .vendorContactPerson(req.getVendorContactPerson())
                .vendorPhone(req.getVendorPhone())
                .estimatedCost(req.getEstimatedCost())
                .beforeImages(toJson(req.getBeforeImages()))
                .status(MaintenanceSchedule.MaintenanceStatus.PLANNED)
                .createdBy(worker)
                .createdByName(worker.getFullName())
                .build();

        MaintenanceSchedule saved = maintenanceRepo.save(m);

        // ── Generate recurrence instances (12 months ahead for MONTHLY, 4 quarters for QUARTERLY, 3 years for YEARLY)
        if (saved.getRecurrenceType() != null && saved.getRecurrenceType() != MaintenanceSchedule.RecurrenceType.ONCE
                && saved.getPlannedStart() != null) {
            java.time.ZonedDateTime base = java.time.Instant.ofEpochMilli(saved.getPlannedStart()).atZone(VN);
            java.time.ZonedDateTime baseEnd = saved.getPlannedEnd() != null
                    ? java.time.Instant.ofEpochMilli(saved.getPlannedEnd()).atZone(VN) : base.plusHours(4);
            long durationMs = baseEnd.toInstant().toEpochMilli() - base.toInstant().toEpochMilli();

            int instances = switch (saved.getRecurrenceType()) {
                case MONTHLY -> 11;    // 11 more months = 12 total
                case QUARTERLY -> 3;   // 3 more quarters = 4 total
                case YEARLY -> 2;      // 2 more years = 3 total
                default -> 0;
            };

            for (int i = 1; i <= instances; i++) {
                java.time.ZonedDateTime next = switch (saved.getRecurrenceType()) {
                    case MONTHLY -> base.plusMonths(i);
                    case QUARTERLY -> base.plusMonths(i * 3L);
                    case YEARLY -> base.plusYears(i);
                    default -> base;
                };
                long nextStart = next.toInstant().toEpochMilli();
                long nextEnd = nextStart + durationMs;
                MaintenanceSchedule instance = MaintenanceSchedule.builder()
                        .machine(machine).machineName(machine.getName())
                        .maintenanceType(saved.getMaintenanceType())
                        .recurrenceType(saved.getRecurrenceType())
                        .title(saved.getTitle()).description(saved.getDescription())
                        .plannedStart(nextStart).plannedEnd(nextEnd)
                        .plannedDowntimeHours(saved.getPlannedDowntimeHours())
                        .recurrenceDay(saved.getRecurrenceDay())
                        .vendorName(saved.getVendorName()).vendorContactPerson(saved.getVendorContactPerson()).vendorPhone(saved.getVendorPhone())
                        .estimatedCost(saved.getEstimatedCost())
                        .status(MaintenanceSchedule.MaintenanceStatus.PLANNED)
                        .createdBy(saved.getCreatedBy()).createdByName(saved.getCreatedByName())
                        .build();
                maintenanceRepo.save(instance);
            }
        }

        // Notify OWNER + SUPER_ACCOUNTANT
        String msg = String.format("[%s] %s — %s, máy: %s",
                MaintenanceSchedule.MaintenanceType.valueOf(req.getMaintenanceType()) == MaintenanceSchedule.MaintenanceType.CORRECTIVE
                        ? "Sự cố" : "Bảo trì định kỳ",
                req.getTitle(), formatDate(req.getPlannedStart()), machine.getName());
        String payload = "{\"maintenanceId\":" + saved.getId() + "}";
        long nowMs = System.currentTimeMillis();
        // Only set UNDER_MAINTENANCE if the maintenance window covers NOW
        boolean isActiveNow = saved.getPlannedStart() != null && saved.getPlannedEnd() != null
                && nowMs >= saved.getPlannedStart() && nowMs <= saved.getPlannedEnd();
        if (isActiveNow) {
            machine.setStatus(Machine.MachineStatus.UNDER_MAINTENANCE);
            machineRepo.save(machine);
        }

        // Full notification with details
        String detailedMsg = String.format("[%s] %s — Máy: %s | Đơn vị: %s | Bắt đầu: %s | Dự kiến xong: %s",
                saved.getMaintenanceType() == MaintenanceSchedule.MaintenanceType.CORRECTIVE ? "🚨 Sự cố" : "🔧 Bảo trì định kỳ",
                saved.getTitle(), machine.getName(),
                saved.getVendorName() != null ? saved.getVendorName() : "Chưa xác định",
                formatDate(saved.getPlannedStart()), formatDate(saved.getPlannedEnd()));

        notificationService.sendToRole("OWNER", "MAINTENANCE_CREATED", detailedMsg, payload);
        notificationService.sendToRole("SUPER_ACCOUNTANT", "MAINTENANCE_CREATED", detailedMsg, payload);
        // PREVENTIVE also notifies FACTORY_WORKER
        if (saved.getMaintenanceType() == MaintenanceSchedule.MaintenanceType.PREVENTIVE) {
            notificationService.sendToRole("FACTORY_WORKER", "MAINTENANCE_CREATED", detailedMsg, payload);
        }

        return toMaintenanceDto(saved);
    }

    public MaintenanceDto completeMaintenance(Long id, CompleteMaintenanceRequest req, String username) {
        MaintenanceSchedule m = maintenanceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lịch bảo trì"));
        if (req.getActualStart() != null) m.setActualStart(req.getActualStart());
        if (req.getActualEnd() != null) m.setActualEnd(req.getActualEnd()); else m.setActualEnd(System.currentTimeMillis());
        if (req.getActualDowntimeHours() != null) m.setActualDowntimeHours(req.getActualDowntimeHours());
        if (req.getActualCost() != null) m.setActualCost(req.getActualCost());
        if (req.getNotes() != null) m.setCompletionNotes(req.getNotes());
        if (req.getAfterImages() != null) m.setAfterImages(toJson(req.getAfterImages()));
        if (req.getReceiptImages() != null) m.setReceiptImages(toJson(req.getReceiptImages()));

        long diffDays = req.getActualStart() != null
                ? Math.abs(req.getActualStart() - m.getPlannedStart()) / ONE_DAY_MS : 0;
        m.setStatus(diffDays <= 1
                ? MaintenanceSchedule.MaintenanceStatus.COMPLETED
                : MaintenanceSchedule.MaintenanceStatus.ADJUSTED);

        // Khôi phục trạng thái máy → ACTIVE
        Machine machine = m.getMachine();
        if (machine != null && machine.getStatus() == Machine.MachineStatus.UNDER_MAINTENANCE) {
            machine.setStatus(Machine.MachineStatus.ACTIVE);
            machineRepo.save(machine);
        }

        // WS notify completion with cost
        String costStr = req.getActualCost() != null
                ? String.format("%,.0f đ", req.getActualCost()) : "chưa cập nhật";
        String doneMsg = String.format("Bảo trì hoàn thành: %s — Máy: %s — Chi phí: %s",
                m.getTitle(), m.getMachineName(), costStr);
        String donePayload = "{\"maintenanceId\":" + m.getId() + "}";
        notificationService.sendToRole("OWNER", "MAINTENANCE_COMPLETED", doneMsg, donePayload);
        notificationService.sendToRole("SUPER_ACCOUNTANT", "MAINTENANCE_COMPLETED", doneMsg, donePayload);

        return toMaintenanceDto(maintenanceRepo.save(m));
    }

    public WorkOrderDto extendWorkOrder(Long id, ExtendWorkOrderRequest req) {
        WorkOrder wo = workOrderRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh sản xuất"));
        wo.setPlannedEndDate(req.getNewEndDate());
        wo.setExtendReason(req.getReason());
        workOrderRepo.save(wo);
        // Notify factory workers
        notificationService.sendToRole("FACTORY_WORKER", "WORK_ORDER_EXTENDED",
                String.format("Lệnh %s đã được gia hạn đến %s. Lý do: %s",
                        wo.getWorkOrderCode(), formatDate(req.getNewEndDate()), req.getReason()),
                "{\"workOrderId\":" + wo.getId() + "}");
        return toWorkOrderDto(wo);
    }

    public MachineDto getMachineDto(Long id) {
        Machine m = machineRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Máy không tồn tại"));
        return toMachineDto(m);
    }

    public MachineDto saveWorkSchedule(Long machineId, SaveWorkScheduleRequest req) {
        Machine machine = machineRepo.findById(machineId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy máy"));
        MachineWorkSchedule schedule = workScheduleRepo.findByMachine_Id(machineId)
                .orElse(MachineWorkSchedule.builder().machine(machine).build());
        schedule.setActiveWeekdays(req.getActiveWeekdays());
        schedule.setStartHour(req.getStartHour());
        schedule.setEndHour(req.getEndHour());
        workScheduleRepo.save(schedule);
        return toMachineDto(machine);
    }

    private MachineDto toMachineDto(Machine m) { return toMachineDtoWithStats(m); }

    public void deleteMaintenance(Long id) {
        if (!maintenanceRepo.existsById(id)) throw new ResourceNotFoundException("Không tìm thấy lịch bảo trì");
        maintenanceRepo.deleteById(id);
    }

    // ─── Dashboard ────────────────────────────────────────────────────────────

    public ProductionDashboardDto getDashboard() {
        long now = System.currentTimeMillis();
        long monthStart = LocalDate.now(VN).withDayOfMonth(1).atStartOfDay(VN).toInstant().toEpochMilli();
        long monthEnd = LocalDate.now(VN).plusMonths(1).withDayOfMonth(1).atStartOfDay(VN).toInstant().toEpochMilli();
        long thirtyDays = now + 30L * ONE_DAY_MS;

        // Plans — 3 tháng trước đến 6 tháng tới
        long threeMonthsAgoPlan = LocalDate.now(VN).minusMonths(3).withDayOfMonth(1)
                .atStartOfDay(VN).toInstant().toEpochMilli();
        long sixMonthsAheadPlan = LocalDate.now(VN).plusMonths(6).withDayOfMonth(1)
                .atStartOfDay(VN).toInstant().toEpochMilli();
        List<ProductionPlan> activePlans = planRepo.findByDateRangeExtended(threeMonthsAgoPlan, sixMonthsAheadPlan);

        // WorkOrder stats
        long totalWO = workOrderRepo.count();
        long inProgress = workOrderRepo.countByStatus(WorkOrder.WorkOrderStatus.IN_PROGRESS);
        long completed = workOrderRepo.countByStatus(WorkOrder.WorkOrderStatus.COMPLETED);
        long scheduled = workOrderRepo.countByStatus(WorkOrder.WorkOrderStatus.SCHEDULED);
        long pendingPlan = workOrderRepo.countByStatus(WorkOrder.WorkOrderStatus.PENDING_PLAN);

        // Calendar items — 3 tháng trước đến 6 tháng tới
        long threeMonthsAgo = LocalDate.now(VN).minusMonths(3).withDayOfMonth(1)
                .atStartOfDay(VN).toInstant().toEpochMilli();
        long sixMonthsAhead = LocalDate.now(VN).plusMonths(6).withDayOfMonth(1)
                .atStartOfDay(VN).toInstant().toEpochMilli();
        List<WorkOrder> calendarWOs = workOrderRepo.findByDateRangeExtended(threeMonthsAgo, sixMonthsAhead);
        List<WorkOrderCalendarItem> calItems = calendarWOs.stream()
                .map(this::toCalendarItem).collect(Collectors.toList());

        // Machines
        List<Machine> allMachines = machineRepo.findAllByOrderByNameAsc();
        long activeMachines = allMachines.stream()
                .filter(m -> m.getStatus() == Machine.MachineStatus.ACTIVE).count();
        long underMaint = allMachines.stream()
                .filter(m -> m.getStatus() == Machine.MachineStatus.UNDER_MAINTENANCE).count();

        // Upcoming maintenance
        List<MaintenanceSchedule> upcoming = maintenanceRepo.findUpcoming(now, thirtyDays);

        // Batch this month
        long batchCount = batchRepo.countByPeriod(monthStart, monthEnd);

        // Tổng sản lượng đã hoàn thành — card "Tổng sản lượng" trên dashboard
        java.math.BigDecimal totalOutput = workOrderRepo.sumAllCompletedOutput();
        if (totalOutput == null) totalOutput = java.math.BigDecimal.ZERO;

        java.math.BigDecimal outputThisMonth = workOrderRepo.sumCompletedOutputBetween(monthStart, monthEnd);
        if (outputThisMonth == null) outputThisMonth = java.math.BigDecimal.ZERO;

        return ProductionDashboardDto.builder()
                .totalActivePlans(activePlans.size())
                .totalWorkOrders((int) totalWO)
                .inProgressOrders((int) inProgress)
                .completedOrders((int) completed)
                .scheduledOrders((int) scheduled)
                .pendingPlanOrders((int) pendingPlan)
                .totalCompletedOutput(totalOutput)
                .completedOutputThisMonth(outputThisMonth)
                .recentPlans(activePlans.stream().map(this::toPlanDto).collect(Collectors.toList()))
                .calendarItems(calItems)
                .totalMachines(allMachines.size())
                .activeMachines((int) activeMachines)
                .underMaintenanceMachines((int) underMaint)
                .machines(allMachines.stream().map(this::toMachineDtoWithStats).collect(Collectors.toList()))
                .upcomingMaintenance(upcoming.stream().map(this::toMaintenanceDto).collect(Collectors.toList()))
                .totalBatchesThisMonth(batchCount)
                .cancelledBatchesThisMonth(0) // TODO: add cancelled count query
                .build();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private long yearStart(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay(VN).toInstant().toEpochMilli();
    }

    private long yearEnd(int year) {
        return LocalDate.of(year + 1, 1, 1).atStartOfDay(VN).toInstant().toEpochMilli();
    }

    private String generatePlanCode() {
        String prefix = "PLAN-" + LocalDate.now(VN).format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        long seq = planRepo.countByPlanCodePrefix(prefix) + 1;
        return prefix + String.format("%03d", seq);
    }

    private String generateWorkOrderCode() {
        String prefix = "WO-" + LocalDate.now(VN).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        long seq = workOrderRepo.countByWorkOrderCodeStartingWith(prefix) + 1;
        return prefix + String.format("%04d", seq);
    }

    private String generateBatchCode() {
        String prefix = "BATCH-" + LocalDate.now(VN).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        long seq = batchRepo.countByBatchCodePrefix(prefix) + 1;
        return prefix + String.format("%04d", seq);
    }

    private BigDecimal calcProgressPct(BigDecimal accumulated, BigDecimal planned) {
        if (planned == null || planned.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        if (accumulated == null) return BigDecimal.ZERO;
        return accumulated.divide(planned, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
    }

    private String colorLevel(BigDecimal pct) {
        if (pct == null) return "0-25";
        double v = pct.doubleValue();
        if (v > 100) return "over";
        if (v >= 100) return "100";
        if (v >= 75) return "76-99";
        if (v >= 50) return "51-75";
        if (v >= 25) return "26-50";
        return "0-25";
    }

    private String formatDate(Long ms) {
        if (ms == null) return "—";
        return new java.util.Date(ms).toInstant().atZone(VN)
                .format(DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));
    }

    @SuppressWarnings("unchecked")
    private String toJson(Object obj) {
        if (obj == null) return "[]";
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "[]"; }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private ProductionPlanDto toPlanDto(ProductionPlan p) {
        List<WorkOrder> wos = workOrderRepo.findByProductionPlan_IdOrderByCreatedAtDesc(p.getId());

        // "Đã lên lệnh" (dùng để check vượt kế hoạch & hiển thị UI):
        // - Lệnh PENDING/PLANNED/IN_PROGRESS/SCHEDULED → dùng plannedQty (chưa biết thực tế)
        // - Lệnh COMPLETED → dùng accumulatedQty thực tế
        // - Lệnh CANCELLED → bỏ qua
        BigDecimal totalPlanned = wos.stream()
                .filter(w -> w.getStatus() != WorkOrder.WorkOrderStatus.CANCELLED)
                .map(w -> {
                    if (w.getStatus() == WorkOrder.WorkOrderStatus.COMPLETED) {
                        return w.getAccumulatedQty() != null ? w.getAccumulatedQty() : BigDecimal.ZERO;
                    }
                    return w.getPlannedQty() != null ? w.getPlannedQty() : BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // "Sản lượng thực tế" = tổng accumulatedQty từ các mẻ đã hoàn thành
        BigDecimal actualAccumulated = wos.stream()
                .map(w -> w.getAccumulatedQty() != null ? w.getAccumulatedQty() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long completedWOs   = wos.stream().filter(w -> w.getStatus() == WorkOrder.WorkOrderStatus.COMPLETED).count();
        long inProgressWOs  = wos.stream().filter(w -> w.getStatus() == WorkOrder.WorkOrderStatus.IN_PROGRESS).count();

        // Lấy danh sách sản phẩm từ production_plan_product
        List<com.nhatnam.server.entity.ProductionPlanProduct> planProds =
                planProductRepo.findByProductionPlan_IdOrderBySortOrderAsc(p.getId());

        List<Long> productIds;
        List<String> productNames;
        if (!planProds.isEmpty()) {
            productIds = planProds.stream()
                    .map(pp -> pp.getFactoryProduct().getId())
                    .collect(java.util.stream.Collectors.toList());
            productNames = planProds.stream()
                    .map(pp -> pp.getFactoryProduct().getName())
                    .collect(java.util.stream.Collectors.toList());
        } else {
            // Fallback về sản phẩm chính nếu chưa có bảng planProduct
            productIds = List.of(p.getFactoryProduct().getId());
            productNames = List.of(p.getProductName());
        }

        return ProductionPlanDto.builder()
                .id(p.getId()).planCode(p.getPlanCode()).title(p.getTitle())
                .factoryProductId(p.getFactoryProduct().getId())
                .productName(p.getProductName()).outputUnit(p.getOutputUnit())
                .productionFactoryId(p.getProductionFactory() != null ? p.getProductionFactory().getId() : null)
                .productionFactoryName(p.getProductionFactoryName())
                .targetQty(p.getTargetQty()).startDate(p.getStartDate()).endDate(p.getEndDate())
                .status(p.getStatus().name()).notes(p.getNotes())
                .createdByName(p.getCreatedByName()).createdAt(p.getCreatedAt())
                .factoryProductIds(productIds)
                .factoryProductNames(productNames)
                .accumulatedQty(totalPlanned)
                .progressPct(calcProgressPct(actualAccumulated, p.getTargetQty()))
                .totalWorkOrders(wos.size())
                .completedWorkOrders((int) completedWOs)
                .inProgressWorkOrders((int) inProgressWOs)
                .build();
    }

    private WorkOrderDto toWorkOrderDto(WorkOrder w) {
        List<ProductionBatch> batches = batchRepo.findByWorkOrder_IdOrderByBatchNumberAsc(w.getId());
        long totalB      = batches.size();
        long completedB  = batches.stream().filter(b -> b.getStatus() == ProductionBatch.BatchStatus.COMPLETED).count();
        long cancelledB  = batches.stream().filter(b -> b.getStatus() == ProductionBatch.BatchStatus.CANCELLED).count();
        long inProgressB = batches.stream().filter(b -> b.getStatus() == ProductionBatch.BatchStatus.IN_PROGRESS).count();

        WorkOrderPlan plan = w.getWorkOrderPlan();

        // Tính canInputMaterials: scheduledMode=false → luôn cho phép
        // scheduledMode=true → chỉ khi còn <= 3 ngày đến scheduledStartDate
        boolean canInputMats = true;
        if (Boolean.TRUE.equals(w.getScheduledMode()) && w.getScheduledStartDate() != null) {
            long threeDaysBefore = w.getScheduledStartDate() - (3L * 24 * 60 * 60 * 1000);
            canInputMats = System.currentTimeMillis() >= threeDaysBefore;
        }

        return WorkOrderDto.builder()
                .id(w.getId()).workOrderCode(w.getWorkOrderCode())
                .productionPlanId(w.getProductionPlan() != null ? w.getProductionPlan().getId() : null)
                .planTitle(w.getProductionPlan() != null ? w.getProductionPlan().getTitle() : null)
                .factoryProductId(w.getFactoryProduct() != null ? w.getFactoryProduct().getId() : null)
                .productName(w.getProductName()).outputUnit(w.getOutputUnit())
                .plannedQty(w.getPlannedQty())
                .accumulatedQty(w.getAccumulatedQty() != null ? w.getAccumulatedQty() : BigDecimal.ZERO)
                .progressPct(calcProgressPct(w.getAccumulatedQty(), w.getPlannedQty()))
                .scheduledStartDate(w.getScheduledStartDate())
                .planDeadline(w.getPlanDeadline())
                .plannedEndDate(w.getPlannedEndDate())
                .actualStartDate(w.getActualStartDate())
                .actualEndDate(w.getActualEndDate())
                .status(w.getStatus().name()).notes(w.getNotes())
                .createdByName(w.getCreatedByName()).createdAt(w.getCreatedAt())
                .totalBatches((int) totalB)
                .completedBatches((int) completedB)
                .cancelledBatches((int) cancelledB)
                .inProgressBatches((int) inProgressB)
                .hasPlan(plan != null)
                .planTotalBatches(plan != null ? plan.getTotalBatches() : null)
                .planBatchQtyPerRun(plan != null ? plan.getBatchQtyPerRun() : null)
                .productionFactoryId(w.getProductionFactory() != null ? w.getProductionFactory().getId() : null)
                .productionFactoryName(w.getProductionFactoryName())
                .scheduledMode(w.getScheduledMode())
                .canInputMaterials(canInputMats)
                .packagingLoss(calcWorkOrderLoss(batches))
                .build();
    }

    private WorkOrderCalendarItem toCalendarItem(WorkOrder w) {
        BigDecimal pct = calcProgressPct(w.getAccumulatedQty(), w.getPlannedQty());
        return WorkOrderCalendarItem.builder()
                .id(w.getId()).workOrderCode(w.getWorkOrderCode())
                .productName(w.getProductName())
                .scheduledStartDate(w.getScheduledStartDate())
                .plannedEndDate(w.getPlannedEndDate())
                .plannedQty(w.getPlannedQty())
                .accumulatedQty(w.getAccumulatedQty() != null ? w.getAccumulatedQty() : BigDecimal.ZERO)
                .progressPct(pct)
                .status(w.getStatus().name())
                .colorLevel(colorLevel(pct))
                .productionPlanId(w.getProductionPlan() != null ? w.getProductionPlan().getId() : null)
                .planCode(w.getProductionPlan() != null ? w.getProductionPlan().getPlanCode() : null)
                .build();
    }

    private WorkOrderPlanDto toPlanDto(WorkOrderPlan p) {
        List<PlanMaterialDto> mats = p.getMaterials() == null ? List.of()
                : p.getMaterials().stream().sorted(Comparator.comparingInt(m -> (m.getSortOrder() == null ? 0 : m.getSortOrder())))
                .map(m -> PlanMaterialDto.builder()
                        .id(m.getId()).materialName(m.getMaterialName())
                        .quantity(m.getQuantity()).unit(m.getUnit())
                        .vendorName(m.getVendorName()).vendorPhone(m.getVendorPhone())
                        .estimatedUnitPrice(m.getEstimatedUnitPrice())
                        .estimatedTotal(m.getEstimatedTotal())
                        .invoiceImages(fromJsonList(m.getInvoiceImages()))
                        .sortOrder(m.getSortOrder() != null ? m.getSortOrder() : 0)
                        .build())
                .collect(Collectors.toList());

        BigDecimal totalCost = mats.stream()
                .map(m -> m.getEstimatedTotal() != null ? m.getEstimatedTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<String> steps = fromJsonList(p.getBatchSteps());

        // Parse batchQtyPerRunList từ JSON (được lưu khi submitPlanByRecipe)
        List<BigDecimal> batchQtyPerRunList = null;
        if (p.getBatchQtyPerRunList() != null) {
            try {
                batchQtyPerRunList = objectMapper.readValue(p.getBatchQtyPerRunList(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, BigDecimal.class));
            } catch (Exception ignored) {}
        }

        return WorkOrderPlanDto.builder()
                .id(p.getId()).workOrderId(p.getWorkOrder().getId())
                .recipeId(p.getRecipe() != null ? p.getRecipe().getId() : null)
                .recipeName(p.getRecipe() != null ? p.getRecipe().getName() : null)
                .requestedQty(p.getRequestedQty())
                .totalBatches(p.getTotalBatches()).batchQtyPerRun(p.getBatchQtyPerRun())
                .batchQtyPerRunList(batchQtyPerRunList)
                .batchSteps(steps).notes(p.getNotes())
                .submittedByName(p.getSubmittedByName()).submittedAt(p.getSubmittedAt())
                .materials(mats).totalEstimatedCost(totalCost)
                .build();
    }

    private ProductionBatchDto toBatchDto(ProductionBatch b) {
        // Các công đoạn phủ mẻ này (bước chung + bước riêng của đúng mẻ) — dùng cho hiển thị tiến độ mẻ
        Long woId = b.getWorkOrder() != null ? b.getWorkOrder().getId() : null;
        List<WorkOrderStep> allWoSteps = woId != null
                ? woStepRepo.findByWorkOrder_IdOrderByStageSequenceAscRunNumberAsc(woId)
                : List.of();
        List<BatchStepDto> steps = allWoSteps.stream()
                .filter(s -> s.isShared() || java.util.Objects.equals(s.getBatchNumber(), b.getBatchNumber()))
                .map(this::stageRunToBatchStepDto)
                .collect(Collectors.toList());
        long completedSteps = steps.stream().filter(s -> "COMPLETED".equals(s.getStatus())).count();
        BigDecimal stepPct = steps.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(completedSteps * 100.0 / steps.size()).setScale(1, RoundingMode.HALF_UP);

        BigDecimal stdOutput = b.getRecipe() != null ? b.getRecipe().getStandardOutputQty() : BigDecimal.ZERO;
        BigDecimal variancePct = BigDecimal.ZERO;
        if (b.getActualOutputQty() != null && stdOutput.compareTo(BigDecimal.ZERO) != 0) {
            variancePct = b.getActualOutputQty().subtract(stdOutput)
                    .divide(stdOutput, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        }

        BatchCancellationDto cancelDto = null;
        if (b.getCancellation() != null) {
            BatchCancellation c = b.getCancellation();
            cancelDto = BatchCancellationDto.builder()
                    .id(c.getId()).reason(c.getReason())
                    .attachments(fromJsonList(c.getAttachments()))
                    .resolution(c.getResolution().name())
                    .resolutionNotes(c.getResolutionNotes())
                    .cancelledByName(c.getCancelledByName())
                    .cancelledAt(c.getCancelledAt())
                    .build();
        }

        // Nguyên liệu riêng của mẻ này — lấy từ phương án (đã tính sẵn theo batchNumber)
        List<MaterialLineDto> batchMaterials = List.of();
        if (b.getWorkOrder() != null && b.getWorkOrder().getWorkOrderPlan() != null) {
            batchMaterials = planBatchMaterialRepo
                    .findByPlan_IdAndBatchNumberOrderBySortOrderAsc(
                            b.getWorkOrder().getWorkOrderPlan().getId(), b.getBatchNumber())
                    .stream()
                    .map(m -> MaterialLineDto.builder()
                            .factoryMaterialId(m.getFactoryMaterial() != null ? m.getFactoryMaterial().getId() : null)
                            .materialName(m.getMaterialName())
                            .unit(m.getUnit())
                            .qty(m.getQty())
                            .build())
                    .collect(Collectors.toList());
        }

        return ProductionBatchDto.builder()
                .id(b.getId()).batchCode(b.getBatchCode())
                .workOrderId(b.getWorkOrder() != null ? b.getWorkOrder().getId() : null)
                .workOrderCode(b.getWorkOrder() != null ? b.getWorkOrder().getWorkOrderCode() : null)
                .batchNumber(b.getBatchNumber())
                .recipeId(b.getRecipe() != null ? b.getRecipe().getId() : null)
                .productName(b.getProductName()).recipeName(b.getRecipeName())
                .actualOutputQty(b.getActualOutputQty()).standardOutputQty(stdOutput)
                .outputVariancePct(variancePct).outputUnit(b.getOutputUnit())
                .scrapQty(b.getScrapQty()).scrapReason(b.getScrapReason())
                .materialCost(b.getMaterialCost()).unitCost(b.getUnitCost())
                .producedAt(b.getProducedAt()).notes(b.getNotes())
                .status(b.getStatus().name())
                .createdByName(b.getCreatedByName()).createdAt(b.getCreatedAt())
                .totalSteps(steps.size()).completedSteps((int) completedSteps)
                .stepProgressPct(stepPct)
                .steps(steps)
                .cancellation(cancelDto)
                .batchMaterials(batchMaterials)
                .build();
    }

    private BatchStepDto toStepDto(BatchStep s) {
        return BatchStepDto.builder()
                .id(s.getId()).stepSequence(s.getStepSequence()).stepName(s.getStepName())
                .status(s.getStatus().name())
                .requiresQc(s.isRequiresQc())
                .controlType((s.getControlType() != null ? s.getControlType()
                        : (s.isRequiresQc() ? ProductionRecipeStep.ControlType.PHOTO_WEIGHT : ProductionRecipeStep.ControlType.NONE)).name())
                .machineId(s.getMachine() != null ? s.getMachine().getId() : null)
                .machineName(s.getMachineName())
                .durationMinutes(s.getDurationMinutes())
                .startedByName(s.getStartedByName())
                .startedAt(s.getStartedAt())
                .attachments(fromJsonList(s.getAttachments()))
                .notes(s.getNotes()).completedByName(s.getCompletedByName())
                .completedAt(s.getCompletedAt())
                .build();
    }

    /** Map 1 lần chạy công đoạn → BatchStepDto (để hiển thị tiến độ trong 1 mẻ, read-only). */
    private BatchStepDto stageRunToBatchStepDto(WorkOrderStep s) {
        String name = s.getStageName();
        if (s.isShared()) {
            String qty = s.getRunQty() != null ? s.getRunQty().stripTrailingZeros().toPlainString() + "kg" : "";
            name = s.getTotalRuns() != null && s.getTotalRuns() > 1
                    ? name + " (chung " + s.getRunNumber() + "/" + s.getTotalRuns() + (qty.isEmpty() ? "" : " · " + qty) + ")"
                    : name + " (chung" + (qty.isEmpty() ? "" : " · " + qty) + ")";
        }
        return BatchStepDto.builder()
                .id(s.getId())
                .stepSequence(s.getStageSequence() != null ? s.getStageSequence() : 0)
                .stepName(name)
                .status(s.getStatus().name())
                .requiresQc(s.isRequiresQc())
                .controlType(s.getControlType() != null ? s.getControlType().name() : "NONE")
                .machineId(s.getMachine() != null ? s.getMachine().getId() : null)
                .machineName(s.getMachineName())
                .durationMinutes(s.getDurationMinutes())
                .startedByName(s.getStartedByName())
                .startedAt(s.getStartedAt())
                .attachments(fromJsonList(s.getAttachments()))
                .notes(s.getNotes())
                .damagedQty(s.getDamagedQty())
                .completedByName(s.getCompletedByName())
                .completedAt(s.getCompletedAt())
                .build();
    }

    private MachineDto toMachineDtoWithStats(Machine m) {
        BigDecimal totalCost = maintenanceRepo.sumActualCostByMachine(m.getId());
        List<MaintenanceSchedule> upcoming = maintenanceRepo
                .findUpcoming(System.currentTimeMillis(), System.currentTimeMillis() + 30L * ONE_DAY_MS)
                .stream().filter(ms -> ms.getMachine().getId().equals(m.getId())).collect(Collectors.toList());
        MaintenanceSchedule nextMaint = upcoming.isEmpty() ? null : upcoming.get(0);

        return MachineDto.builder()
                .id(m.getId()).name(m.getName())
                .capacityHoursPerMonth(m.getCapacityHoursPerMonth())
                .status(m.getStatus().name()).description(m.getDescription())
                .purchaseDate(m.getPurchaseDate()).purchaseCost(m.getPurchaseCost())
                .manufacturer(m.getManufacturer()).serialNumber(m.getSerialNumber())
                .createdAt(m.getCreatedAt())
                .totalMaintenanceCost(totalCost)
                .totalMaintenanceCount(0)
                .nextMaintenanceDate(nextMaint != null ? nextMaint.getPlannedStart() : null)
                .nextMaintenanceTitle(nextMaint != null ? nextMaint.getTitle() : null)
                .workSchedule(workScheduleRepo.findByMachine_Id(m.getId()).map(ws ->
                        new MachineWorkScheduleDto(ws.getActiveWeekdays(), ws.getStartHour(), ws.getEndHour())
                ).orElse(null))
                .factoryId(m.getFactory() != null ? m.getFactory().getId() : null)
                .factoryName(m.getFactoryName())
                .build();
    }

    // ─── Machine Metrics (Issue #4: trang quản lý metric máy) ──────────────────

    public MachineMetricsDto getMachineMetrics(Long machineId) {
        Machine machine = machineRepo.findById(machineId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy máy"));

        long now = System.currentTimeMillis();

        // ── Hoạt động sản xuất: tất cả lần chạy công đoạn đã từng chạy trên máy này ──
        List<WorkOrderStep> steps = woStepRepo.findAllByMachineIdOrderByStartedAt(machineId);

        Long firstProductionAt = steps.isEmpty() ? null : steps.get(0).getStartedAt();
        Long lastProductionAt = null;
        BigDecimal totalProdMs = BigDecimal.ZERO;
        // Gom giờ sản xuất theo tháng (yyyy-MM) để vẽ chart
        Map<String, BigDecimal> prodByMonth = new TreeMap<>();
        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("yyyy-MM");

        for (WorkOrderStep s : steps) {
            if (s.getStartedAt() == null) continue;
            long end = s.getCompletedAt() != null ? s.getCompletedAt() : now; // đang chạy → tính đến hiện tại
            long durMs = Math.max(0, end - s.getStartedAt());
            totalProdMs = totalProdMs.add(BigDecimal.valueOf(durMs));
            if (lastProductionAt == null || s.getStartedAt() > lastProductionAt) lastProductionAt = s.getStartedAt();

            String monthKey = java.time.Instant.ofEpochMilli(s.getStartedAt()).atZone(VN).format(monthFmt);
            prodByMonth.merge(monthKey, BigDecimal.valueOf(durMs), BigDecimal::add);
        }
        BigDecimal totalProductionHours = totalProdMs
                .divide(BigDecimal.valueOf(3_600_000L), 2, RoundingMode.HALF_UP);

        // ── Bảo trì/hư hỏng: toàn bộ lịch sử của máy ─────────────────────────────
        List<MaintenanceSchedule> maintenanceList = maintenanceRepo.findByMachineId(machineId);
        // Mới nhất trước
        maintenanceList = maintenanceList.stream()
                .sorted(Comparator.comparing(MaintenanceSchedule::getPlannedStart).reversed())
                .collect(Collectors.toList());

        BigDecimal totalMaintMs = BigDecimal.ZERO;
        BigDecimal totalCompletedCost = BigDecimal.ZERO;
        int completedCount = 0, activeCount = 0;
        Map<String, BigDecimal> maintByMonth = new TreeMap<>();

        for (MaintenanceSchedule m : maintenanceList) {
            boolean isCompleted = m.getStatus() == MaintenanceSchedule.MaintenanceStatus.COMPLETED;
            boolean isCancelledLike = m.getStatus() == MaintenanceSchedule.MaintenanceStatus.MISSED;
            if (isCompleted) {
                completedCount++;
                if (m.getActualCost() != null) totalCompletedCost = totalCompletedCost.add(m.getActualCost());
            } else if (!isCancelledLike) {
                activeCount++;
            }

            // Thời gian downtime: nếu đã hoàn tất dùng actualStart→actualEnd thực tế,
            // nếu chưa xử lý xong (PLANNED/IN_PROGRESS/ADJUSTED) dùng plannedStart→plannedEnd (dự kiến lúc tạo phiếu)
            if (isCancelledLike) continue;
            long mStart = m.getActualStart() != null ? m.getActualStart() : m.getPlannedStart();
            long mEnd;
            if (isCompleted) {
                mEnd = m.getActualEnd() != null ? m.getActualEnd() : m.getPlannedEnd();
            } else {
                // Chưa xử lý xong → kết thúc lấy thời gian dự kiến khi tạo phiếu
                mEnd = m.getPlannedEnd();
            }
            if (mEnd < mStart) mEnd = mStart;
            long durMs = mEnd - mStart;
            totalMaintMs = totalMaintMs.add(BigDecimal.valueOf(durMs));

            String monthKey = java.time.Instant.ofEpochMilli(mStart).atZone(VN).format(monthFmt);
            maintByMonth.merge(monthKey, BigDecimal.valueOf(durMs), BigDecimal::add);
        }
        BigDecimal totalMaintenanceHours = totalMaintMs
                .divide(BigDecimal.valueOf(3_600_000L), 2, RoundingMode.HALF_UP);

        // ── Gộp chart theo tháng (union của 2 map, sắp theo thời gian tăng dần) ──
        Set<String> allMonths = new TreeSet<>();
        allMonths.addAll(prodByMonth.keySet());
        allMonths.addAll(maintByMonth.keySet());
        List<MachineMonthlyMetricDto> monthlyChart = allMonths.stream()
                .map(mo -> MachineMonthlyMetricDto.builder()
                        .month(mo)
                        .productionHours(prodByMonth.getOrDefault(mo, BigDecimal.ZERO)
                                .divide(BigDecimal.valueOf(3_600_000L), 2, RoundingMode.HALF_UP))
                        .maintenanceHours(maintByMonth.getOrDefault(mo, BigDecimal.ZERO)
                                .divide(BigDecimal.valueOf(3_600_000L), 2, RoundingMode.HALF_UP))
                        .build())
                .collect(Collectors.toList());

        return MachineMetricsDto.builder()
                .machineId(machine.getId())
                .machineName(machine.getName())
                .status(machine.getStatus().name())
                .factoryName(machine.getFactoryName())
                .purchaseDate(machine.getPurchaseDate() != null ? machine.getPurchaseDate() : machine.getCreatedAt())
                .firstProductionAt(firstProductionAt)
                .lastProductionAt(lastProductionAt)
                .totalProductionHours(totalProductionHours)
                .totalMaintenanceHours(totalMaintenanceHours)
                .totalCompletedMaintenanceCost(totalCompletedCost)
                .completedMaintenanceCount(completedCount)
                .activeMaintenanceCount(activeCount)
                .monthlyChart(monthlyChart)
                // Lịch sử bảo trì/bảo dưỡng: chỉ hiện các phiếu ĐÃ HOÀN TẤT (COMPLETED).
                // Không hiện lệnh đang chờ/đang xử lý (PLANNED/IN_PROGRESS/ADJUSTED)
                // hay lịch bảo dưỡng định kỳ chưa tới hạn (cũng PLANNED) và lệnh bị bỏ lỡ (MISSED).
                .maintenanceHistory(maintenanceList.stream()
                        .filter(m -> m.getStatus() == MaintenanceSchedule.MaintenanceStatus.COMPLETED)
                        .map(this::toMaintenanceDto)
                        .collect(Collectors.toList()))
                .build();
    }

    private MaintenanceDto toMaintenanceDto(MaintenanceSchedule m) {
        BigDecimal devDays = null;
        if (m.getActualStart() != null) {
            devDays = BigDecimal.valueOf((m.getActualStart() - m.getPlannedStart()) / (double) ONE_DAY_MS)
                    .setScale(1, RoundingMode.HALF_UP);
        }
        return MaintenanceDto.builder()
                .id(m.getId()).machineId(m.getMachine().getId()).machineName(m.getMachineName())
                .maintenanceType(m.getMaintenanceType().name())
                .recurrenceType(m.getRecurrenceType() != null ? m.getRecurrenceType().name() : null)
                .title(m.getTitle()).description(m.getDescription())
                .plannedStart(m.getPlannedStart()).plannedEnd(m.getPlannedEnd())
                .actualStart(m.getActualStart()).actualEnd(m.getActualEnd())
                .plannedDowntimeHours(m.getPlannedDowntimeHours())
                .actualDowntimeHours(m.getActualDowntimeHours())
                .vendorName(m.getVendorName()).vendorContactPerson(m.getVendorContactPerson()).vendorPhone(m.getVendorPhone())
                .estimatedCost(m.getEstimatedCost()).actualCost(m.getActualCost())
                .beforeImages(fromJsonList(m.getBeforeImages()))
                .afterImages(fromJsonList(m.getAfterImages()))
                .receiptImages(fromJsonList(m.getReceiptImages()))
                .status(m.getStatus().name())
                .createdByName(m.getCreatedByName())
                .deviationDays(devDays).createdAt(m.getCreatedAt())
                .build();
    }

    // Owner force update status (cancel, force complete)
    public WorkOrderDto updateWorkOrderStatusByOwner(Long id, UpdateWorkOrderStatusRequest req) {
        WorkOrder wo = workOrderRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh"));
        wo.setStatus(WorkOrder.WorkOrderStatus.valueOf(req.getStatus()));
        if (req.getNotes() != null) wo.setNotes(req.getNotes());
        if (req.getStatus().equals("CANCELLED") || req.getStatus().equals("COMPLETED")) {
            wo.setActualEndDate(System.currentTimeMillis());
        }
        return toWorkOrderDto(workOrderRepo.save(wo));
    }

    private ProductionFactoryDto toFactoryDto(ProductionFactory f) {
        List<FactoryManagerDto> managers = f.getManagers() == null ? List.of()
                : f.getManagers().stream().map(u -> FactoryManagerDto.builder()
                        .id(u.getId()).fullName(u.getFullName())
                        .username(u.getUsername())
                        .phone(u.getPhoneNumber())
                        .build())
                .collect(Collectors.toList());
        return ProductionFactoryDto.builder()
                .id(f.getId()).name(f.getName())
                .address(f.getAddress()).description(f.getDescription())
                .status(f.getStatus().name())
                .managers(managers)
                .createdAt(f.getCreatedAt())
                .build();
    }
}