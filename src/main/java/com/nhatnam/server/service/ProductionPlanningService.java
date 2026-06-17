package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.production.ProductionPlanningDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductionPlanningService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final String[] MONTH_LABELS =
            {"", "Th1","Th2","Th3","Th4","Th5","Th6","Th7","Th8","Th9","Th10","Th11","Th12"};

    private final MachineRepository             machineRepo;
    private final MaintenanceScheduleRepository maintenanceRepo;
    private final AnnualMPSRepository           annualMpsRepo;
    private final WorkOrderRepository           workOrderRepo;
    private final FactoryProductRepository      productRepo;
    private final ProductionRecipeRepository    recipeRepo;
    private final UserRepository                userRepo;

    private long yearStart(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay(VN).toInstant().toEpochMilli();
    }

    private long yearEnd(int year) {
        return LocalDate.of(year + 1, 1, 1).atStartOfDay(VN).toInstant().toEpochMilli();
    }

    // ─── Machine ──────────────────────────────────────────────────────────────

    public List<MachineDto> listMachines(boolean activeOnly) {
        List<Machine> list = activeOnly
                ? machineRepo.findByStatusOrderByNameAsc(Machine.MachineStatus.ACTIVE)
                : machineRepo.findAllByOrderByNameAsc();
        return list.stream().map(this::toMachineDto).collect(Collectors.toList());
    }

    public MachineDto saveMachine(Long id, SaveMachineRequest req) {
        Machine m = id == null ? new Machine()
                : machineRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy máy"));
        m.setName(req.getName());
        m.setCapacityHoursPerMonth(req.getCapacityHoursPerMonth());
        m.setDescription(req.getDescription());
        if (m.getStatus() == null) m.setStatus(Machine.MachineStatus.ACTIVE);
        return toMachineDto(machineRepo.save(m));
    }

    public MachineDto toggleMachineStatus(Long id, boolean active) {
        Machine m = machineRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy máy"));
        m.setStatus(active ? Machine.MachineStatus.ACTIVE : Machine.MachineStatus.INACTIVE);
        return toMachineDto(machineRepo.save(m));
    }

    // ─── MaintenanceSchedule ─────────────────────────────────────────────────

    public MaintenanceSummaryDto getMaintenanceSummary(int year, Long machineId) {
        long fromMs = yearStart(year);
        long toMs   = yearEnd(year);
        List<MaintenanceSchedule> list = (machineId != null)
                ? maintenanceRepo.findByYearRangeAndMachine(fromMs, toMs, machineId)
                : maintenanceRepo.findByYearRange(fromMs, toMs);

        BigDecimal totalPlanned = list.stream()
                .map(m -> m.getPlannedDowntimeHours() != null ? m.getPlannedDowntimeHours() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalActual = list.stream()
                .map(m -> m.getActualDowntimeHours() != null ? m.getActualDowntimeHours() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long completed = list.stream()
                .filter(m -> m.getStatus() == MaintenanceSchedule.MaintenanceStatus.COMPLETED).count();
        long scheduled = list.size();
        long adjusted  = list.stream()
                .filter(m -> m.getStatus() == MaintenanceSchedule.MaintenanceStatus.ADJUSTED).count();
        double avgDev = list.stream()
                .filter(m -> m.getActualStart() != null)
                .mapToDouble(m -> Math.abs(m.getActualStart() - m.getPlannedStart()) / (1000.0 * 60 * 60 * 24))
                .average().orElse(0.0);

        return MaintenanceSummaryDto.builder()
                .year(year)
                .totalPlannedDowntimeHours(totalPlanned)
                .totalActualDowntimeHours(totalActual)
                .completedOnSchedule(completed)
                .totalScheduled(scheduled)
                .adjustmentsMade(adjusted)
                .avgDeviationDays(BigDecimal.valueOf(avgDev).setScale(1, RoundingMode.HALF_UP))
                .items(list.stream().map(this::toMaintenanceDto).collect(Collectors.toList()))
                .build();
    }

    public MaintenanceDto saveMaintenance(Long id, SaveMaintenanceRequest req) {
        Machine machine = machineRepo.findById(req.getMachineId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy máy"));
        MaintenanceSchedule m = id == null ? new MaintenanceSchedule()
                : maintenanceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lịch bảo trì"));
        m.setMachine(machine);
        m.setMachineName(machine.getName());
        m.setPlannedStart(req.getPlannedStart());
        m.setPlannedEnd(req.getPlannedEnd());
        m.setPlannedDowntimeHours(req.getPlannedDowntimeHours());
        m.setMaintenanceType(MaintenanceSchedule.MaintenanceType.valueOf(req.getMaintenanceType()));
        // title là bắt buộc trong entity mới
        if (m.getTitle() == null || m.getTitle().isBlank()) {
            m.setTitle(req.getMaintenanceType());
        }
        if (m.getStatus() == null) m.setStatus(MaintenanceSchedule.MaintenanceStatus.PLANNED);
        return toMaintenanceDto(maintenanceRepo.save(m));
    }

    public MaintenanceDto completeMaintenance(Long id, CompleteMaintenanceRequest req) {
        MaintenanceSchedule m = maintenanceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lịch bảo trì"));
        m.setActualStart(req.getActualStart());
        m.setActualEnd(req.getActualEnd());
        m.setActualDowntimeHours(req.getActualDowntimeHours());
        long diffDays = Math.abs(req.getActualStart() - m.getPlannedStart()) / (1000L * 60 * 60 * 24);
        m.setStatus(diffDays <= 1
                ? MaintenanceSchedule.MaintenanceStatus.COMPLETED
                : MaintenanceSchedule.MaintenanceStatus.ADJUSTED);
        return toMaintenanceDto(maintenanceRepo.save(m));
    }

    public void deleteMaintenance(Long id) {
        if (!maintenanceRepo.existsById(id))
            throw new ResourceNotFoundException("Không tìm thấy lịch bảo trì");
        maintenanceRepo.deleteById(id);
    }

    // ─── AnnualMPS ───────────────────────────────────────────────────────────

    public AnnualMpsDashboardDto getAnnualMpsDashboard(int year, Long productId) {
        List<AnnualMPS> plans = annualMpsRepo.findByYearAndProduct(year, productId);

        BigDecimal totalHours = plans.stream()
                .map(p -> p.getMachineHoursRequired() != null ? p.getMachineHoursRequired() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgUtil = plans.isEmpty() ? BigDecimal.ZERO
                : plans.stream()
                .map(p -> p.getUtilizationPercent() != null ? p.getUtilizationPercent() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(plans.size()), 2, RoundingMode.HALF_UP);

        List<MaintenanceSchedule> maint = maintenanceRepo.findByYearRange(yearStart(year), yearEnd(year));
        Map<Integer, BigDecimal> maintByMonth = new HashMap<>();
        for (MaintenanceSchedule ms : maint) {
            int mo = new java.util.Date(ms.getPlannedStart()).toInstant().atZone(VN).getMonthValue();
            maintByMonth.merge(mo,
                    ms.getPlannedDowntimeHours() != null ? ms.getPlannedDowntimeHours() : BigDecimal.ZERO,
                    BigDecimal::add);
        }

        Map<Integer, List<AnnualMPS>> byMonth = plans.stream()
                .collect(Collectors.groupingBy(AnnualMPS::getMonth));

        List<MonthlyKpiDto> kpis = new ArrayList<>();
        for (int mo = 1; mo <= 12; mo++) {
            List<AnnualMPS> mp = byMonth.getOrDefault(mo, List.of());
            BigDecimal plannedQty = mp.stream()
                    .map(p -> p.getPlannedProductionQty() != null ? p.getPlannedProductionQty() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal plannedHrs = mp.stream()
                    .map(p -> p.getMachineHoursRequired() != null ? p.getMachineHoursRequired() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal netHrs = mp.stream()
                    .map(p -> p.getNetAvailableMachineHours() != null ? p.getNetAvailableMachineHours() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal maintHrs = maintByMonth.getOrDefault(mo, BigDecimal.ZERO);
            BigDecimal util = mp.isEmpty() ? BigDecimal.ZERO
                    : mp.stream()
                    .map(p -> p.getUtilizationPercent() != null ? p.getUtilizationPercent() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(mp.size()), 2, RoundingMode.HALF_UP);
            kpis.add(MonthlyKpiDto.builder()
                    .month(mo).monthLabel(MONTH_LABELS[mo])
                    .plannedQty(plannedQty).plannedHours(plannedHrs)
                    .machineHours(netHrs).maintenanceHours(maintHrs)
                    .utilizationPct(util).build());
        }

        return AnnualMpsDashboardDto.builder()
                .year(year).totalProductionHours(totalHours)
                .avgUtilizationPct(avgUtil).productionVariancePct(BigDecimal.ZERO)
                .plans(plans.stream().map(this::toAnnualMpsDto).collect(Collectors.toList()))
                .monthlyKpis(kpis)
                .build();
    }

    public List<AnnualMpsDto> listAnnualMps(int year, Long productId) {
        return annualMpsRepo.findByYearAndProduct(year, productId)
                .stream().map(this::toAnnualMpsDto).collect(Collectors.toList());
    }

    public AnnualMpsDto saveAnnualMps(Long id, SaveAnnualMpsRequest req) {
        FactoryProduct fp = productRepo.findById(req.getFactoryProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành phẩm"));
        Machine machine = req.getMachineId() != null
                ? machineRepo.findById(req.getMachineId()).orElse(null) : null;

        AnnualMPS mps = id == null ? new AnnualMPS()
                : annualMpsRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kế hoạch"));
        mps.setYear(req.getYear());
        mps.setMonth(req.getMonth());
        mps.setFactoryProduct(fp);
        mps.setMachine(machine);
        mps.setForecastDemand(req.getForecastDemand());
        mps.setPlannedProductionQty(req.getPlannedProductionQty());
        mps.setMachineHoursRequired(req.getMachineHoursRequired());
        mps.setNetAvailableMachineHours(req.getNetAvailableMachineHours());
        mps.setNotes(req.getNotes());

        if (req.getMachineHoursRequired() != null && req.getNetAvailableMachineHours() != null
                && req.getNetAvailableMachineHours().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal util = req.getMachineHoursRequired()
                    .divide(req.getNetAvailableMachineHours(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            mps.setUtilizationPercent(util);
        }
        if (mps.getStatus() == null) mps.setStatus(AnnualMPS.MpsStatus.DRAFT);
        return toAnnualMpsDto(annualMpsRepo.save(mps));
    }

    public AnnualMpsDto updateMpsStatus(Long id, String status) {
        AnnualMPS mps = annualMpsRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kế hoạch"));
        mps.setStatus(AnnualMPS.MpsStatus.valueOf(status));
        return toAnnualMpsDto(annualMpsRepo.save(mps));
    }

    public void deleteAnnualMps(Long id) {
        if (!annualMpsRepo.existsById(id))
            throw new ResourceNotFoundException("Không tìm thấy kế hoạch");
        annualMpsRepo.deleteById(id);
    }

    // ─── WorkOrder (legacy — compatible với WorkOrderDto cũ) ─────────────────

    public Page<WorkOrderDto> listWorkOrders(int page, int size, String status) {
        Pageable pageable = PageRequest.of(page, size);
        Page<WorkOrder> raw = (status != null && !status.isBlank())
                ? workOrderRepo.findByStatusOrderByCreatedAtDesc(WorkOrder.WorkOrderStatus.valueOf(status), pageable)
                : workOrderRepo.findAllByOrderByCreatedAtDesc(pageable);
        return raw.map(this::toWorkOrderDto);
    }

    public WorkOrderDto getWorkOrder(Long id) {
        return toWorkOrderDto(workOrderRepo.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh sản xuất")));
    }

    public WorkOrderDto createWorkOrder(CreateWorkOrderRequest req, String username) {
        User creator = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        FactoryProduct fp = productRepo.findById(req.getFactoryProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành phẩm"));
        User assignee = req.getAssignedToId() != null
                ? userRepo.findById(req.getAssignedToId()).orElse(null) : null;

        long scheduledStart = req.getPlannedStartDate() != null
                ? req.getPlannedStartDate() : System.currentTimeMillis();
        WorkOrder.WorkOrderStatus initStatus = scheduledStart > System.currentTimeMillis()
                ? WorkOrder.WorkOrderStatus.SCHEDULED
                : WorkOrder.WorkOrderStatus.PENDING_PLAN;

        WorkOrder wo = WorkOrder.builder()
                .workOrderCode(generateWorkOrderCode())
                .factoryProduct(fp)
                .productName(fp.getName())
                .plannedQty(req.getPlannedQty())
                .accumulatedQty(BigDecimal.ZERO)
                .outputUnit(fp.getUnit())
                .scheduledStartDate(scheduledStart)
                .planDeadline(scheduledStart + 24L * 60 * 60 * 1000)
                .plannedEndDate(req.getPlannedEndDate())
                .notes(req.getNotes())
                .createdBy(creator)
                .createdByName(creator.getFullName())
                .status(initStatus)
                .build();

        return toWorkOrderDto(workOrderRepo.save(wo));
    }

    public WorkOrderDto updateWorkOrderStatus(Long id, UpdateWorkOrderStatusRequest req) {
        WorkOrder wo = workOrderRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lệnh sản xuất"));
        WorkOrder.WorkOrderStatus newStatus = WorkOrder.WorkOrderStatus.valueOf(req.getStatus());
        wo.setStatus(newStatus);
        if (req.getNotes() != null) wo.setNotes(req.getNotes());
        if (newStatus == WorkOrder.WorkOrderStatus.IN_PROGRESS && wo.getActualStartDate() == null)
            wo.setActualStartDate(System.currentTimeMillis());
        if (newStatus == WorkOrder.WorkOrderStatus.COMPLETED && wo.getActualEndDate() == null)
            wo.setActualEndDate(System.currentTimeMillis());
        return toWorkOrderDto(workOrderRepo.save(wo));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String generateWorkOrderCode() {
        String prefix = "WO-" + LocalDate.now(VN).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        long seq = workOrderRepo.countByWorkOrderCodeStartingWith(prefix) + 1;
        return prefix + String.format("%04d", seq);
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private MachineDto toMachineDto(Machine m) {
        return MachineDto.builder()
                .id(m.getId()).name(m.getName())
                .capacityHoursPerMonth(m.getCapacityHoursPerMonth())
                .status(m.getStatus().name())
                .description(m.getDescription())
                .createdAt(m.getCreatedAt())
                .build();
    }

    private MaintenanceDto toMaintenanceDto(MaintenanceSchedule m) {
        BigDecimal devDays = null;
        if (m.getActualStart() != null) {
            long diffMs = m.getActualStart() - m.getPlannedStart();
            devDays = BigDecimal.valueOf(diffMs)
                    .divide(BigDecimal.valueOf(1000L * 60 * 60 * 24), 1, RoundingMode.HALF_UP);
        }
        return MaintenanceDto.builder()
                .id(m.getId())
                .machineId(m.getMachine().getId())
                .machineName(m.getMachine().getName())
                .plannedStart(m.getPlannedStart()).plannedEnd(m.getPlannedEnd())
                .actualStart(m.getActualStart()).actualEnd(m.getActualEnd())
                .plannedDowntimeHours(m.getPlannedDowntimeHours())
                .actualDowntimeHours(m.getActualDowntimeHours())
                .maintenanceType(m.getMaintenanceType().name())
                .status(m.getStatus().name())
                .deviationDays(devDays)
                .createdAt(m.getCreatedAt())
                .build();
    }

    private AnnualMpsDto toAnnualMpsDto(AnnualMPS a) {
        return AnnualMpsDto.builder()
                .id(a.getId()).year(a.getYear()).month(a.getMonth())
                .factoryProductId(a.getFactoryProduct().getId())
                .factoryProductName(a.getFactoryProduct().getName())
                .factoryProductUnit(a.getFactoryProduct().getUnit())
                .machineId(a.getMachine() != null ? a.getMachine().getId() : null)
                .machineName(a.getMachine() != null ? a.getMachine().getName() : null)
                .forecastDemand(a.getForecastDemand())
                .plannedProductionQty(a.getPlannedProductionQty())
                .machineHoursRequired(a.getMachineHoursRequired())
                .netAvailableMachineHours(a.getNetAvailableMachineHours())
                .utilizationPercent(a.getUtilizationPercent())
                .status(a.getStatus().name())
                .notes(a.getNotes())
                .createdAt(a.getCreatedAt())
                .build();
    }

    private WorkOrderDto toWorkOrderDto(WorkOrder w) {
        // Map sang WorkOrderDto cũ — các field không còn trong entity mới → null/empty
        return WorkOrderDto.builder()
                .id(w.getId())
                .workOrderCode(w.getWorkOrderCode())
                .factoryProductId(w.getFactoryProduct() != null ? w.getFactoryProduct().getId() : null)
                .productName(w.getProductName())
                .recipeId(null)
                .recipeName(null)
                .annualMpsId(null)
                .plannedStartDate(w.getScheduledStartDate())
                .plannedEndDate(w.getPlannedEndDate())
                .actualStartDate(w.getActualStartDate())
                .actualEndDate(w.getActualEndDate())
                .plannedQty(w.getPlannedQty())
                .actualQty(w.getAccumulatedQty())
                .scrapQty(BigDecimal.ZERO)
                .outputUnit(w.getOutputUnit())
                .status(w.getStatus().name())
                .notes(w.getNotes())
                .createdByName(w.getCreatedByName())
                .createdAt(w.getCreatedAt())
                .productionBatchId(null)
                .batchCode(null)
                .operations(List.of())
                .build();
    }
}