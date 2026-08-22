package com.nhatnam.server.enumtype;

import lombok.Getter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BỘ PHẬN TÍNH LƯƠNG.
 *
 * <p>Mỗi bộ phận có bảng chấm công / lịch nghỉ RIÊNG cho từng tháng. OWNER tải
 * lên từng file cho từng bộ phận, xử lý xong thì bấm "Hoàn tất" cho bộ phận đó.
 *
 * <pre>
 *   MANAGEMENT Quản lý cấp cao — Chủ Tịch (OWNER) / Giám Đốc (ADMIN) · tính lương tách riêng
 *   FACTORY    Xưởng sản xuất  — role FACTORY_* + SUPER_FACTORY_WORKER  · CÓ thưởng KPI sản xuất
 *   SALES      Kinh doanh      — role SELLER / SUPER_SELLER
 *   WAREHOUSE  Kho             — role WAREHOUSE / SUPER_WAREHOUSE
 *   ACCOUNTING Kế toán         — role ACCOUNTANT / SUPER_ACCOUNTANT
 *   DRIVER     Tài xế          — role DRIVER · KHÔNG có bảng chấm công (tính theo km chạy trong ngày)
 * </pre>
 *
 * <h3>Thứ tự ưu tiên khi 1 nhân viên kiêm nhiều role</h3>
 * Danh sách {@link #values()} đã xếp theo ĐỘ ƯU TIÊN GIẢM DẦN. Ví dụ Trần Mộng
 * Thuỳ có cả {@code SELLER} và {@code WAREHOUSE} (quản lý 2 kho) thì bộ phận
 * nhận lương là {@code SALES} vì SALES đứng trước WAREHOUSE.
 *
 * <p>Muốn ép cứng khác quy tắc trên thì set cột {@code payroll_role} của bảng
 * {@code _user} — giá trị đó luôn được ưu tiên tuyệt đối.
 */
@Getter
public enum PayrollDepartment {

    /**
     * BAN QUẢN LÝ — tài khoản có role {@code OWNER} hoặc {@code ADMIN}.
     *
     * <p>Đặt ĐẦU DANH SÁCH nên có độ ưu tiên cao nhất: người vừa là OWNER vừa
     * kiêm role khác (VD OWNER + SELLER) sẽ nhận lương ở đây, không rơi vào
     * Kinh doanh. Đây là chủ đích — lương ban quản lý tính riêng, không trộn vào
     * bảng lương của bộ phận mà họ kiêm nhiệm.
     *
     * <p>{@code attendanceBased = true} để dùng chung toàn bộ luồng upload bảng
     * chấm công → hoàn tất như các bộ phận văn phòng khác. KHÔNG đặt {@code false}:
     * cờ đó hiện đang đồng nghĩa với "tính lương theo số km" và sẽ đẩy nhóm này
     * sang nhánh xử lý của tài xế.
     */
    MANAGEMENT("Quản lý cấp cao", false, true, List.of(
            Role.OWNER,
            Role.ADMIN
    )),

    /** Xưởng sản xuất — bộ phận DUY NHẤT có bảng "Thưởng KPI sản xuất". */
    FACTORY("Xưởng sản xuất", true, true, List.of(
            Role.FACTORY_MANAGER,
            Role.SUPER_FACTORY_WORKER,
            Role.FACTORY_ACCOUNTANT,
            Role.FACTORY_STAFF,
            Role.FACTORY_PRODUCTION_WORKER,
            Role.FACTORY_WORKER,
            Role.FACTORY_SECURITY
    )),

    /** Kế toán. */
    ACCOUNTING("Kế toán", false, true, List.of(
            Role.SUPER_ACCOUNTANT,
            Role.ACCOUNTANT
    )),

    /** Kinh doanh. */
    SALES("Kinh doanh", false, true, List.of(
            Role.SUPER_SELLER,
            Role.SELLER
    )),

    /** Kho. */
    WAREHOUSE("Kho", false, true, List.of(
            Role.SUPER_WAREHOUSE,
            Role.WAREHOUSE
    )),

    /** Tài xế — không chấm công, tính theo số km chạy mỗi ngày. */
    DRIVER("Tài xế", false, false, List.of(
            Role.DRIVER
    ));

    /** Nhãn tiếng Việt hiển thị trên UI. */
    private final String label;

    /** Có bảng "Thưởng KPI sản xuất" hay không (chỉ Xưởng). */
    private final boolean kpiBonus;

    /** Có upload bảng chấm công / lịch nghỉ hay không (Tài xế thì không). */
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