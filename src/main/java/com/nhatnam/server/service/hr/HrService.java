package com.nhatnam.server.service.hr;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.hr.HrDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.OrgCatalog;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.NotificationService;
import com.nhatnam.server.utils.AuthRoleUtil;
import com.nhatnam.server.utils.PayrollTaxCalculator;
import com.nhatnam.server.utils.SeniorityCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HrService {

    private final UserRepository userRepository;
    private final MonthlyAdjustmentRepository monthlyAdjustmentRepository;
    private final com.nhatnam.server.repository.FactoryKpiBonusItemRepository factoryKpiBonusItemRepository;
    private final com.nhatnam.server.service.hr.PayrollDepartmentResolver payrollDepartmentResolver;
    private final EmployeeSalaryRepository salaryRepository;
    private final LeaveRequestRepository leaveRepository;
    private final OvertimeRequestRepository overtimeRepository;
    private final NotificationService notificationService;
    private final PayrollInputProvider payrollInputProvider;
    private final AllowanceLabelRepository allowanceLabelRepository;
    private final com.nhatnam.server.repository.AttendanceEntryRepository attendanceEntryRepository;

    // ── Thâm niên ─────────────────────────────────────────────────────────────
    /**
     * Bản CHỐT thâm niên của kỳ (do OWNER bấm Hoàn tất sinh ra) — ưu tiên đọc để
     * việc sửa ngày vào làm về sau không làm đổi phụ cấp của tháng đã trả.
     *
     * <p>Không cần {@code AttendanceSheetRepository} ở đây: mốc đếm năm là NGÀY
     * CUỐI CỦA KỲ LƯƠNG, suy thẳng từ {@code month/year}, không đọc
     * {@code finalizedAt} của bảng chấm công.
     */
    private final com.nhatnam.server.repository.EmployeeSeniorityRepository seniorityRepository;

    /**
     * Từ khóa (chuẩn hóa không dấu, chữ thường) để nhận diện nhân viên hưởng
     * lương theo GIỜ (phòng Xưởng / ban Sản xuất). Nếu trường "Bộ phận"
     * (department) chứa một trong các từ khóa này thì lương tính theo giờ công.
     */
    private static final String[] HOURLY_DEPT_KEYWORDS = { "xuong", "san xuat" };

    /** Bỏ dấu tiếng Việt + đưa về chữ thường để so khớp từ khóa phòng ban linh hoạt. */
    private static String normalize(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase().trim();
    }

    /**
     * Nhân viên hưởng LƯƠNG KHOÁN theo hợp đồng bên thứ ba.
     *
     * <p>Hiện chỉ có Bảo vệ xưởng ({@code FACTORY_SECURITY}). Nhận diện ưu tiên
     * theo {@code payroll_role} đã chốt trong hồ sơ; khi không có (màn hình xem
     * thử lương, nhân viên chưa gán chức vụ) thì suy từ cặp Bộ phận + Chức vụ
     * qua {@link OrgCatalog} để bản xem thử khớp với lương thật.
     */
    private static boolean isFlatContractRole(User u, String department, String position) {
        Role r = (u != null && u.getPayrollRole() != null)
                ? u.getPayrollRole()
                : OrgCatalog.payrollRoleOf(department, position);
        return r == Role.FACTORY_SECURITY;
    }

    /**
     * Nhân viên có hưởng lương theo giờ hay không, dựa vào Bộ phận (department).
     * Hiện chỉ xét department vì Phòng ban (division) mới được sửa để lưu ổn định;
     * có thể mở rộng xét thêm division sau.
     */
    private static boolean isHourlyBased(String department, String division) {
        String d = normalize(department);
        for (String kw : HOURLY_DEPT_KEYWORDS) {
            if (d.contains(kw)) return true;
        }
        return false;
    }

    // ── Current user helper ───────────────────────────────────────────────────

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("Người dùng không tồn tại"));
    }

    /**
     * Các role ĐƯỢC PHÉP điều chỉnh lương, xếp theo độ ưu tiên hiển thị.
     * OWNER cố tình KHÔNG nằm trong danh sách: OWNER là người DUYỆT lương,
     * để tách bạch người lập và người duyệt.
     */
    private static final List<Role> SALARY_EDITOR_ROLES = List.of(
            Role.SUPER_ACCOUNTANT,
            Role.HR);

    /**
     * Role đang thao tác của request hiện tại, đã kiểm tra quyền điều chỉnh lương.
     *
     * <p>Lấy từ authorities của JWT (tôn trọng claim selected_role khi tài khoản
     * có nhiều role) chứ KHÔNG lấy {@code user.getRole()} — cột đó chỉ là role
     * chính trong DB nên hay hiển thị sai người thực hiện.
     *
     * @throws BusinessException nếu role đang dùng không được phép chỉnh lương.
     */
    private Role requireSalaryEditorRole(
            org.springframework.security.core.Authentication auth) {
        Role acting = (auth != null)
                ? AuthRoleUtil.actingRole(auth, SALARY_EDITOR_ROLES)
                : AuthRoleUtil.currentActingRole(SALARY_EDITOR_ROLES);

        if (acting == null)
            throw new BusinessException(
                    "Chỉ Kế toán trưởng hoặc Nhân sự mới được điều chỉnh lương. "
                            + "Nếu tài khoản của bạn có nhiều vai trò, hãy chuyển sang đúng vai trò rồi thử lại.");
        return acting;
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Nhân viên không tồn tại: " + id));
    }

    // ── Employee info ─────────────────────────────────────────────────────────

    /**
     * Cập nhật Bộ phận / Chức vụ và ĐỒNG BỘ LUÔN ROLE HƯỞNG LƯƠNG.
     *
     * <p>Bộ phận &amp; chức vụ là danh sách chọn cố định
     * ({@link com.nhatnam.server.enumtype.OrgCatalog}), mỗi cặp ứng với đúng
     * một role hưởng lương. Ghi thẳng vào {@code _user.payroll_role} nên hệ
     * thống KHÔNG còn phải suy ngược từ tập role đăng nhập nữa.
     *
     * <p>Nhờ vậy nhân viên kiêm nhiệm được xử lý đúng: một người có cả
     * {@code SELLER} và {@code WAREHOUSE} (kiêm coi kho) nếu được set chức vụ
     * "Nhân viên kinh doanh" thì payroll_role = {@code SELLER} — vẫn vào được
     * màn hình kho nhưng lương luôn tính ở bộ phận Kinh doanh.
     */
    @Transactional
    public void updateEmployeeInfo(Long userId, UpdateEmployeeInfoRequest req) {
        User u = findUser(userId);
        if (req.getDepartment() != null) u.setDepartment(req.getDepartment());
        if (req.getDivision()   != null) u.setDivision(req.getDivision());
        if (req.getPosition()   != null) u.setPosition(req.getPosition());

        // Ngày vào làm: null = không đụng tới, 0 = xoá trắng (nhập nhầm thì sửa lại được).
        if (req.getWorkStartDate() != null) {
            u.setWorkStartDate(req.getWorkStartDate() > 0 ? req.getWorkStartDate() : null);
        }

        // Suy role hưởng lương từ cặp (Bộ phận, Chức vụ) vừa chọn.
        com.nhatnam.server.enumtype.Role payrollRole =
                com.nhatnam.server.enumtype.OrgCatalog.payrollRoleOf(u.getDepartment(), u.getPosition());
        if (payrollRole != null) u.setPayrollRole(payrollRole);

        userRepository.save(u);
    }

    // ── Salary ────────────────────────────────────────────────────────────────

    @Transactional
    public SalaryDto setSalary(SalaryRequest req) {
        return setSalary(req, null);
    }

    @Transactional
    public SalaryDto setSalary(SalaryRequest req, org.springframework.security.core.Authentication auth) {
        // Role ĐANG THAO TÁC (theo JWT), đồng thời chặn luôn role không có quyền
        Role actingRole = requireSalaryEditorRole(auth);
        String actorLabel = actingRole.getLabel();

        User hr   = currentUser();
        User user = findUser(req.getUserId());

        // Chuẩn hoá danh sách phụ cấp: ưu tiên danh sách chi tiết; nếu không có thì
        // fallback về field tổng `allowance` (tương thích luồng cũ / Excel).
        java.util.List<AllowanceItemDto> items = req.getAllowances();
        long allowanceTotal;
        if (items != null && !items.isEmpty()) {
            allowanceTotal = items.stream()
                    .filter(a -> a != null && a.getAmount() != null)
                    .mapToLong(a -> Math.max(0, a.getAmount())).sum();
        } else {
            allowanceTotal = req.getAllowance() != null ? Math.max(0, req.getAllowance()) : 0L;
        }
        boolean bonusTaxable = Boolean.TRUE.equals(req.getBonusTaxable());

        // Tạo bản ghi PENDING mới
        //
        //   LƯƠNG ĐÓNG BẢO HIỂM: giá trị 0 gửi lên là CỐ Ý ("nhân viên không đóng
        //   bảo hiểm") nên được LƯU THẲNG 0 vào DB, không bị đổi thành lương cơ
        //   bản — xem resolveInsuranceSalary.
        EmployeeSalary salary = EmployeeSalary.builder()
                .user(user)
                .baseSalary(req.getBaseSalary())
                .insuranceSalary(resolveInsuranceSalary(req.getInsuranceSalary(), req.getBaseSalary()))
                .allowance(allowanceTotal)
                .bonus(req.getBonus() != null ? req.getBonus() : 0L)
                .bonusTaxable(bonusTaxable)
                .dependents(req.getDependents() != null ? req.getDependents() : 0)
                .status("PENDING")
                .createdBy(hr)
                .createdAt(System.currentTimeMillis())
                .updatedAt(System.currentTimeMillis())
                .build();

        // Gắn chi tiết từng khoản phụ cấp (nếu có)
        if (items != null) {
            for (AllowanceItemDto a : items) {
                if (a == null || a.getAmount() == null) continue;
                salary.getAllowanceItems().add(EmployeeSalaryAllowance.builder()
                        .salary(salary)
                        .label(a.getLabel() != null && !a.getLabel().isBlank() ? a.getLabel().trim() : "Phụ cấp")
                        .amount(Math.max(0, a.getAmount()))
                        .taxable(a.isTaxable())
                        .build());
            }
        }
        salaryRepository.save(salary);

        // Notify owner
        notificationService.sendToRole("OWNER", "HR_SALARY_PENDING",
                actorLabel + " vừa cập nhật lương cho " + user.getFullName() + " — chờ duyệt",
                "{\"salaryId\":" + salary.getId() + ",\"userId\":" + user.getId() + "}");

        return toSalaryDto(salary);
    }

    @Transactional(readOnly = true)
    public List<SalaryDto> listLatestApprovedSalaries() {
        // Lấy lương APPROVED mới nhất cho mỗi user
        return salaryRepository.findLatestApprovedPerUser().stream()
                .map(this::toSalaryDto)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Lấy bản ghi lương hiện hành (mới nhất, bất kể trạng thái) của 1 nhân viên —
     * dùng để hiển thị lại lương đã nhập khi mở form "Cập nhật lương".
     * Trả null nếu nhân viên chưa từng được nhập lương.
     */
    @Transactional(readOnly = true)
    public SalaryDto getCurrentSalary(Long userId) {
        List<EmployeeSalary> all = salaryRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (all.isEmpty()) return null;
        return toSalaryDto(all.get(0));
    }

    /**
     * LƯƠNG ĐANG ÁP DỤNG + PHIẾU LƯƠNG MỚI CHỜ DUYỆT của một nhân viên.
     *
     * <p>Khác {@link #getCurrentSalary(Long)} ở chỗ hàm đó trả về bản ghi MỚI NHẤT
     * bất kể trạng thái — nếu có phiếu mới đang chờ, nó che mất mức lương đang
     * thực sự áp dụng. Màn so sánh của OWNER cần tách bạch hai thứ đó.
     *
     * <p>Cả hai vế đều có thể null: nhân viên chưa từng được duyệt lương thì
     * {@code current} null (FE hiện thẳng phiếu mới kèm nút Duyệt), còn khi
     * không có phiếu chờ thì {@code pending} null (FE chỉ hiện lương hiện tại).
     */
    @Transactional(readOnly = true)
    public SalaryOverviewDto getSalaryOverview(Long userId) {
        List<EmployeeSalary> approved = salaryRepository.findApprovedByUserId(userId);
        List<EmployeeSalary> pending  = salaryRepository.findPendingByUserId(userId);

        EmployeeSalary current = approved.isEmpty() ? null : approved.get(0);
        EmployeeSalary latestPending = pending.isEmpty() ? null : pending.get(0);

        String fullName = null;
        if (current != null && current.getUser() != null) fullName = current.getUser().getFullName();
        else if (latestPending != null && latestPending.getUser() != null) fullName = latestPending.getUser().getFullName();
        if (fullName == null) {
            fullName = userRepository.findById(userId).map(User::getFullName).orElse(null);
        }

        return SalaryOverviewDto.builder()
                .userId(userId)
                .userFullName(fullName)
                .current(current == null ? null : toSalaryDto(current))
                .pending(latestPending == null ? null : toSalaryDto(latestPending))
                .build();
    }

    /**
     * Lấy bản ghi lương hiện hành (mới nhất) của TẤT CẢ nhân viên đã từng được
     * nhập lương — dùng cho trang Owner xem breakdown lương toàn bộ nhân sự.
     */
    @Transactional(readOnly = true)
    public List<SalaryDto> listCurrentSalaries() {
        Map<Long, EmployeeSalary> latestByUser = new LinkedHashMap<>();
        // findAllFiltered(null, ...) đã sort theo createdAt DESC nên bản ghi đầu
        // tiên gặp cho mỗi user chính là bản ghi mới nhất.
        for (EmployeeSalary s : salaryRepository.findAll(
                org.springframework.data.domain.Sort.by("createdAt").descending())) {
            latestByUser.putIfAbsent(s.getUser().getId(), s);
        }
        return latestByUser.values().stream().map(this::toSalaryDto).collect(java.util.stream.Collectors.toList());
    }

    // ── MỨC LƯƠNG ĐÓNG BẢO HIỂM ───────────────────────────────────────────────

    /**
     * MỨC LƯƠNG ĐÓNG THUẾ/BẢO HIỂM HIỆU LỰC.
     *
     * <p>Phân biệt rạch ròi hai trường hợp mà trước đây bị gộp làm một:
     * <ul>
     *   <li><b>{@code null} — CHƯA NHẬP.</b> Form để trống, hoặc dữ liệu cũ chưa
     *       có cột này. Mặc định lấy = lương cơ bản (NET) để không phá luồng cũ.</li>
     *   <li><b>{@code 0} — KHÔNG ĐÓNG BẢO HIỂM.</b> Người nhập cố ý gõ số 0:
     *       nhân viên thời vụ / thử việc / hợp đồng dịch vụ không tham gia
     *       BHXH-BHYT-BHTN. Số 0 được GIỮ NGUYÊN và lưu thẳng vào DB.</li>
     * </ul>
     *
     * <p>Trước đây điều kiện là {@code insuranceSalary > 0} nên số 0 rơi vào
     * nhánh fallback và âm thầm bị ghi đè bằng lương cơ bản — nhân viên không
     * đóng bảo hiểm vẫn bị trừ đủ 10,5% và công ty vẫn bị tính 21,5%.
     *
     * @return mức lương đóng BH, luôn ≥ 0
     */
    private static long resolveInsuranceSalary(Long insuranceSalary, Long baseSalary) {
        if (insuranceSalary != null) return Math.max(0L, insuranceSalary);
        return baseSalary != null ? Math.max(0L, baseSalary) : 0L;
    }

    /**
     * Nhân viên KHÔNG tham gia bảo hiểm bắt buộc.
     *
     * <p>Mức đóng BH = 0 ⇒ mọi khoản BHXH/BHYT/BHTN của cả người lao động lẫn
     * doanh nghiệp đều bằng 0. Thuế TNCN thì VẪN tính bình thường — không đóng
     * bảo hiểm không có nghĩa là được miễn thuế.
     */
    private static boolean isInsuranceExempt(long insuranceSalary) {
        return insuranceSalary <= 0;
    }

    /**
     * Breakdown lương toàn bộ nhân sự cho Owner xem: lương trước thuế, tổng tiền
     * bảo hiểm doanh nghiệp đóng (21.5%), bảo hiểm + thuế TNCN người lao động
     * đóng, lương NET, và tổng chi phí — kèm 1 dòng tổng cộng tất cả nhân viên.
     * Dùng lương hiện hành mới nhất (bất kể trạng thái duyệt) của mỗi nhân viên.
     */
    @Transactional(readOnly = true)
    public SalaryBreakdownSummaryDto getSalaryBreakdownSummary() {
        List<SalaryDto> currentSalaries = listCurrentSalaries();

        List<SalaryBreakdownDto> rows = new ArrayList<>();
        long totalBase = 0, totalAllowance = 0, totalBonus = 0;
        long totalEmployerIns = 0, totalEmployeeIns = 0, totalPit = 0, totalNet = 0, totalCost = 0;

        for (SalaryDto s : currentSalaries) {
            User u = findUser(s.getUserId());
            SalaryBreakdownDto row = computeBreakdown(
                    u, s.getDepartment(), s.getDivision(), s.getPosition(), s.getStatus(),
                    s.getBaseSalary() != null ? s.getBaseSalary() : 0L,
                    // s.getInsuranceSalary() đã đi qua resolveInsuranceSalary ở
                    // toSalaryDto nên 0 ở đây nghĩa là "không đóng bảo hiểm" —
                    // truyền thẳng, KHÔNG resolve lại lần nữa kẻo mất số 0.
                    s.getInsuranceSalary() != null ? Math.max(0L, s.getInsuranceSalary()) : 0L,
                    s.getDependents() != null ? s.getDependents() : 0,
                    s.getAllowances(),
                    s.getBonus() != null ? s.getBonus() : 0L,
                    Boolean.TRUE.equals(s.getBonusTaxable()), null, null);
            row.setUserId(s.getUserId());
            row.setUserFullName(s.getUserFullName());
            rows.add(row);

            totalBase += row.getBaseSalary();
            totalAllowance += row.getAllowance();
            totalBonus += row.getEffectiveBonus();
            totalEmployerIns += row.getEmployerInsuranceTotal();
            totalEmployeeIns += row.getEmployeeInsuranceTotal();
            totalPit += row.getPersonalIncomeTax();
            totalNet += row.getNetSalary();
            totalCost += row.getTotalCost();
        }

        rows.sort(Comparator.comparing(r -> r.getUserFullName() == null ? "" : r.getUserFullName()));

        return SalaryBreakdownSummaryDto.builder()
                .rows(rows)
                .totalBaseSalary(totalBase)
                .totalAllowance(totalAllowance)
                .totalBonus(totalBonus)
                .totalEmployerInsurance(totalEmployerIns)
                .totalEmployeeInsurance(totalEmployeeIns)
                .totalPersonalIncomeTax(totalPit)
                .totalNetSalary(totalNet)
                .totalCost(totalCost)
                .build();
    }

    /**
     * BREAKDOWN LƯƠNG CỦA ĐÚNG 1 NHÂN VIÊN.
     *
     * <p>Dùng cho trang "Quản lý lương" của nhân viên (card Phiếu lương) và cho
     * bảng tổng hợp lương theo bộ phận của OWNER. Trả về CHÍNH bộ số liệu mà
     * OWNER nhìn thấy khi mở "Chi tiết lương" ở {@code /owner/employees}:
     * các khoản cấu thành, GROSS, bảo hiểm, thuế TNCN và lương NET.
     *
     * <p>Lấy bản ghi lương HIỆN HÀNH (mới nhất, bất kể trạng thái duyệt) giống
     * hệt {@link #getSalaryBreakdownSummary()} để hai màn hình luôn khớp số.
     *
     * @return {@code null} nếu nhân viên chưa từng được nhập lương
     */
    @Transactional(readOnly = true)
    public SalaryBreakdownDto getSalaryBreakdownForUser(Long userId) {
        return getSalaryBreakdownForUser(userId, null, null);
    }

    /**
     * Như trên nhưng CHIA LƯƠNG THEO NGÀY CÔNG của kỳ {@code month/year}.
     *
     * <p>Truyền {@code null} cho tháng/năm thì tính ở mức ĐỦ CÔNG (26 công) —
     * dùng cho các màn hình xem hồ sơ lương không gắn với kỳ nào.
     */
    @Transactional(readOnly = true)
    public SalaryBreakdownDto getSalaryBreakdownForUser(Long userId, Integer month, Integer year) {
        List<EmployeeSalary> all = salaryRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (all.isEmpty()) return null;

        SalaryDto s = toSalaryDto(all.get(0));
        User u = findUser(userId);

        SalaryBreakdownDto row = computeBreakdown(
                u, s.getDepartment(), s.getDivision(), s.getPosition(), s.getStatus(),
                s.getBaseSalary() != null ? s.getBaseSalary() : 0L,
                // Đã resolve ở toSalaryDto — 0 nghĩa là không đóng bảo hiểm.
                s.getInsuranceSalary() != null ? Math.max(0L, s.getInsuranceSalary()) : 0L,
                s.getDependents() != null ? s.getDependents() : 0,
                s.getAllowances(),
                s.getBonus() != null ? s.getBonus() : 0L,
                Boolean.TRUE.equals(s.getBonusTaxable()),
                month, year);

        row.setUserId(userId);
        row.setUserFullName(u.getFullName());
        return row;
    }

    /**
     * Preview breakdown lương từ dữ liệu SUPER_ACCOUNTANT đang nhập (CHƯA lưu) —
     * để hiển thị panel xem trước giống hệt màn hình Owner xem chi tiết.
     *
     * <p>FE gửi {@code insuranceSalary = null} khi để trống ô nhập, và
     * {@code 0} khi cố ý chọn "không đóng bảo hiểm" — bản xem trước vì thế khớp
     * đúng với bản sẽ được lưu.
     */
    @Transactional(readOnly = true)
    public SalaryBreakdownDto previewSalary(SalaryRequest req) {
        User u = req.getUserId() != null ? findUser(req.getUserId()) : null;
        String dept = u != null ? u.getDepartment() : null;
        String div  = u != null ? u.getDivision() : null;
        String pos  = u != null ? u.getPosition() : null;

        long baseNet = req.getBaseSalary() != null ? req.getBaseSalary() : 0L;
        long insSalary = resolveInsuranceSalary(req.getInsuranceSalary(), baseNet);
        int dependents = req.getDependents() != null ? req.getDependents() : 0;
        long bonus = req.getBonus() != null ? req.getBonus() : 0L;
        boolean bonusTaxable = Boolean.TRUE.equals(req.getBonusTaxable());

        List<AllowanceItemDto> allowances = req.getAllowances();
        if ((allowances == null || allowances.isEmpty()) && req.getAllowance() != null && req.getAllowance() > 0) {
            // Fallback: tổng phụ cấp cũ coi như 1 khoản không tính thuế
            allowances = List.of(AllowanceItemDto.builder()
                    .label("Phụ cấp").amount(req.getAllowance()).taxable(false).build());
        }

        SalaryBreakdownDto dto = computeBreakdown(u, dept, div, pos, "PREVIEW",
                baseNet, insSalary, dependents, allowances, bonus, bonusTaxable,
                req.getMonth(), req.getYear());
        if (u != null) {
            dto.setUserId(u.getId());
            dto.setUserFullName(u.getFullName());
        }
        return dto;
    }

    /**
     * TÍNH TOÁN CHUNG breakdown lương cho 1 nhân viên theo mô hình:
     * <ul>
     *   <li>Lương cơ bản (NET) → prorate theo giờ nếu là phòng Xưởng/Sản xuất.</li>
     *   <li>Thưởng nhân theo % KPI.</li>
     *   <li>Phụ cấp/thưởng có cờ "tính thuế": phần CÓ tính thuế được cộng vào NET
     *       chịu thuế rồi gross-up chung (ảnh hưởng thuế TNCN); phần KHÔNG tính
     *       thuế cộng thẳng vào GROSS mà không chịu thuế.</li>
     *   <li>Bảo hiểm (NLĐ 10.5% &amp; DN 21.5%) luôn tính CỐ ĐỊNH trên lương đóng BH.
     *       Riêng khi lương đóng BH = 0 thì nhân viên KHÔNG tham gia bảo hiểm:
     *       toàn bộ khoản BH của hai bên đều bằng 0, chỉ còn thuế TNCN.</li>
     * </ul>
     */
    private SalaryBreakdownDto computeBreakdown(User u, String department, String division, String position,
                                                String status, long standardNetBase, long insSalary, int dependents,
                                                List<AllowanceItemDto> allowances, long bonusInput, boolean bonusTaxable,
                                                Integer month, Integer year) {

        // (1) KPI → thưởng thực lĩnh
        double kpiPercent = u != null ? payrollInputProvider.getKpiPercentage(u) : 100.0;
        long effectiveBonus = PayrollTaxCalculator.applyKpiToBonus(bonusInput, kpiPercent);

        // ══════════════════════════════════════════════════════════════════════
        // (1b) LƯƠNG KHOÁN THEO HỢP ĐỒNG BÊN THỨ BA — BẢO VỆ XƯỞNG
        // ══════════════════════════════════════════════════════════════════════
        //
        //   Bảo vệ xưởng do công ty bảo vệ bên ngoài cung cấp, không phải người
        //   lao động ký hợp đồng trực tiếp. Công ty trả một khoản NET cố định
        //   theo hợp đồng; mọi nghĩa vụ thuế TNCN và bảo hiểm thuộc về bên thứ
        //   ba đó. Vì vậy ở đây KHÔNG gross-up, KHÔNG trích bảo hiểm, KHÔNG tính
        //   thuế, và KHÔNG cộng phụ cấp cơm (suất ăn do bên kia lo).
        //
        //   Hai khoản họ VẪN được hưởng:
        //     · Thưởng KPI xưởng — mức CỐ ĐỊNH 300.000đ, nằm ở FactoryKpiService
        //       (SECURITY_FIXED_BONUS) và là CỘT RIÊNG, không nằm trong netSalary
        //       nên không xử lý ở hàm này.
        //     · Thưởng thêm import từ file Excel của tháng — cộng thẳng vào NET.
        //
        //   Lương KHÔNG chia theo ngày công: hợp đồng khoán trọn tháng, thiếu
        //   người thì bên thứ ba tự bố trí thay ca.
        if (isFlatContractRole(u, department, position)) {
            long importedBonus = 0L;
            java.util.List<com.nhatnam.server.dto.hr.HrDtos.BonusItemDto> bonusItemsOutFlat = new ArrayList<>();
            if (u != null && month != null && year != null) {
                var payrollDept = payrollDepartmentResolver.departmentOf(u);
                String deptCode = payrollDept != null ? payrollDept.name() : null;
                for (MonthlyAdjustment adj : monthlyAdjustmentRepository
                        .findByUserPeriodAndDepartment(u.getId(), month, year, deptCode)) {
                    if (adj.getType() != MonthlyAdjustment.Type.BONUS) continue;
                    long amt = adj.getAmount() != null ? Math.max(0, adj.getAmount()) : 0L;
                    if (amt <= 0) continue;
                    importedBonus += amt;
                    bonusItemsOutFlat.add(com.nhatnam.server.dto.hr.HrDtos.BonusItemDto.builder()
                            .label(adj.getLabel() != null && !adj.getLabel().isBlank()
                                    ? adj.getLabel() : "Thưởng khác")
                            .amount(amt).build());
                }
            }

            long bonusTotal = effectiveBonus + importedBonus;
            long effectiveBonusKpiOnlyFlat = effectiveBonus;  // trước khi cộng import
            long netExact = standardNetBase + bonusTotal;

            return SalaryBreakdownDto.builder()
                    .department(department).division(division).position(position).status(status)
                    .flatContract(true)
                    .baseSalary(standardNetBase).standardBaseSalary(standardNetBase)
                    .allowance(0L).allowances(List.of())
                    .bonus(bonusInput).effectiveBonus(bonusTotal).bonusTaxable(false)
                    .bonusItems(bonusItemsOutFlat)
                    .bonusItemsTotal(importedBonus)
                    .effectiveBonusKpiOnly(effectiveBonusKpiOnlyFlat)
                    .dependents(0)
                    .taxableAdditions(0L).nonTaxableAdditions(bonusTotal)
                    .kpiPercent(kpiPercent)
                    .hourlyBased(false).attendanceProrated(false)
                    // Ép kiểu (double) là BẮT BUỘC: STANDARD_WORKDAYS là int còn
                    // field là Double, mà Java không cho vừa nới rộng vừa đóng hộp
                    // trong một bước (int → double → Double).
                    .standardWorkdays(month != null && year != null
                            ? PayrollTaxCalculator.standardWorkdaysOf(month, year)
                            : (double) PayrollTaxCalculator.STANDARD_WORKDAYS)
                    .actualWorkdays(month != null && year != null
                            ? PayrollTaxCalculator.standardWorkdaysOf(month, year)
                            : (double) PayrollTaxCalculator.STANDARD_WORKDAYS)
                    .standardWorkHours(PayrollTaxCalculator.STANDARD_WORK_HOURS)
                    .actualWorkHours(PayrollTaxCalculator.STANDARD_WORK_HOURS)
                    .dailyRate(0L)
                    .payrollMonth(month).payrollYear(year)
                    // GROSS = NET: không có phần khấu trừ nào ở phía công ty.
                    .grossSalary(netExact).insuranceSalary(0L)
                    .employeeSocialInsurance(0L).employeeHealthInsurance(0L)
                    .employeeUnemploymentInsurance(0L).employeeInsuranceTotal(0L)
                    .employerSocialInsurance(0L).employerAccidentInsurance(0L)
                    .employerHealthInsurance(0L).employerUnemploymentInsurance(0L)
                    .employerInsuranceTotal(0L)
                    .preTaxIncome(netExact)
                    .personalDeduction(0L).dependentDeduction(0L)
                    .taxableIncome(0L).personalIncomeTax(0L)
                    .pitBrackets(List.of())
                    .netSalary(roundNetPayout(netExact)).netSalaryExact(netExact)
                    .totalCost(netExact)
                    .build();
        }

        // ── (2) LƯƠNG THEO NGÀY CÔNG — bộ phận Xưởng sản xuất ─────────────────
        //
        //   Lương nhập trong hồ sơ là mức ĐỦ CÔNG (26 công/tháng). Lương thực
        //   nhận được chia ngược ra theo số công thực tế trên bảng chấm công:
        //
        //       đơn giá ngày = lương chuẩn / 26            (làm tròn về đồng)
        //       lương thực   = đơn giá ngày × công thực tế (làm tròn về đồng)
        //
        //   VD lương 8.000.000đ, làm 22,64/26 công
        //       → 8.000.000 / 26          = 307.692đ/ngày
        //       → 307.692 × 22,64         = 6.966.147đ
        //
        //   Số 6.966.147đ này là căn cứ TÍNH THUẾ TNCN (cộng thêm phụ cấp /
        //   thưởng chịu thuế). MỨC LƯƠNG ĐÓNG BẢO HIỂM thì KHÔNG bị chia —
        //   vẫn giữ nguyên `insSalary` đã nhập trong hồ sơ.
        boolean hourlyBased = isHourlyBased(department, division);

        // ── CÔNG CHUẨN THEO ĐÚNG THÁNG ĐANG TÍNH ──────────────────────────────
        //   = số ngày trong tháng trừ các Chủ nhật (T7 tính tròn 1 công).
        //
        //   KHÔNG lấy cứng 26: tháng 7/2026 có 27 công, tháng 2/2026 chỉ có 24.
        //   Chia lương theo mẫu số 26 trong khi bảng chấm công chấm tối đa 27 ngày
        //   thì nhân viên nghỉ nguyên một ngày vẫn "đủ công" và ăn tròn lương.
        //
        //   Chỉ rơi về mặc định 26 khi chưa biết tháng (màn hình xem trước mức
        //   lương trong hồ sơ, chưa gắn với kỳ lương nào).
        double standardDays = (month != null && year != null)
                ? PayrollTaxCalculator.standardWorkdaysOf(month, year)
                : PayrollTaxCalculator.STANDARD_WORKDAYS;

        double actualDays   = standardDays;
        boolean attendanceProrated = false;
        long netBase = standardNetBase;

        if (hourlyBased && u != null && month != null && year != null) {
            Double days = actualWorkdaysOf(u.getId(), month, year);
            if (days != null) {
                actualDays = days;
                attendanceProrated = true;
                netBase = PayrollTaxCalculator.prorateSalaryByDays(
                        standardNetBase, actualDays, standardDays);
            }
        }

        long dailyRate = hourlyBased
                ? PayrollTaxCalculator.dailyRate(standardNetBase, standardDays) : 0L;

        // Giữ lại thông tin theo GIỜ cho các màn hình cũ (quy đổi từ ngày công)
        double standardHours = standardDays * PayrollTaxCalculator.HOURS_PER_DAY;
        double actualHours = hourlyBased
                ? actualDays * PayrollTaxCalculator.HOURS_PER_DAY
                : standardHours;

        // (3) Phụ cấp — TOÀN BỘ đều tính vào thu nhập chịu thuế
        //
        //   BỎ khái niệm "phụ cấp miễn thuế": theo yêu cầu, mọi khoản phụ cấp đều
        //   cộng vào thu nhập rồi mới tính thuế (VD lương 15tr + phụ cấp 2tr →
        //   chịu thuế trên 17tr). Cờ `taxable` trên entity được giữ lại để không
        //   phải migrate dữ liệu cũ nhưng KHÔNG còn được đọc ở đây.
        //
        //   Riêng PHỤ CẤP CƠM được tính lại theo NGÀY ĐI LÀM THỰC TẾ (xem
        //   mealAllowanceFor): đi làm ngày nào có tiền cơm ngày đó.
        long allowanceTotal = 0;
        List<AllowanceItemDto> allowanceOut = new ArrayList<>();
        Long mealOverride = mealAllowanceFor(u, month, year);
        boolean mealApplied = false;

        if (allowances != null) {
            for (AllowanceItemDto a : allowances) {
                if (a == null || a.getAmount() == null) continue;

                long amt = Math.max(0, a.getAmount());
                String label = a.getLabel() != null ? a.getLabel() : "Phụ cấp";

                if (mealOverride != null && isMealAllowance(label)) {
                    amt = mealOverride;
                    mealApplied = true;
                }

                allowanceTotal += amt;
                allowanceOut.add(AllowanceItemDto.builder()
                        .label(label).amount(amt).taxable(true).build());
            }
        }

        // Nhân viên chưa có dòng phụ cấp cơm trong hồ sơ vẫn được hưởng — mức cơm
        // là chính sách chung của công ty, không phải thoả thuận riêng từng người.
        if (mealOverride != null && !mealApplied && mealOverride > 0) {
            allowanceTotal += mealOverride;
            allowanceOut.add(AllowanceItemDto.builder()
                    .label(MEAL_ALLOWANCE_LABEL).amount(mealOverride).taxable(true).build());
        }

        // ── (3b) PHỤ CẤP THÂM NIÊN ────────────────────────────────────────────
        //   Chính sách chung như phụ cấp cơm: hệ thống TỰ tính từ ngày vào làm,
        //   không ai nhập tay vào hồ sơ lương. Gốc nhân % là lương cơ bản CHUẨN
        //   (standardNetBase) chứ không phải netBase đã chia theo ngày công —
        //   thâm niên gắn với thời gian gắn bó, không gắn với số ngày đi làm.
        SeniorityResult seniority = resolveSeniority(u, standardNetBase, month, year);

        if (seniority.amount() > 0) {
            // Hồ sơ cũ có thể đã có sẵn một dòng "phụ cấp thâm niên" nhập tay.
            // Bỏ dòng đó đi rồi thay bằng số hệ thống tính, nếu không sẽ cộng đôi.
            for (Iterator<AllowanceItemDto> it = allowanceOut.iterator(); it.hasNext(); ) {
                AllowanceItemDto a = it.next();
                if (SeniorityCalculator.isSeniorityAllowance(a.getLabel())) {
                    allowanceTotal -= (a.getAmount() != null ? a.getAmount() : 0L);
                    it.remove();
                }
            }

            allowanceTotal += seniority.amount();
            allowanceOut.add(AllowanceItemDto.builder()
                    .label(SeniorityCalculator.labelFor(seniority.years(), seniority.percent()))
                    .amount(seniority.amount())
                    .taxable(true)
                    .build());
        }

        // ── PHỤ CẤP & THƯỞNG IMPORT THEO THÁNG ────────────────────────────────
        //   Xăng xe, điện thoại, lì xì Tết… không cố định nên không nằm trong hồ
        //   sơ lương mà được OWNER import Excel mỗi kỳ (bảng monthly_adjustment).
        long monthlyBonus = 0L;
        java.util.List<com.nhatnam.server.dto.hr.HrDtos.BonusItemDto> bonusItemsOut = new ArrayList<>();
        if (u != null && month != null && year != null) {
            // Chỉ lấy adjustment CỦA ĐÚNG BỘ PHẬN của user — tránh import cho
            // tài xế lại cộng vào lương xưởng và ngược lại. Dept của user resolve
            // qua PayrollDepartmentResolver (dùng payrollRole + department string).
            var payrollDept = payrollDepartmentResolver.departmentOf(u);
            String deptCode = payrollDept != null ? payrollDept.name() : null;
            for (MonthlyAdjustment adj : monthlyAdjustmentRepository
                    .findByUserPeriodAndDepartment(u.getId(), month, year, deptCode)) {
                long amt = adj.getAmount() != null ? Math.max(0, adj.getAmount()) : 0L;
                if (amt <= 0) continue;

                if (adj.getType() == MonthlyAdjustment.Type.ALLOWANCE) {
                    allowanceTotal += amt;
                    allowanceOut.add(AllowanceItemDto.builder()
                            .label(adj.getLabel()).amount(amt).taxable(true).build());
                } else {
                    monthlyBonus += amt;
                    bonusItemsOut.add(com.nhatnam.server.dto.hr.HrDtos.BonusItemDto.builder()
                            .label(adj.getLabel() != null && !adj.getLabel().isBlank()
                                    ? adj.getLabel() : "Thưởng khác")
                            .amount(amt).build());
                }
            }
        }

        // Thưởng import là số tiền ẤN ĐỊNH, KHÔNG nhân với tỉ lệ KPI như thưởng
        // trong hồ sơ — lì xì Tết không phụ thuộc KPI.
        long effectiveBonusKpiOnly = effectiveBonus;   // ghi lại phần THUẦN KPI
        effectiveBonus += monthlyBonus;

        // ── THƯỞNG KPI SẢN XUẤT THEO SẢN LƯỢNG (chỉ Xưởng) ────────────────────
        //   Từ bảng factory_kpi_bonus_item — Bảo vệ 300k cố định, các vai trò
        //   khác chia theo trọng số × sản lượng tấn. FE render THÀNH DÒNG RIÊNG
        //   "Thưởng KPI sản xuất theo sản lượng" kèm chi tiết kg × đơn giá/tấn
        //   thay vì gộp vào KPI hồ sơ.
        if (u != null && month != null && year != null) {
            var kpiItemOpt = factoryKpiBonusItemRepository
                    .findByUserAndPeriod(u.getId(), month, year);
            if (kpiItemOpt.isPresent()) {
                var kpiItem = kpiItemOpt.get();
                Long amtRaw = kpiItem.getAmount();
                long amt = amtRaw != null ? amtRaw : 0L;
                if (amt > 0) {
                    monthlyBonus += amt;
                    effectiveBonus += amt;
                    // Đọc tổng sản lượng + đơn giá/tấn từ quỹ mẹ để bổ sung
                    // vào nhãn — nhân viên xem lương biết ngay tháng đó xưởng
                    // sản xuất bao nhiêu kg và đơn giá thưởng là bao nhiêu.
                    String detail = "";
                    var parent = kpiItem.getKpiBonus();
                    if (parent != null) {
                        long kgLong = parent.getTotalOutputKg() != null
                                ? parent.getTotalOutputKg().setScale(0,
                                    java.math.RoundingMode.HALF_UP).longValue() : 0L;
                        long rate = parent.getRatePerTon() != null ? parent.getRatePerTon() : 0L;
                        if (kgLong > 0 && rate > 0) {
                            detail = " (%s kg × %s đ/tấn)".formatted(fmtVN(kgLong), fmtVN(rate));
                        }
                    }
                    bonusItemsOut.add(com.nhatnam.server.dto.hr.HrDtos.BonusItemDto.builder()
                            .label("Thưởng KPI sản xuất theo sản lượng" + detail)
                            .amount(amt).build());
                }
            }
        }

        // (4) Thưởng cũng tính vào thu nhập chịu thuế
        long taxableAdditions = allowanceTotal + effectiveBonus;
        long nonTaxableAdditions = 0L;

        // ── (5) GROSS-UP & BẢO HIỂM ───────────────────────────────────────────
        //
        //   KHÔNG ĐÓNG BẢO HIỂM (insSalary = 0):
        //     · calcGrossFromNetFixedInsurance(net, 0, deps) → calcInsurance(0)
        //       trả về toàn 0, nên GROSS = thu nhập trước thuế, không bị trừ BH.
        //     · calcEmployerInsurance(0) cũng trả về toàn 0 ⇒ chi phí DN chỉ còn
        //       đúng phần GROSS.
        //     · Thuế TNCN VẪN được tính bình thường trên GROSS − giảm trừ gia cảnh.
        //
        //   Cả hai hàm của PayrollTaxCalculator đã tự chặn mức đóng ≤ 0 nên
        //   không cần rẽ nhánh riêng ở đây; `insuranceExempt` chỉ dùng để đánh
        //   dấu cho FE hiển thị "Không đóng bảo hiểm" thay vì "0đ".
        boolean insuranceExempt = isInsuranceExempt(insSalary);

        long netTaxableTarget = netBase + taxableAdditions;
        PayrollTaxCalculator.NetToGrossResult ntg =
                PayrollTaxCalculator.calcGrossFromNetFixedInsurance(netTaxableTarget, insSalary, dependents);

        long fullGross = ntg.grossSalary + nonTaxableAdditions;
        PayrollTaxCalculator.EmployerInsuranceResult erIns =
                PayrollTaxCalculator.calcEmployerInsurance(insSalary);

        // ── LƯƠNG THỰC NHẬN ───────────────────────────────────────────────────
        //   netSalaryExact là số TẠM TÍNH, gần như luôn lẻ tới hàng đồng vì lương
        //   được chia theo ngày công và phụ cấp cơm chia cho 26. Số nhân viên thật
        //   sự nhận được làm tròn về hàng NGHÌN cho gọn khi chi trả.
        long netSalaryExact = netBase + allowanceTotal + effectiveBonus; // = fullGross - BH NLĐ - thuế TNCN
        long netSalary = roundNetPayout(netSalaryExact);
        long totalCost = fullGross + erIns.total;
        long preTaxFull = Math.max(0, fullGross - ntg.totalInsuranceAmount);

        // Chi tiết thuế theo bậc
        List<PitBracketDto> brackets = PayrollTaxCalculator.calcPitBreakdown(ntg.taxableIncome).stream()
                .map(b -> PitBracketDto.builder()
                        .ratePercent(b.ratePercent)
                        .incomeInBracket(b.incomeInBracket)
                        .taxInBracket(b.taxInBracket)
                        .build())
                .collect(java.util.stream.Collectors.toList());

        return SalaryBreakdownDto.builder()
                .department(department).division(division).position(position).status(status)
                .baseSalary(netBase).standardBaseSalary(standardNetBase)
                .allowance(allowanceTotal).allowances(allowanceOut)
                .bonus(bonusInput).effectiveBonus(effectiveBonus).bonusTaxable(true)
                .bonusItems(bonusItemsOut)
                .bonusItemsTotal(monthlyBonus)
                .effectiveBonusKpiOnly(effectiveBonusKpiOnly)
                .dependents(dependents)
                .workStartDate(seniority.workStartDate())
                .seniorityReferenceDate(seniority.referenceDate())
                .seniorityYears(seniority.years())
                .seniorityPercent(seniority.percent())
                .seniorityAllowance(seniority.amount())
                .taxableAdditions(taxableAdditions).nonTaxableAdditions(nonTaxableAdditions)
                .kpiPercent(kpiPercent).hourlyBased(hourlyBased)
                .standardWorkHours(standardHours).actualWorkHours(actualHours)
                .attendanceProrated(attendanceProrated)
                .standardWorkdays(standardDays).actualWorkdays(actualDays)
                .dailyRate(dailyRate)
                .payrollMonth(month).payrollYear(year)
                .grossSalary(fullGross).insuranceSalary(insSalary)
                // Cờ để FE hiện "Không đóng bảo hiểm" thay vì một loạt dòng 0đ.
                .insuranceExempt(insuranceExempt)
                .employeeSocialInsurance(ntg.socialInsuranceAmount)
                .employeeHealthInsurance(ntg.healthInsuranceAmount)
                .employeeUnemploymentInsurance(ntg.unemploymentInsuranceAmount)
                .employeeInsuranceTotal(ntg.totalInsuranceAmount)
                .employerSocialInsurance(erIns.socialInsurance)
                .employerAccidentInsurance(erIns.accidentInsurance)
                .employerHealthInsurance(erIns.healthInsurance)
                .employerUnemploymentInsurance(erIns.unemploymentInsurance)
                .employerInsuranceTotal(erIns.total)
                .preTaxIncome(preTaxFull)
                .personalDeduction(ntg.personalDeduction)
                .dependentDeduction(ntg.dependentDeduction)
                .taxableIncome(ntg.taxableIncome)
                .personalIncomeTax(ntg.personalIncomeTax)
                .pitBrackets(brackets)
                .netSalary(netSalary).netSalaryExact(netSalaryExact).totalCost(totalCost)
                .build();
    }

    // ── THÂM NIÊN ─────────────────────────────────────────────────────────────

    /**
     * Kết quả tính thâm niên của 1 nhân viên trong 1 kỳ.
     *
     * @param referenceDate mốc đã dùng để đếm năm (null khi chưa xác định được)
     */
    public record SeniorityResult(Long workStartDate, Long referenceDate,
                                  int years, int percent, long amount) {

        static final SeniorityResult NONE = new SeniorityResult(null, null, 0, 0, 0L);
    }

    /**
     * THÂM NIÊN &amp; PHỤ CẤP THÂM NIÊN của nhân viên trong kỳ lương.
     *
     * <p>Mốc đếm năm LUÔN là NGÀY CUỐI CỦA KỲ LƯƠNG (xem
     * {@link #payrollReferenceDate(Integer, Integer)}), nên kết quả chỉ phụ thuộc
     * (ngày vào làm, kỳ lương) — bấm Hoàn tất lúc nào cũng ra cùng một số.
     *
     * <p>Vẫn ưu tiên đọc BẢN CHỐT của kỳ ({@code employee_seniority}) nếu có. Lý
     * do không còn là "sợ trôi theo ngày xem" nữa mà là: {@code workStartDate} có
     * thể bị SỬA về sau (nhập nhầm rồi chỉnh lại). Không có bản chốt thì lần sửa
     * đó âm thầm làm đổi luôn phụ cấp của những tháng đã trả xong.
     *
     * <p>Không có tháng/năm (màn hình xem hồ sơ lương chung, panel preview) thì
     * lấy HÔM NAY làm mốc — đúng nghĩa "thâm niên tính tới thời điểm này".
     */
    private SeniorityResult resolveSeniority(User u, long standardBaseSalary,
                                             Integer month, Integer year) {
        if (u == null) return SeniorityResult.NONE;

        // 1) Bản chốt của kỳ — giữ nguyên con số đã trả, kể cả khi hồ sơ sửa sau.
        if (month != null && year != null) {
            EmployeeSeniority snap = seniorityRepository
                    .findByUserAndPeriod(u.getId(), month, year).orElse(null);
            if (snap != null) {
                return new SeniorityResult(
                        snap.getWorkStartDate(), snap.getReferenceDate(),
                        snap.getYears() != null ? snap.getYears() : 0,
                        snap.getPercent() != null ? snap.getPercent() : 0,
                        snap.getAmount() != null ? snap.getAmount() : 0L);
            }
        }

        Long workStart = u.getWorkStartDate();
        if (workStart == null) return SeniorityResult.NONE;   // chưa khai báo ⇒ 0đ

        Long reference = payrollReferenceDate(month, year);

        int years   = SeniorityCalculator.years(workStart, reference);
        int percent = SeniorityCalculator.percentOf(years);
        long amount = SeniorityCalculator.allowance(standardBaseSalary, percent);

        return new SeniorityResult(workStart, reference, years, percent, amount);
    }

    /**
     * NGÀY CHỐT dùng để đếm thâm niên của kỳ {@code month/year}.
     *
     * <h3>Là NGÀY CUỐI CỦA KỲ LƯƠNG, KHÔNG phải ngày bấm Hoàn tất</h3>
     * Lương thường được chốt vào ngày 1 đầu tháng sau, TÍNH CHO THÁNG TRƯỚC. Nếu
     * lấy ngày bấm nút làm mốc thì nhân viên được cộng thêm phần thâm niên của
     * những ngày KHÔNG thuộc kỳ lương đang tính:
     * <pre>
     *   Vào làm 29/02/2024 · kỳ lương T2/2026 (01/02 → 28/02) · bấm Hoàn tất 01/03/2026
     *     mốc = 01/03/2026 (ngày bấm)       → 2 năm   ✗ SAI, tính dôi 1 ngày ngoài kỳ
     *     mốc = 28/02/2026 (cuối kỳ lương)  → 1 năm   ✓ ĐÚNG
     * </pre>
     * Việc "trừ lui 1 tháng" được thực hiện SẴN ở đây: tham số {@code month/year}
     * chính là THÁNG ĐƯỢC TRẢ LƯƠNG, nên lấy cuối tháng đó là đã lùi đúng một
     * tháng so với ngày thao tác — không cần trừ thêm ở đâu nữa.
     *
     * <p>Hệ quả tốt: con số CHỈ phụ thuộc (ngày vào làm, kỳ lương), không phụ
     * thuộc lúc nào bấm nút. Bản xem trước và bản chốt luôn khớp nhau, và mở lại
     * tháng rồi Hoàn tất lần nữa cũng ra đúng con số cũ.
     */
    static Long payrollReferenceDate(Integer month, Integer year) {
        if (month == null || year == null) return System.currentTimeMillis();
        return endOfMonthMillis(month, year);
    }

    /**
     * 23:59:59 ngày cuối tháng, theo giờ VN — NGÀY CUỐI CỦA KỲ LƯƠNG.
     *
     * <p>{@code public} vì {@code FactoryPayrollService} (khác package) dùng
     * chung hàm này khi chốt thâm niên, để hai bên không bao giờ lệch định nghĩa
     * "cuối tháng".
     */
    public static long endOfMonthMillis(int month, int year) {
        return YearMonth.of(year, month)
                .atEndOfMonth()
                .atTime(23, 59, 59)
                .atZone(SeniorityCalculator.ZONE)
                .toInstant()
                .toEpochMilli();
    }

    /**
     * SỐ CÔNG THỰC TẾ của nhân viên trong kỳ, đọc từ bảng chấm công đã import.
     *
     * @return {@code null} khi CHƯA CÓ dữ liệu chấm công của kỳ đó — khi đó
     *         lương giữ nguyên mức đủ công thay vì bị tính thành 0đ.
     */
    private Double actualWorkdaysOf(Long userId, int month, int year) {
        return attendanceEntryRepository.findByUserAndPeriod(userId, month, year)
                .map(e -> e.getActualDays() != null ? e.getActualDays() : 0.0)
                .orElse(null);
    }

    // ── Danh mục nhãn phụ cấp ──────────────────────────────────────────────────

    /**
     * PHỤ CẤP CƠM TRƯA — TÍNH THEO NGÀY, áp dụng CHUNG cho mọi nhân viên.
     *
     * <p>Khác với các phụ cấp còn lại (xăng xe, điện thoại… nhập theo tháng), cơm
     * là chính sách chung nên để hằng số ở đây thay vì lưu từng người. Sửa một
     * chỗ là đổi cho toàn công ty.
     *
     * <p><b>ĐƠN GIÁ NGÀY là gốc, không phải mức tháng.</b> Trước đây lưu mức đủ
     * công rồi chia cho 26 để ra tiền một ngày — phép chia đó không hết nên phải
     * làm tròn lên, khiến mọi phiếu lương đều lẻ tới hàng đồng và cộng ngược lại
     * không khớp mức tháng. Nay nhân thẳng đơn giá ngày × số ngày, con số luôn
     * tròn và kiểm tra bằng tay được:
     * <pre>
     *   30.000đ × 26 ngày = 780.000đ   (đủ công)
     *   30.000đ × 22 ngày = 660.000đ
     * </pre>
     */
    public static final long MEAL_ALLOWANCE_PER_DAY = 30_000L;

    /** Số công chuẩn của tháng — chỉ dùng để hiển thị mức đủ công. */
    public static final int MEAL_ALLOWANCE_STANDARD_DAYS = 26;

    /** Mức phụ cấp cơm khi đi làm ĐỦ công = 30.000 × 26 = 780.000đ. */
    public static final long MEAL_ALLOWANCE_FULL =
            MEAL_ALLOWANCE_PER_DAY * MEAL_ALLOWANCE_STANDARD_DAYS;

    /** Nhãn dùng khi tự thêm dòng phụ cấp cơm cho nhân viên chưa có. */
    public static final String MEAL_ALLOWANCE_LABEL = "Phụ cấp cơm trưa";

    /** Nhận diện dòng phụ cấp cơm trong hồ sơ (dữ liệu cũ đặt tên không thống nhất). */
    private static boolean isMealAllowance(String label) {
        if (label == null) return false;
        String s = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase();
        return s.contains("com");   // "Phụ cấp cơm", "Phụ cấp cơm trưa", "Tiền cơm"…
    }

    /**
     * PHỤ CẤP CƠM THỰC TẾ của nhân viên trong kỳ.
     *
     * <pre>
     *   tiền cơm = 30.000đ × số ngày ĐI LÀM
     * </pre>
     *
     * <p>Mốc là {@code mealDays} — SỐ NGÀY ĐƯỢC HƯỞNG CƠM, không phải số công.
     * Đi làm là có cơm, kể cả hôm đó chỉ tính nửa công vì đi trễ. Nghỉ phép được
     * duyệt CÓ LƯƠNG cũng được tính cơm dù không quẹt thẻ; nghỉ KHÔNG lương thì
     * không.
     *
     * <p>Không còn phép chia nên không phát sinh số lẻ: 30.000đ mỗi ngày đi làm.
     *
     * @return {@code null} khi chưa có bảng chấm công của kỳ (giữ nguyên mức trong
     *         hồ sơ thay vì tính thành 0đ), hoặc khi không xét theo kỳ nào.
     */
    private Long mealAllowanceFor(User u, Integer month, Integer year) {
        if (u == null || month == null || year == null) return null;

        AttendanceEntry entry = attendanceEntryRepository
                .findByUserAndPeriod(u.getId(), month, year)
                .orElse(null);
        if (entry == null) return null;

        // Ưu tiên mealDays — số ngày ĐƯỢC HƯỞNG CƠM, do khâu tính công dựng ra.
        //
        // Không dùng presentDays nữa vì hai con số đã tách nhau kể từ khi có đơn
        // nghỉ phép: nghỉ phép CÓ LƯƠNG thì không quẹt thẻ (presentDays không
        // đếm) nhưng theo quy định vẫn được tiền cơm; nghỉ KHÔNG lương thì mất
        // cả công lẫn cơm.
        //
        // Bản ghi cũ chưa tính lại có mealDays = null → quay về presentDays như
        // trước, để phiếu lương các tháng đã chốt không tự đổi số.
        double days = entry.getMealDays() != null
                ? entry.getMealDays()
                : (entry.getPresentDays() != null ? entry.getPresentDays() : 0);

        if (days <= 0) return 0L;

        // Nhân thẳng đơn giá ngày — KHÔNG chia cho 26 nữa, nên không còn số lẻ.
        return BigDecimal.valueOf(MEAL_ALLOWANCE_PER_DAY)
                .multiply(BigDecimal.valueOf(days))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    // ── Làm tròn lương chi trả ────────────────────────────────────────────────

    /** Bước làm tròn lương thực nhận: hàng NGHÌN. */
    public static final long NET_PAYOUT_ROUNDING_STEP = 1_000L;

    /**
     * Làm tròn lương thực nhận về hàng nghìn.
     *
     * <p><b>Đang làm tròn XUỐNG</b> theo đúng ví dụ được đưa ra
     * (6.114.236 → 6.114.000). Đổi sang {@link RoundingMode#CEILING} nếu muốn
     * làm tròn LÊN (6.114.236 → 6.115.000).
     */
    public static long roundNetPayout(long value) {
        if (value <= 0) return 0L;
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(NET_PAYOUT_ROUNDING_STEP), 0, RoundingMode.FLOOR)
                .multiply(BigDecimal.valueOf(NET_PAYOUT_ROUNDING_STEP))
                .longValue();
    }

    private static final String[] DEFAULT_ALLOWANCE_LABELS = {
            "Phụ cấp cơm trưa", "Phụ cấp điện thoại", "Phụ cấp xăng xe", "Phụ cấp công tác"
    };

    @Transactional
    public List<AllowanceLabelDto> listAllowanceLabels() {
        // Seed mặc định nếu danh mục trống
        if (allowanceLabelRepository.count() == 0) {
            for (String name : DEFAULT_ALLOWANCE_LABELS) {
                allowanceLabelRepository.save(AllowanceLabel.builder()
                        .name(name).system(true).createdAt(System.currentTimeMillis()).build());
            }
        }
        return allowanceLabelRepository.findAllByOrderBySystemDescNameAsc().stream()
                .map(l -> AllowanceLabelDto.builder().id(l.getId()).name(l.getName()).system(l.isSystem()).build())
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional
    public AllowanceLabelDto createAllowanceLabel(String name) {
        if (name == null || name.isBlank())
            throw new BusinessException("Tên khoản phụ cấp không được để trống");
        String trimmed = name.trim();
        AllowanceLabel existing = allowanceLabelRepository.findByNameIgnoreCase(trimmed).orElse(null);
        if (existing != null)
            return AllowanceLabelDto.builder().id(existing.getId()).name(existing.getName()).system(existing.isSystem()).build();
        AllowanceLabel saved = allowanceLabelRepository.save(AllowanceLabel.builder()
                .name(trimmed).system(false).createdAt(System.currentTimeMillis()).build());
        return AllowanceLabelDto.builder().id(saved.getId()).name(saved.getName()).system(saved.isSystem()).build();
    }

    @Transactional
    public List<SalaryDto> batchSetSalary(BatchSalaryRequest req) {
        // Lấy role đang thao tác từ SecurityContext (không cần đổi chữ ký hàm)
        String actorLabel = requireSalaryEditorRole(null).getLabel();

        User hr = currentUser();
        List<SalaryDto> results = new ArrayList<>();

        for (Long uid : req.getUserIds()) {
            User user = findUser(uid);
            EmployeeSalary salary = EmployeeSalary.builder()
                    .user(user)
                    .baseSalary(req.getBaseSalary())
                    // Cùng quy ước với setSalary: null = lấy theo lương cơ bản,
                    // 0 = cả nhóm này không đóng bảo hiểm.
                    .insuranceSalary(resolveInsuranceSalary(req.getInsuranceSalary(), req.getBaseSalary()))
                    .allowance(req.getAllowance() != null ? req.getAllowance() : 0L)
                    .bonus(req.getBonus() != null ? req.getBonus() : 0L)
                    .dependents(req.getDependents() != null ? req.getDependents() : 0)
                    .status("PENDING")
                    .createdBy(hr)
                    .createdAt(System.currentTimeMillis())
                    .updatedAt(System.currentTimeMillis())
                    .build();
            salaryRepository.save(salary);
            results.add(toSalaryDto(salary));
        }

        notificationService.sendToRole("OWNER", "HR_SALARY_PENDING",
                actorLabel + " vừa cập nhật lương hàng loạt cho "
                        + req.getUserIds().size() + " nhân viên — chờ duyệt",
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

    /** Duyệt nhiều phiếu lương cùng lúc — bỏ qua các phiếu không ở trạng thái PENDING. */
    @Transactional
    public List<SalaryDto> bulkApproveSalary(BulkApproveSalaryRequest req) {
        if (req.getSalaryIds() == null || req.getSalaryIds().isEmpty())
            throw new BusinessException("Vui lòng chọn ít nhất 1 phiếu lương để duyệt");

        User owner = currentUser();
        long now = System.currentTimeMillis();
        List<SalaryDto> results = new ArrayList<>();

        for (Long salaryId : req.getSalaryIds()) {
            EmployeeSalary salary = salaryRepository.findById(salaryId).orElse(null);
            if (salary == null || !"PENDING".equals(salary.getStatus())) continue;

            salary.setStatus("APPROVED");
            salary.setApprovedBy(owner);
            salary.setUpdatedAt(now);
            salaryRepository.save(salary);
            results.add(toSalaryDto(salary));

            if (salary.getCreatedBy() != null) {
                notificationService.sendToUser(salary.getCreatedBy(), "HR",
                        "HR_SALARY_APPROVED",
                        "Phiếu lương của " + salary.getUser().getFullName() + " đã được duyệt",
                        "{\"salaryId\":" + salaryId + "}");
            }
        }
        return results;
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

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private SalaryDto toSalaryDto(EmployeeSalary s) {
        long insSalary = resolveInsuranceSalary(s.getInsuranceSalary(), s.getBaseSalary());
        return SalaryDto.builder()
                .id(s.getId())
                .userId(s.getUser().getId())
                .userFullName(s.getUser().getFullName())
                .department(s.getUser().getDepartment() != null ? s.getUser().getDepartment() : "Nhân viên")
                .division(s.getUser().getDivision())
                .position(s.getUser().getPosition())
                .baseSalary(s.getBaseSalary())
                // 0 được giữ nguyên = nhân viên không đóng bảo hiểm.
                .insuranceSalary(insSalary)
                .insuranceExempt(isInsuranceExempt(insSalary))
                .allowance(s.getAllowance() != null ? s.getAllowance() : 0L)
                .allowances(s.getAllowanceItems() == null ? java.util.List.of() :
                        s.getAllowanceItems().stream()
                                .map(a -> AllowanceItemDto.builder()
                                        .label(a.getLabel())
                                        .amount(a.getAmount())
                                        .taxable(a.isTaxable())
                                        .build())
                                .collect(java.util.stream.Collectors.toList()))
                .bonus(s.getBonus() != null ? s.getBonus() : 0L)
                .bonusTaxable(Boolean.TRUE.equals(s.getBonusTaxable()))
                .dependents(s.getDependents() != null ? s.getDependents() : 0)
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

    /** Số nguyên → chuỗi kiểu VN "1.234.567" (dùng cho nhãn hiển thị). */
    private static String fmtVN(long v) {
        return String.format(java.util.Locale.GERMANY, "%,d", v);
    }
}