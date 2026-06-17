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
    private final WorkOrderStockDeductionRepository deductionRepo;

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
        ProductionPlan plan = ProductionPlan.builder()
                .planCode(code)
                .title(req.getTitle())
                .factoryProduct(primaryProduct)
                .productName(primaryProduct.getName())
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

        // Tìm mẻ đang làm
        ProductionBatch inProgressBatch = batches.stream()
                .filter(b -> b.getStatus() == ProductionBatch.BatchStatus.IN_PROGRESS)
                .findFirst().orElse(null);
        String currentStep = null;
        int currentBatchNum = 0;
        if (inProgressBatch != null) {
            currentBatchNum = inProgressBatch.getBatchNumber() != null ? inProgressBatch.getBatchNumber() : 0;
            List<BatchStep> steps = stepRepo.findByBatch_IdOrderByStepSequenceAsc(inProgressBatch.getId());
            currentStep = steps.stream()
                    .filter(s -> s.getStatus() == BatchStep.StepStatus.PENDING)
                    .findFirst()
                    .map(BatchStep::getStepName)
                    .orElse("Hoàn thành");
        }

        BigDecimal progress = calcProgressPct(wo.getAccumulatedQty(), wo.getPlannedQty());

        return WorkOrderDetailDto.builder()
                .workOrder(toWorkOrderDto(wo))
                .plan(plan != null ? toPlanDto(plan) : null)
                .batches(batches.stream().map(this::toBatchDto).collect(Collectors.toList()))
                .progressPct(progress)
                .currentBatchNumber(currentBatchNum)
                .currentStepName(currentStep)
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
        return toFactoryDto(factoryRepo.save(factory));
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

    public WorkOrderPlanDto submitPlan(Long workOrderId, CreateWorkOrderPlanRequest req, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        WorkOrder wo = workOrderRepo.findById(workOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh"));

        if (wo.getStatus() != WorkOrder.WorkOrderStatus.PENDING_PLAN
                && wo.getStatus() != WorkOrder.WorkOrderStatus.SCHEDULED) {
            throw new IllegalStateException("Lệnh không ở trạng thái chờ lập phương án");
        }

        // Xoá phương án cũ nếu có
        workOrderPlanRepo.findByWorkOrder_Id(workOrderId).ifPresent(workOrderPlanRepo::delete);

        String stepsJson = toJson(req.getBatchSteps());
        String stepDetailsJson = req.getBatchStepDetails() != null ? toJson(req.getBatchStepDetails()) : null;
        String batchQtyListJson = req.getBatchQtyPerRunList() != null && !req.getBatchQtyPerRunList().isEmpty()
                ? toJson(req.getBatchQtyPerRunList()) : null;

        WorkOrderPlan plan = WorkOrderPlan.builder()
                .workOrder(wo)
                .totalBatches(req.getTotalBatches())
                .batchQtyPerRun(req.getBatchQtyPerRun())
                .batchQtyPerRunList(batchQtyListJson)
                .plannedStaff(req.getPlannedStaff())
                .batchSteps(stepsJson)
                .batchStepDetails(stepDetailsJson)
                .notes(req.getNotes())
                .submittedBy(worker)
                .submittedByName(worker.getFullName())
                .submittedAt(System.currentTimeMillis())
                .build();

        if (req.getMaterials() != null) {
            int order = 0;
            for (PlanMaterialRequest m : req.getMaterials()) {
                BigDecimal total = m.getEstimatedUnitPrice() != null && m.getQuantity() != null
                        ? m.getEstimatedUnitPrice().multiply(m.getQuantity()).setScale(2, RoundingMode.HALF_UP)
                        : null;
                plan.getMaterials().add(WorkOrderPlanMaterial.builder()
                        .plan(plan)
                        .materialName(m.getMaterialName())
                        .quantity(m.getQuantity())
                        .unit(m.getUnit())
                        .vendorName(m.getVendorName())
                        .vendorPhone(m.getVendorPhone())
                        .estimatedUnitPrice(m.getEstimatedUnitPrice())
                        .estimatedTotal(total)
                        .sortOrder(m.getSortOrder() > 0 ? m.getSortOrder() : order)
                        .build());
                order++;
            }
        }

        WorkOrderPlan saved = workOrderPlanRepo.save(plan);

        // Update WO status → PLANNED
        wo.setStatus(WorkOrder.WorkOrderStatus.PLANNED);
        workOrderRepo.save(wo);

        // Notify OWNER + SUPER_ACCOUNTANT về nguyên liệu cần mua
        BigDecimal totalCost = saved.getMaterials().stream()
                .map(m -> m.getEstimatedTotal() != null ? m.getEstimatedTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String msg = String.format("Phương án lệnh %s đã được lập bởi %s. Nguyên liệu ước tính: %s VND",
                wo.getWorkOrderCode(), worker.getFullName(),
                String.format("%,.0f", totalCost));
        String payload = String.format(
                "{\"workOrderId\":%d,\"workOrderCode\":\"%s\",\"totalCost\":%s}",
                wo.getId(), wo.getWorkOrderCode(), totalCost);
        notificationService.sendToRole("OWNER", "WORK_ORDER_PLAN_SUBMITTED", msg, payload);
        notificationService.sendToRole("SUPER_ACCOUNTANT", "WORK_ORDER_PLAN_SUBMITTED", msg, payload);

        return toPlanDto(saved);
    }

    /**
     * Bắt đầu lệnh sản xuất:
     *  - Chuyển trạng thái PLANNED → IN_PROGRESS
     *  - Trừ kho xưởng theo FIFO (ưu tiên lô gần hết hạn sử dụng nhất trừ trước)
     *    dựa trên nguyên liệu trong phương án (WorkOrderPlanMaterial).
     *    Nếu kho không đủ một loại NVL nào → ném lỗi, KHÔNG bắt đầu được lệnh.
     */
    public WorkOrderDto startWorkOrder(Long workOrderId, String username) {
        WorkOrder wo = workOrderRepo.findByIdWithPlan(workOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh"));
        if (wo.getStatus() != WorkOrder.WorkOrderStatus.PLANNED) {
            throw new IllegalStateException("Lệnh chưa có phương án hoặc không thể bắt đầu");
        }
        // Kiểm tra scheduledStartDate
        if (wo.getScheduledStartDate() != null && wo.getScheduledStartDate() > System.currentTimeMillis()) {
            throw new IllegalStateException("Chưa đến ngày được phép bắt đầu: " + formatDate(wo.getScheduledStartDate()));
        }

        // ── Trừ kho xưởng theo FIFO (theo hạn sử dụng gần nhất → xa nhất) ─────
        WorkOrderPlan plan = wo.getWorkOrderPlan();
        if (plan != null && plan.getMaterials() != null && !plan.getMaterials().isEmpty()) {
            deductStockFifo(wo, plan.getMaterials());
        }

        wo.setStatus(WorkOrder.WorkOrderStatus.IN_PROGRESS);
        wo.setActualStartDate(System.currentTimeMillis());
        return toWorkOrderDto(workOrderRepo.save(wo));
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
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        WorkOrder wo = workOrderRepo.findByIdWithPlan(req.getWorkOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh"));
        if (wo.getStatus() != WorkOrder.WorkOrderStatus.IN_PROGRESS) {
            throw new IllegalStateException("Lệnh chưa ở trạng thái đang thực hiện");
        }
        ProductionRecipe recipe = recipeRepo.findByIdWithItems(req.getRecipeId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy công thức"));

        int batchNum = batchRepo.nextBatchNumber(wo.getId());
        String code = generateBatchCode();

        // Lấy phương án để tính sản lượng từng mẻ
        WorkOrderPlan plan = wo.getWorkOrderPlan();

        // Lấy sản lượng của mẻ này (per-batch list hoặc default)
        java.math.BigDecimal thisBatchQty = null;
        if (plan != null && plan.getBatchQtyPerRunList() != null) {
            try {
                List<java.math.BigDecimal> qtyList = objectMapper.readValue(plan.getBatchQtyPerRunList(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, java.math.BigDecimal.class));
                int idx = batchNum - 1;
                if (idx >= 0 && idx < qtyList.size()) thisBatchQty = qtyList.get(idx);
            } catch (Exception ignored) {}
        }
        if (thisBatchQty == null && plan != null) thisBatchQty = plan.getBatchQtyPerRun();

        ProductionBatch batch = ProductionBatch.builder()
                .batchCode(code)
                .workOrder(wo)
                .batchNumber(batchNum)
                .recipe(recipe)
                .productName(wo.getProductName())
                .recipeName(recipe.getName())
                .outputUnit(wo.getOutputUnit())
                .status(ProductionBatch.BatchStatus.IN_PROGRESS)
                .notes(req.getNotes())
                .createdBy(worker)
                .createdByName(worker.getFullName())
                .build();

        // Tạo các BatchStep từ phương án
        if (plan != null && plan.getBatchSteps() != null) {
            List<String> stepNames = fromJsonList(plan.getBatchSteps());
            // Parse batchStepDetails để lấy requiresQC
            java.util.Map<Integer, Boolean> qcMap = new java.util.HashMap<>();
            java.util.Map<Integer, Long> machineIdMap = new java.util.HashMap<>();
            if (plan.getBatchStepDetails() != null) {
                try {
                    List<java.util.Map<String,Object>> details = objectMapper.readValue(
                            plan.getBatchStepDetails(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, java.util.Map.class));
                    for (int k = 0; k < details.size(); k++) {
                        Object qcVal = details.get(k).get("requiresQC");
                        if (qcVal == null) qcVal = details.get(k).get("requiresQc");
                        qcMap.put(k, Boolean.TRUE.equals(qcVal) || "true".equalsIgnoreCase(String.valueOf(qcVal)));
                        Object machineIdVal = details.get(k).get("machineId");
                        if (machineIdVal != null) {
                            try { machineIdMap.put(k, Long.valueOf(machineIdVal.toString())); } catch(Exception ignored2) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
            for (int i = 0; i < stepNames.size(); i++) {
                Machine stepMachine = null;
                if (machineIdMap.containsKey(i)) {
                    stepMachine = machineRepo.findById(machineIdMap.get(i)).orElse(null);
                    // Check if machine is under maintenance — notify owner
                    if (stepMachine != null && stepMachine.getStatus() == Machine.MachineStatus.UNDER_MAINTENANCE) {
                        String warnMsg = String.format("Máy '%s' đang bảo trì, lệnh %s cần điều chỉnh lịch",
                                stepMachine.getName(), batch.getWorkOrder().getWorkOrderCode());
                        notificationService.sendToRole("OWNER", "MACHINE_MAINTENANCE_CONFLICT", warnMsg,
                                "{\"workOrderId\":" + batch.getWorkOrder().getId() + ",\"machineId\":" + stepMachine.getId() + "}");
                    }
                }
                batch.getSteps().add(BatchStep.builder()
                        .batch(batch)
                        .stepSequence(i + 1)
                        .stepName(stepNames.get(i))
                        .status(BatchStep.StepStatus.PENDING)
                        .requiresQc(qcMap.getOrDefault(i, false))
                        .machine(stepMachine)
                        .machineName(stepMachine != null ? stepMachine.getName() : null)
                        .build());
            }
        }

        return toBatchDto(batchRepo.save(batch));
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

        batch.setActualOutputQty(req.getActualOutputQty());
        batch.setProducedAt(System.currentTimeMillis());
        batch.setStatus(ProductionBatch.BatchStatus.COMPLETED);
        if (req.getNotes() != null) batch.setNotes(req.getNotes());

        batchRepo.save(batch);

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
                        .vendorName(saved.getVendorName()).vendorPhone(saved.getVendorPhone())
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

        return ProductionDashboardDto.builder()
                .totalActivePlans(activePlans.size())
                .totalWorkOrders((int) totalWO)
                .inProgressOrders((int) inProgress)
                .completedOrders((int) completed)
                .scheduledOrders((int) scheduled)
                .pendingPlanOrders((int) pendingPlan)
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

        return WorkOrderPlanDto.builder()
                .id(p.getId()).workOrderId(p.getWorkOrder().getId())
                .totalBatches(p.getTotalBatches()).batchQtyPerRun(p.getBatchQtyPerRun())
                .batchSteps(steps).notes(p.getNotes())
                .submittedByName(p.getSubmittedByName()).submittedAt(p.getSubmittedAt())
                .materials(mats).totalEstimatedCost(totalCost)
                .build();
    }

    private ProductionBatchDto toBatchDto(ProductionBatch b) {
        List<BatchStep> steps = stepRepo.findByBatch_IdOrderByStepSequenceAsc(b.getId());
        long completedSteps = steps.stream().filter(s -> s.getStatus() == BatchStep.StepStatus.COMPLETED).count();
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

        return ProductionBatchDto.builder()
                .id(b.getId()).batchCode(b.getBatchCode())
                .workOrderId(b.getWorkOrder() != null ? b.getWorkOrder().getId() : null)
                .workOrderCode(b.getWorkOrder() != null ? b.getWorkOrder().getWorkOrderCode() : null)
                .batchNumber(b.getBatchNumber())
                .recipeId(b.getRecipe() != null ? b.getRecipe().getId() : null)
                .productName(b.getProductName()).recipeName(b.getRecipeName())
                .actualOutputQty(b.getActualOutputQty()).standardOutputQty(stdOutput)
                .outputVariancePct(variancePct).outputUnit(b.getOutputUnit())
                .producedAt(b.getProducedAt()).notes(b.getNotes())
                .status(b.getStatus().name())
                .createdByName(b.getCreatedByName()).createdAt(b.getCreatedAt())
                .totalSteps(steps.size()).completedSteps((int) completedSteps)
                .stepProgressPct(stepPct)
                .steps(steps.stream().map(this::toStepDto).collect(Collectors.toList()))
                .cancellation(cancelDto)
                .build();
    }

    private BatchStepDto toStepDto(BatchStep s) {
        return BatchStepDto.builder()
                .id(s.getId()).stepSequence(s.getStepSequence()).stepName(s.getStepName())
                .status(s.getStatus().name())
                .requiresQc(s.isRequiresQc())
                .machineId(s.getMachine() != null ? s.getMachine().getId() : null)
                .machineName(s.getMachineName())
                .attachments(fromJsonList(s.getAttachments()))
                .notes(s.getNotes()).completedByName(s.getCompletedByName())
                .completedAt(s.getCompletedAt())
                .build();
    }

    private MachineDto toMachineDto(Machine m) { return toMachineDtoWithStats(m); }

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
                .vendorName(m.getVendorName()).vendorPhone(m.getVendorPhone())
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