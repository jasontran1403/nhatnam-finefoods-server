package com.nhatnam.server.service.hr;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.FactoryKpiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * XÁC ĐỊNH ROLE NHẬN LƯƠNG &amp; BỘ PHẬN TÍNH LƯƠNG của từng nhân viên.
 *
 * <h3>Vì sao cần</h3>
 * Một nhân viên có thể KIÊM nhiều role. Ví dụ Trần Mộng Thuỳ vừa là
 * {@code SELLER} vừa là {@code WAREHOUSE} (quản lý 2 kho) nhưng lương chính
 * nhận theo {@code SELLER}. Khi tính lương / xếp bộ phận / import chấm công thì
 * chỉ được dùng ĐÚNG MỘT role — role nhận lương.
 *
 * <h3>Thứ tự quyết định</h3>
 * <ol>
 *   <li>Cột {@code _user.payroll_role} — do OWNER/HR set tay, ưu tiên tuyệt đối.</li>
 *   <li>Suy ra tự động theo {@link PayrollDepartment} (FACTORY → ACCOUNTING →
 *       SALES → WAREHOUSE → DRIVER).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class PayrollDepartmentResolver {

    private final UserRepository userRepo;

    /** Nhãn hiển thị cho các role NGOÀI xưởng (xưởng đã có trong FactoryKpiService). */
    private static final Map<Role, String> EXTRA_ROLE_LABELS = Map.ofEntries(
            Map.entry(Role.ACCOUNTANT, "Kế toán"),
            Map.entry(Role.SUPER_ACCOUNTANT, "Kế toán trưởng"),
            Map.entry(Role.SELLER, "Nhân viên kinh doanh"),
            Map.entry(Role.SUPER_SELLER, "Trưởng phòng kinh doanh"),
            Map.entry(Role.WAREHOUSE, "Nhân viên kho"),
            Map.entry(Role.SUPER_WAREHOUSE, "Quản lý kho"),
            Map.entry(Role.DRIVER, "Tài xế"),
            Map.entry(Role.SECURITY, "Bảo vệ"),
            // Nhãn phải trùng CHỨC VỤ trong OrgCatalog, vì đây là chữ hiện trên
            // phiếu lương và bảng lương của ban lãnh đạo.
            Map.entry(Role.OWNER, "Chủ Tịch"),
            Map.entry(Role.ADMIN, "Giám Đốc")
    );

    // ══════════════════════════════════════════════════════════════════════════
    // ROLE NHẬN LƯƠNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ROLE NHẬN LƯƠNG của nhân viên.
     * {@code null} khi nhân viên không thuộc bộ phận nào (OWNER / ADMIN / HR…).
     */
    public Role payrollRoleOf(User user) {
        if (user == null) return null;

        // 1. Đã set tay trong _user.payroll_role → dùng luôn
        //
        //    KHÔNG chèn ngoại lệ cho OWNER/ADMIN ở đây. Bộ phận "Quản lý cấp cao"
        //    được CHỌN ở trang Nhân sự (OrgCatalog), và lựa chọn đó ghi thẳng vào
        //    cột này. Nếu code tự ý kéo mọi tài khoản có role OWNER về ban lãnh
        //    đạo thì người được xếp có chủ đích sang bộ phận khác sẽ bị kéo ngược,
        //    và ô chọn trên UI thành vô nghĩa.
        Role explicit = user.getPayrollRole();
        if (explicit != null && PayrollDepartment.of(explicit) != null) return explicit;

        // 2. Suy ra từ tập role hiện có
        return PayrollDepartment.resolvePayrollRole(user.getAllRoles());
    }

    /** Bộ phận tính lương của nhân viên ({@code null} nếu không hưởng lương theo bộ phận). */
    public PayrollDepartment departmentOf(User user) {
        return PayrollDepartment.of(payrollRoleOf(user));
    }

    /** Nhãn vị trí hiển thị trên phiếu lương. */
    public String roleLabelOf(User user) {
        Role r = payrollRoleOf(user);
        if (r == null) return user != null ? user.getPosition() : null;
        String factoryLabel = FactoryKpiService.ROLE_LABELS.get(r);
        if (factoryLabel != null) return factoryLabel;
        return EXTRA_ROLE_LABELS.getOrDefault(r, r.name());
    }

    /** Nhãn vị trí theo role (dùng khi chỉ có role, không có user). */
    public static String labelOf(Role r) {
        if (r == null) return null;
        String factoryLabel = FactoryKpiService.ROLE_LABELS.get(r);
        if (factoryLabel != null) return factoryLabel;
        return EXTRA_ROLE_LABELS.getOrDefault(r, r.name());
    }

    /**
     * NHÂN VIÊN KHÔNG THEO DÕI CHẤM CÔNG.
     *
     * <p>Hiện chỉ Bảo vệ xưởng ({@code FACTORY_SECURITY}): do đơn vị bảo vệ bên
     * ngoài cung cấp và bố trí ca, công ty trả khoán trọn tháng nên không quẹt
     * thẻ và cũng không có mặt trong file chấm công của xưởng.
     *
     * <p>Đặt ở đây thay vì kiểm rải rác {@code == FACTORY_SECURITY} để hai nơi
     * phụ thuộc nhau không bị lệch: khâu import chấm công BỎ QUA họ, thì khâu
     * chia thưởng KPI cũng KHÔNG được xét ngày công của họ nữa — nếu không,
     * {@code attendanceRatio} không tìm thấy bản ghi sẽ trả 0 và cắt sạch thưởng.
     */
    public static boolean isAttendanceExempt(Role payrollRole) {
        return payrollRole == Role.FACTORY_SECURITY;
    }

    /** Như trên, tra theo nhân viên. */
    public boolean isAttendanceExempt(User user) {
        return isAttendanceExempt(payrollRoleOf(user));
    }

    /**
     * Nhân viên của bộ phận CÓ THEO DÕI CHẤM CÔNG — dùng khi import bảng chấm công.
     * Khác {@link #employeesOf} ở chỗ đã loại người hưởng khoán, để họ không bị
     * báo "thiếu trong file" mỗi lần OWNER tải bảng chấm công lên.
     */
    @Transactional(readOnly = true)
    public List<User> attendanceEmployeesOf(PayrollDepartment department) {
        return employeesOf(department).stream()
                .filter(u -> !isAttendanceExempt(u))
                .toList();
    }

    /** TRUE nếu nhân viên được vào trang "Quản lý lương". */
    public boolean hasPayroll(User user) {
        return payrollRoleOf(user) != null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DANH SÁCH NHÂN SỰ THEO BỘ PHẬN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Toàn bộ nhân viên đang hoạt động của 1 bộ phận, xếp theo họ tên.
     *
     * <p>Nhân viên kiêm nhiệm CHỈ xuất hiện ở bộ phận của role nhận lương —
     * Trần Mộng Thuỳ (SELLER + WAREHOUSE) chỉ nằm trong danh sách Kinh doanh,
     * không nằm trong danh sách Kho.
     */
    @Transactional(readOnly = true)
    public List<User> employeesOf(PayrollDepartment department) {
        if (department == null) return List.of();

        Map<Long, User> merged = new LinkedHashMap<>();
        for (Role r : department.getRoles()) {
            userRepo.findByRole(r).forEach(u -> merged.put(u.getId(), u));
            userRepo.findByRolesContaining(r).forEach(u -> merged.put(u.getId(), u));
            // BẮT BUỘC: tra thêm theo CHỨC VỤ TRẢ LƯƠNG. Kế toán xưởng thường chỉ có
            // role đăng nhập ACCOUNTANT (để vào màn hình kế toán) trong khi
            // payroll_role = FACTORY_ACCOUNTANT. Thiếu dòng này họ sẽ không xuất hiện
            // trong bảng lương Xưởng sản xuất.
            userRepo.findByPayrollRole(r).forEach(u -> merged.put(u.getId(), u));
        }

        return merged.values().stream()
                .filter(u -> !u.isLockAccount())
                .filter(u -> !u.isDeleted())
                // Lọc lại theo ROLE NHẬN LƯƠNG để loại nhân viên kiêm nhiệm
                // đang hưởng lương ở bộ phận khác.
                .filter(u -> departmentOf(u) == department)
                .sorted(Comparator.comparing(u -> u.getFullName() != null ? u.getFullName() : ""))
                .toList();
    }

    /** Toàn bộ nhân viên có lương, gom theo bộ phận. */
    @Transactional(readOnly = true)
    public Map<PayrollDepartment, List<User>> allByDepartment() {
        Map<PayrollDepartment, List<User>> out = new LinkedHashMap<>();
        for (PayrollDepartment d : PayrollDepartment.values()) out.put(d, employeesOf(d));
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BACKFILL — set payroll_role cho các user chưa có
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Điền {@code payroll_role} cho những nhân viên chưa có, suy ra từ tập role
     * hiện tại. Gọi 1 lần sau khi chạy migration, hoặc mỗi khi OWNER đổi role
     * của nhân viên.
     *
     * @return số bản ghi được cập nhật
     */
    @Transactional
    public int backfillPayrollRoles() {
        int updated = 0;
        for (User u : userRepo.findAll()) {
            if (u.isDeleted()) continue;

            if (u.getPayrollRole() != null && PayrollDepartment.of(u.getPayrollRole()) != null) continue;

            Role resolved = PayrollDepartment.resolvePayrollRole(u.getAllRoles());
            if (resolved == null) continue;

            u.setPayrollRole(resolved);
            userRepo.save(u);
            updated++;
        }
        return updated;
    }
}