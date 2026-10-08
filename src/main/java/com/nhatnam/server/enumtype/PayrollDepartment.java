package com.nhatnam.server.enumtype;

import lombok.Getter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BỘ PHẬN TÍNH LƯƠNG.
 *
 * <p>Phase 1 refactor (10/2026): dùng CHUNG 1 file chấm công cho cả công ty.
 * Enum này không còn dùng để phân mảnh file theo bộ phận (như trước); thay vào
 * đó nó chỉ còn giữ thông tin "nhân viên này thuộc phòng nào" phục vụ hiển thị
 * tab trên trang xem lương, và các bảng thưởng/KPI còn tính riêng theo bộ phận.
 *
 * <pre>
 *   FACTORY    Xưởng sản xuất  — role FACTORY_* + SUPER_FACTORY_WORKER  · CÓ thưởng KPI sản xuất
 *   ACCOUNTING Kế toán         — role ACCOUNTANT / SUPER_ACCOUNTANT
 *   SALES      Kinh doanh      — role SELLER / SUPER_SELLER
 *   WAREHOUSE  Kho             — role WAREHOUSE / SUPER_WAREHOUSE
 *   DRIVER     Tài xế          — role DRIVER (từ Phase 1 trở đi dùng chấm công, không dùng odo)
 * </pre>
 *
 * <h3>MANAGEMENT đã bị gỡ</h3>
 * Trước đây có {@code MANAGEMENT} dành cho OWNER / ADMIN. Theo yêu cầu refactor,
 * nhóm này không còn hiển thị trên UI tính lương và cũng không nằm trong bất kỳ
 * tab nào. OWNER/ADMIN nay không thuộc {@link PayrollDepartment} nào — các truy
 * vấn {@link #of(Role)} / {@link #resolvePayrollRole(Set)} sẽ trả {@code null}
 * cho họ, đồng nghĩa họ không có phiếu lương trong hệ thống.
 *
 * <h3>Thứ tự ưu tiên khi 1 nhân viên kiêm nhiều role</h3>
 * Danh sách {@link #values()} đã xếp theo ĐỘ ƯU TIÊN GIẢM DẦN. Người có cả
 * {@code SELLER} và {@code WAREHOUSE} (quản lý 2 kho) sẽ nhận lương ở
 * {@code SALES} vì SALES đứng trước {@code WAREHOUSE}.
 *
 * <p>Muốn ép cứng khác quy tắc trên thì set cột {@code payroll_role} của bảng
 * {@code _user} — giá trị đó luôn được ưu tiên tuyệt đối.
 */
@Getter
public enum PayrollDepartment {

    /** Xưởng sản xuất — bộ phận DUY NHẤT có bảng "Thưởng KPI sản xuất". */
    FACTORY("Xưởng sản xuất", true, true, List.of(
            Role.FACTORY_MANAGER,
            Role.SUPER_FACTORY_WORKER,
            Role.FACTORY_ACCOUNTANT,
            Role.FACTORY_STAFF,
            // FIX (10/2026): Thêm FACTORY_PACKAGING_WORKER vào bộ phận Xưởng.
            // Trước đó role này đã được định nghĩa trong Role.java và FactoryKpi
            // Service (hệ số 1.2) nhưng BỊ QUÊN ở enum này ⇒ allPayrollRoles()
            // không chứa nó ⇒ CompanyAttendanceService.allPayrollEmployees()
            // bỏ sót nhân viên đóng gói ⇒ resolveUser trả null khi parse file
            // chấm công ⇒ Ngô Thị Mỹ Hạnh (và bất kỳ ai thuần đóng gói) không
            // có AttendanceEntry ⇒ preview lương 0 công + Chuyên cần dòng rỗng.
            // Đặt NGAY SAU Trợ lý / Kế toán xưởng, trước CNSX — khớp thứ tự
            // KPI trong FactoryKpiService (hệ số 1.2 ngang Trợ lý).
            Role.FACTORY_PACKAGING_WORKER,
            Role.FACTORY_PRODUCTION_WORKER,
            Role.FACTORY_WORKER,
            Role.FACTORY_SECURITY
    )),

    /**
     * Kế toán — Phase 1: theo CHẤM CÔNG CHUNG (không còn mặc định full công như
     * các phiên bản trước).
     */
    ACCOUNTING("Kế toán", false, true, List.of(
            Role.SUPER_ACCOUNTANT,
            Role.ACCOUNTANT
    )),

    /**
     * Kinh doanh — Phase 1: theo CHẤM CÔNG CHUNG (không còn mặc định full công
     * cơ bản như trước). KPI/bonus vẫn tính riêng qua calcSalesKpi / calcSalesBonus.
     */
    SALES("Kinh doanh", false, true, List.of(
            Role.SUPER_SELLER,
            Role.SELLER
    )),

    /** Kho — Phase 1: theo CHẤM CÔNG CHUNG. */
    WAREHOUSE("Kho", false, true, List.of(
            Role.SUPER_WAREHOUSE,
            Role.WAREHOUSE
    )),

    /**
     * Tài xế — Phase 1: dùng CHẤM CÔNG CHUNG, KHÔNG còn tính theo số km (odo).
     * Phụ cấp xăng xe vẫn giữ như quy trình cũ nhưng không còn ảnh hưởng đến
     * ngày công nữa.
     */
    DRIVER("Tài xế", false, true, List.of(
            Role.DRIVER
    ));

    /** Nhãn tiếng Việt hiển thị trên UI. */
    private final String label;

    /** Có bảng "Thưởng KPI sản xuất" hay không (chỉ Xưởng). */
    private final boolean kpiBonus;

    /** Có upload bảng chấm công / lịch nghỉ hay không. Phase 1: tất cả = true. */
    private final boolean attendanceBased;

    /** Các role thuộc bộ phận, xếp theo độ ưu tiên giảm dần trong nội bộ bộ phận. */
    private final List<Role> roles;

    PayrollDepartment(String label, boolean kpiBonus, boolean attendanceBased, List<Role> roles) {
        this.label = label;
        this.kpiBonus = kpiBonus;
        this.attendanceBased = attendanceBased;
        this.roles = roles;
    }

    /** Bộ phận của 1 role cụ thể, {@code null} nếu role không hưởng lương theo bộ phận nào. */
    public static PayrollDepartment of(Role role) {
        if (role == null) return null;
        for (PayrollDepartment d : values()) {
            if (d.roles.contains(role)) return d;
        }
        return null;
    }

    /**
     * ROLE NHẬN LƯƠNG được chọn ra từ tập role của nhân viên.
     *
     * <p>Duyệt theo thứ tự ưu tiên bộ phận → thứ tự ưu tiên role trong bộ phận,
     * trả về role đầu tiên khớp. Trả {@code null} nếu nhân viên không có role nào
     * thuộc 5 bộ phận trên (VD OWNER / ADMIN — không có tab Quản lý lương).
     */
    public static Role resolvePayrollRole(Set<Role> userRoles) {
        if (userRoles == null || userRoles.isEmpty()) return null;
        for (PayrollDepartment d : values()) {
            for (Role r : d.roles) {
                if (userRoles.contains(r)) return r;
            }
        }
        return null;
    }

    /** Toàn bộ role được coi là "có lương theo bộ phận". */
    public static Set<Role> allPayrollRoles() {
        Set<Role> all = new LinkedHashSet<>();
        for (PayrollDepartment d : values()) all.addAll(d.roles);
        return all;
    }

    /** Parse an toàn từ chuỗi (query param), {@code null} nếu không hợp lệ. */
    public static PayrollDepartment parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Arrays.stream(values())
                .filter(d -> d.name().equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElse(null);
    }
}