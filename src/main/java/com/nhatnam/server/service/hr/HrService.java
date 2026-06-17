package com.nhatnam.server.service.hr;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.hr.HrDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HrService {

    private final UserRepository userRepository;
    private final EmployeeSalaryRepository salaryRepository;
    private final LeaveRequestRepository leaveRepository;
    private final OvertimeRequestRepository overtimeRepository;
    private final NotificationService notificationService;

    // ── Current user helper ───────────────────────────────────────────────────

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("Người dùng không tồn tại"));
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Nhân viên không tồn tại: " + id));
    }

    // ── Employee info ─────────────────────────────────────────────────────────

    @Transactional
    public void updateEmployeeInfo(Long userId, UpdateEmployeeInfoRequest req) {
        User u = findUser(userId);
        if (req.getDepartment() != null) u.setDepartment(req.getDepartment());
        if (req.getPosition()   != null) u.setPosition(req.getPosition());
        userRepository.save(u);
    }

    // ── Salary ────────────────────────────────────────────────────────────────

    @Transactional
    public SalaryDto setSalary(SalaryRequest req) {
        User hr   = currentUser();
        User user = findUser(req.getUserId());

        // Tạo bản ghi PENDING mới
        EmployeeSalary salary = EmployeeSalary.builder()
                .user(user)
                .baseSalary(req.getBaseSalary())
                .socialInsuranceRate(req.getSocialInsuranceRate())
                .socialInsuranceSalary(req.getSocialInsuranceSalary())
                .bonus(req.getBonus())
                .mealAllowance(req.getMealAllowance())
                .transportAllowance(req.getTransportAllowance())
                .status("PENDING")
                .createdBy(hr)
                .createdAt(System.currentTimeMillis())
                .updatedAt(System.currentTimeMillis())
                .build();
        salaryRepository.save(salary);

        // Notify owner
        notificationService.sendToRole("OWNER", "HR_SALARY_PENDING",
                "HR vừa cập nhật lương cho " + user.getFullName() + " — chờ duyệt",
                "{\"salaryId\":" + salary.getId() + ",\"userId\":" + user.getId() + "}");

        return toSalaryDto(salary);
    }

    @Transactional
    public List<SalaryDto> batchSetSalary(BatchSalaryRequest req) {
        User hr = currentUser();
        List<SalaryDto> results = new ArrayList<>();

        for (Long uid : req.getUserIds()) {
            User user = findUser(uid);
            EmployeeSalary salary = EmployeeSalary.builder()
                    .user(user)
                    .baseSalary(req.getBaseSalary())
                    .socialInsuranceRate(req.getSocialInsuranceRate())
                    .socialInsuranceSalary(req.getSocialInsuranceSalary())
                    .bonus(req.getBonus())
                    .mealAllowance(req.getMealAllowance())
                    .transportAllowance(req.getTransportAllowance())
                    .status("PENDING")
                    .createdBy(hr)
                    .createdAt(System.currentTimeMillis())
                    .updatedAt(System.currentTimeMillis())
                    .build();
            salaryRepository.save(salary);
            results.add(toSalaryDto(salary));
        }

        notificationService.sendToRole("OWNER", "HR_SALARY_PENDING",
                "HR vừa cập nhật lương hàng loạt cho " + req.getUserIds().size() + " nhân viên — chờ duyệt",
                "{\"count\":" + req.getUserIds().size() + "}");

        return results;
    }

    @Transactional(readOnly = true)
    public PageResponse<SalaryDto> listSalaries(String status, Pageable pageable) {
        Page<EmployeeSalary> page = salaryRepository.findAllFiltered(status, pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toSalaryDto).toList());
    }

    @Transactional
    public SalaryDto approveSalary(Long salaryId) {
        User owner = currentUser();
        EmployeeSalary salary = salaryRepository.findById(salaryId)
                .orElseThrow(() -> new ResourceNotFoundException("Phiếu lương không tồn tại"));
        if (!"PENDING".equals(salary.getStatus())) {
            throw new BusinessException("Phiếu lương không ở trạng thái chờ duyệt");
        }
        salary.setStatus("APPROVED");
        salary.setApprovedBy(owner);
        salary.setUpdatedAt(System.currentTimeMillis());
        salaryRepository.save(salary);

        // Notify HR creator
        if (salary.getCreatedBy() != null) {
            notificationService.sendToUser(salary.getCreatedBy(), "HR",
                    "HR_SALARY_APPROVED",
                    "Phiếu lương của " + salary.getUser().getFullName() + " đã được duyệt",
                    "{\"salaryId\":" + salaryId + "}");
        }
        return toSalaryDto(salary);
    }

    @Transactional
    public SalaryDto rejectSalary(Long salaryId, RejectSalaryRequest req) {
        User owner = currentUser();
        EmployeeSalary salary = salaryRepository.findById(salaryId)
                .orElseThrow(() -> new ResourceNotFoundException("Phiếu lương không tồn tại"));
        if (!"PENDING".equals(salary.getStatus())) {
            throw new BusinessException("Phiếu lương không ở trạng thái chờ duyệt");
        }
        if (req.getRejectReason() == null || req.getRejectReason().isBlank()) {
            throw new BusinessException("Cần nhập lý do từ chối");
        }
        salary.setStatus("REJECTED");
        salary.setRejectReason(req.getRejectReason());
        salary.setApprovedBy(owner);
        salary.setUpdatedAt(System.currentTimeMillis());
        salaryRepository.save(salary);

        if (salary.getCreatedBy() != null) {
            notificationService.sendToUser(salary.getCreatedBy(), "HR",
                    "HR_SALARY_REJECTED",
                    "Phiếu lương của " + salary.getUser().getFullName() + " bị từ chối: " + req.getRejectReason(),
                    "{\"salaryId\":" + salaryId + ",\"reason\":\"" + req.getRejectReason() + "\"}");
        }
        return toSalaryDto(salary);
    }

    // ── Leave ─────────────────────────────────────────────────────────────────

    @Transactional
    public LeaveRequestDto createLeave(LeaveRequestCreate req) {
        User hr   = currentUser();
        User user = findUser(req.getUserId());

        LeaveRequest leave = LeaveRequest.builder()
                .user(user)
                .leaveType(req.getLeaveType())
                .leaveDate(req.getLeaveDate())
                .leaveEndDate(req.getLeaveEndDate())
                .leaveDays(req.getLeaveDays())
                .handoverTo(req.getHandoverTo())
                .contactPhone(req.getContactPhone())
                .note(req.getNote())
                .createdBy(hr)
                .createdAt(System.currentTimeMillis())
                .build();
        leaveRepository.save(leave);

        notificationService.sendToRole("OWNER", "HR_LEAVE_CREATED",
                user.getFullName() + " xin nghỉ " + req.getLeaveDays() + " ngày ("
                        + ("PAID".equals(req.getLeaveType()) ? "có lương" : "không lương") + ")",
                "{\"leaveId\":" + leave.getId() + ",\"userId\":" + user.getId() + "}");

        return toLeaveDto(leave);
    }

    @Transactional(readOnly = true)
    public PageResponse<LeaveRequestDto> listLeaves(Long from, Long to, Pageable pageable) {
        Page<LeaveRequest> page = leaveRepository.findAllInRange(from, to, pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toLeaveDto).toList());
    }

    @Transactional(readOnly = true)
    public LeaveRequestDto getLeave(Long id) {
        return toLeaveDto(leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Phiếu nghỉ không tồn tại")));
    }

    // ── Overtime ──────────────────────────────────────────────────────────────

    @Transactional
    public OvertimeRequestDto createOvertime(OvertimeRequestCreate req) {
        User hr = currentUser();

        OvertimeRequest ot = OvertimeRequest.builder()
                .otDate(req.getOtDate())
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .otHours(req.getOtHours())
                .reason(req.getReason())
                .createdBy(hr)
                .createdAt(System.currentTimeMillis())
                .build();

        List<OvertimeEmployee> emps = new ArrayList<>();
        for (Long uid : req.getUserIds()) {
            User u = findUser(uid);
            emps.add(OvertimeEmployee.builder().overtimeRequest(ot).user(u).build());
        }
        ot.setEmployees(emps);
        overtimeRepository.save(ot);

        notificationService.sendToRole("OWNER", "HR_OT_CREATED",
                "Đơn OT ngày " + formatEpochDate(req.getOtDate()) + " cho "
                        + req.getUserIds().size() + " nhân viên",
                "{\"otId\":" + ot.getId() + "}");

        return toOtDto(ot);
    }

    @Transactional(readOnly = true)
    public PageResponse<OvertimeRequestDto> listOvertimes(Long from, Long to, Pageable pageable) {
        Page<OvertimeRequest> page = overtimeRepository.findAllInRange(from, to, pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toOtDto).toList());
    }

    @Transactional(readOnly = true)
    public OvertimeRequestDto getOvertime(Long id) {
        return toOtDto(overtimeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn OT không tồn tại")));
    }

    // ── Payslip ───────────────────────────────────────────────────────────────

    /**
     * Tính phiếu lương tháng trước của user.
     * Phiếu lương luôn là tháng ngay trước thời điểm hiện tại.
     */
    @Transactional(readOnly = true)
    public PayslipDto getPayslip(Long userId) {
        User user = findUser(userId);

        // Tháng trước
        YearMonth ym = YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusMonths(1);
        int month = ym.getMonthValue();
        int year  = ym.getYear();

        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd   = ym.atEndOfMonth();
        long fromMs = periodStart.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli();
        long toMs   = periodEnd.atTime(23, 59, 59).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli();

        // Lấy lương đã duyệt mới nhất
        List<EmployeeSalary> approved = salaryRepository.findApprovedByUserId(userId);
        EmployeeSalary salary = approved.isEmpty() ? null : approved.get(0);

        long baseSalary           = salary != null && salary.getBaseSalary()           != null ? salary.getBaseSalary()           : 0L;
        double siRate             = salary != null && salary.getSocialInsuranceRate()   != null ? salary.getSocialInsuranceRate()   : 0.0;
        long siSalary             = salary != null && salary.getSocialInsuranceSalary() != null ? salary.getSocialInsuranceSalary() : 0L;
        long bonus                = salary != null && salary.getBonus()                != null ? salary.getBonus()                : 0L;
        long mealAllowance        = salary != null && salary.getMealAllowance()        != null ? salary.getMealAllowance()        : 0L;
        long transportAllowance   = salary != null && salary.getTransportAllowance()   != null ? salary.getTransportAllowance()   : 0L;

        // Tính công chuẩn: T2-T6 = 1, T7 = 0.5, CN = 0
        double standardWorkdays = 0;
        for (LocalDate d = periodStart; !d.isAfter(periodEnd); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY) standardWorkdays += 0.5;
            else if (dow != DayOfWeek.SUNDAY) standardWorkdays += 1.0;
        }

        // Nghỉ không lương trong tháng
        List<LeaveRequest> unpaidLeaves = leaveRepository.findUnpaidByUserAndPeriod(userId, fromMs, toMs);
        double unpaidDays = unpaidLeaves.stream().mapToDouble(l -> l.getLeaveDays() != null ? l.getLeaveDays() : 0).sum();

        // Nghỉ có lương trong tháng
        List<LeaveRequest> paidLeaves = leaveRepository.findByUserAndPeriod(userId, fromMs, toMs)
                .stream().filter(l -> "PAID".equals(l.getLeaveType())).collect(Collectors.toList());
        double paidDays = paidLeaves.stream().mapToDouble(l -> l.getLeaveDays() != null ? l.getLeaveDays() : 0).sum();

        double actualWorkdays = standardWorkdays - unpaidDays;
        if (actualWorkdays < 0) actualWorkdays = 0;

        // OT trong tháng
        List<OvertimeRequest> otList = overtimeRepository.findByUserAndPeriod(userId, fromMs, toMs);
        double totalOtHours = otList.stream().mapToDouble(o -> o.getOtHours() != null ? o.getOtHours() : 0).sum();

        // Tiền OT = 150% lương giờ (lương giờ = baseSalary / (standardWorkdays * 8))
        long otPay = 0;
        if (standardWorkdays > 0 && baseSalary > 0) {
            double hourlyRate = (double) baseSalary / (standardWorkdays * 8);
            otPay = Math.round(hourlyRate * 1.5 * totalOtHours);
        }

        // Lương theo ngày thực tế
        long dailySalary = standardWorkdays > 0 ? Math.round((double) baseSalary / standardWorkdays * actualWorkdays) : 0;

        // Phụ cấp cơm: tính theo ngày thực tế
        long mealActual = standardWorkdays > 0 ? Math.round((double) mealAllowance / standardWorkdays * actualWorkdays) : 0;

        // BHXH khấu trừ
        long siAmount = Math.round(siSalary * siRate / 100.0);

        long grossSalary    = dailySalary + mealActual + transportAllowance + bonus + otPay;
        long totalDeductions = siAmount;
        long netSalary      = grossSalary - totalDeductions;

        return PayslipDto.builder()
                .userId(user.getId())
                .userFullName(user.getFullName())
                .department(user.getDepartment() != null ? user.getDepartment() : "Nhân viên")
                .position(user.getPosition())
                .month(month)
                .year(year)
                .periodStart(fromMs)
                .periodEnd(toMs)
                .baseSalary(baseSalary)
                .mealAllowance(mealAllowance)
                .transportAllowance(transportAllowance)
                .bonus(bonus)
                .standardWorkdays(standardWorkdays)
                .actualWorkdays(actualWorkdays)
                .unpaidLeaveDays(unpaidDays)
                .paidLeaveDays(paidDays)
                .otHours(totalOtHours)
                .otPay(otPay)
                .socialInsuranceRate(siRate)
                .socialInsuranceSalary(siSalary)
                .socialInsuranceAmount(siAmount)
                .grossSalary(grossSalary)
                .totalDeductions(totalDeductions)
                .netSalary(netSalary)
                .build();
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private SalaryDto toSalaryDto(EmployeeSalary s) {
        return SalaryDto.builder()
                .id(s.getId())
                .userId(s.getUser().getId())
                .userFullName(s.getUser().getFullName())
                .department(s.getUser().getDepartment() != null ? s.getUser().getDepartment() : "Nhân viên")
                .position(s.getUser().getPosition())
                .baseSalary(s.getBaseSalary())
                .socialInsuranceRate(s.getSocialInsuranceRate())
                .socialInsuranceSalary(s.getSocialInsuranceSalary())
                .bonus(s.getBonus())
                .mealAllowance(s.getMealAllowance())
                .transportAllowance(s.getTransportAllowance())
                .status(s.getStatus())
                .rejectReason(s.getRejectReason())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .createdByName(s.getCreatedBy() != null ? s.getCreatedBy().getFullName() : null)
                .approvedByName(s.getApprovedBy() != null ? s.getApprovedBy().getFullName() : null)
                .build();
    }

    private LeaveRequestDto toLeaveDto(LeaveRequest l) {
        return LeaveRequestDto.builder()
                .id(l.getId())
                .userId(l.getUser().getId())
                .userFullName(l.getUser().getFullName())
                .department(l.getUser().getDepartment() != null ? l.getUser().getDepartment() : "Nhân viên")
                .position(l.getUser().getPosition())
                .leaveType(l.getLeaveType())
                .leaveDate(l.getLeaveDate())
                .leaveEndDate(l.getLeaveEndDate())
                .leaveDays(l.getLeaveDays())
                .handoverTo(l.getHandoverTo())
                .contactPhone(l.getContactPhone())
                .note(l.getNote())
                .createdAt(l.getCreatedAt())
                .createdByName(l.getCreatedBy() != null ? l.getCreatedBy().getFullName() : null)
                .build();
    }

    private OvertimeRequestDto toOtDto(OvertimeRequest o) {
        List<OtEmployeeDto> emps = o.getEmployees() == null ? List.of() :
                o.getEmployees().stream().map(e -> OtEmployeeDto.builder()
                        .userId(e.getUser().getId())
                        .fullName(e.getUser().getFullName())
                        .department(e.getUser().getDepartment() != null ? e.getUser().getDepartment() : "Nhân viên")
                        .position(e.getUser().getPosition())
                        .build()).collect(Collectors.toList());

        return OvertimeRequestDto.builder()
                .id(o.getId())
                .otDate(o.getOtDate())
                .startTime(o.getStartTime())
                .endTime(o.getEndTime())
                .otHours(o.getOtHours())
                .reason(o.getReason())
                .employees(emps)
                .createdAt(o.getCreatedAt())
                .createdByName(o.getCreatedBy() != null ? o.getCreatedBy().getFullName() : null)
                .build();
    }

    private String formatEpochDate(Long epochMs) {
        if (epochMs == null) return "?";
        LocalDate d = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        return d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + d.getYear();
    }
}
