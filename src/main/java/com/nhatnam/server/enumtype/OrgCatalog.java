package com.nhatnam.server.enumtype;

import lombok.Getter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DANH MỤC CỐ ĐỊNH "BỘ PHẬN → CHỨC VỤ → ROLE HƯỞNG LƯƠNG".
 *
 * <h3>Vì sao cần</h3>
 * Trước đây trang Nhân sự cho nhập TAY tự do ô "Bộ phận" / "Chức vụ", còn role
 * hưởng lương thì SUY NGƯỢC từ tập role của tài khoản
 * ({@link PayrollDepartment#resolvePayrollRole}). Cách đó sai với nhân viên
 * KIÊM NHIỆM: một người vừa {@code SELLER} vừa {@code WAREHOUSE} (kiêm coi kho)
 * vẫn phải hưởng lương theo {@code SELLER}, nhưng nếu quy tắc ưu tiên đổi thì
 * họ bị nhảy bộ phận.
 *
 * <h3>Cách làm mới</h3>
 * Bộ phận &amp; chức vụ trở thành DANH SÁCH CHỌN CỐ ĐỊNH. Kế toán trưởng chọn
 * bộ phận + chức vụ ở trang Nhân sự → hệ thống GHI THẲNG
 * {@code _user.payroll_role} theo bảng dưới đây. Role hưởng lương từ đó là dữ
 * liệu do người dùng quyết định, không còn suy đoán.
 *
 * <p>Tập role đăng nhập ({@code _user.roles}) vẫn giữ nguyên và độc lập — nhân
 * viên vẫn có thể có thêm {@code WAREHOUSE} để vào được màn hình kho.
 */
public final class OrgCatalog {

    private OrgCatalog() {}

    /** Một CHỨC VỤ trong bộ phận, kèm role hưởng lương tương ứng. */
    @Getter
    public static final class Position {
        private final String label;
        private final Role payrollRole;

        Position(String label, Role payrollRole) {
            this.label = label;
            this.payrollRole = payrollRole;
        }
    }

    /** Một BỘ PHẬN, kèm danh sách chức vụ hợp lệ. */
    @Getter
    public static final class Department {
        private final String label;
        private final PayrollDepartment payrollDepartment;
        private final List<Position> positions;

        /**
         * ROLE MẶC ĐỊNH khi chức vụ không khớp danh mục (VD dữ liệu cũ nhập tay).
         *
         * <p>PHẢI khai báo tường minh, KHÔNG được lấy "chức vụ cuối danh sách":
         * ở Xưởng sản xuất chức vụ cuối là <i>Bảo vệ</i> → nhân viên sẽ bị rơi vào
         * {@code FACTORY_SECURITY} và nhận thưởng CỐ ĐỊNH 300.000đ thay vì được
         * chia theo hệ số. Mặc định đúng phải là vị trí cơ bản nhất của bộ phận.
         */
        private final Role fallbackRole;

        Department(String label, PayrollDepartment payrollDepartment,
                   Role fallbackRole, List<Position> positions) {
            this.label = label;
            this.payrollDepartment = payrollDepartment;
            this.fallbackRole = fallbackRole;
            this.positions = positions;
        }
    }

    private static Position pos(String label, Role role) {
        return new Position(label, role);
    }

    /**
     * BẢNG CHÍNH — sửa ở đây là đổi toàn bộ danh sách chọn trên UI.
     * Thứ tự trong map = thứ tự hiển thị trong dropdown.
     */
    public static final Map<String, Department> DEPARTMENTS;

    static {
        Map<String, Department> m = new LinkedHashMap<>();

        // Phase 1 (10/2026): BAN LÃNH ĐẠO (OWNER / ADMIN) đã được gỡ khỏi hệ
        // thống tính lương. Enum PayrollDepartment.MANAGEMENT không còn tồn tại
        // nên entry "Quản lý cấp cao" bị xoá tại đây. OWNER/ADMIN vẫn giữ nguyên
        // quyền quản trị, chỉ là không hiển thị trên các tab tính lương / phiếu
        // lương / sơ đồ phòng ban nhận lương.

        // Hệ số chia thưởng KPI ghi kèm để đối chiếu — xem FactoryKpiService.ROLE_WEIGHTS
        m.put("Xưởng sản xuất", new Department("Xưởng sản xuất", PayrollDepartment.FACTORY, Role.FACTORY_PRODUCTION_WORKER, List.of(
                pos("Trưởng xưởng",          Role.SUPER_FACTORY_WORKER),      // ×1.5000
                pos("Quản lý xưởng",         Role.FACTORY_MANAGER),           // ×1.1125
                pos("Kế toán xưởng",         Role.FACTORY_ACCOUNTANT),        // ×1.1125
                pos("Công nhân sản xuất",    Role.FACTORY_PRODUCTION_WORKER), // ×1.0000 — mốc chuẩn
                pos("Trợ lý xưởng",          Role.FACTORY_STAFF),             // ×1.1125
                pos("Nhân viên văn phòng",   Role.FACTORY_WORKER),            // ×1.1125
                pos("Bảo vệ",                Role.FACTORY_SECURITY)           // cố định 300.000đ
        )));

        m.put("Kế Toán", new Department("Kế Toán", PayrollDepartment.ACCOUNTING, Role.ACCOUNTANT, List.of(
                pos("Kế toán trưởng",        Role.SUPER_ACCOUNTANT),
                pos("Chuyên viên kế toán",   Role.ACCOUNTANT)
        )));

        m.put("Kinh doanh", new Department("Kinh doanh", PayrollDepartment.SALES, Role.SELLER, List.of(
                pos("Trưởng phòng kinh doanh", Role.SUPER_SELLER),
                pos("Nhân viên kinh doanh",    Role.SELLER)
        )));

        m.put("Kho", new Department("Kho", PayrollDepartment.WAREHOUSE, Role.WAREHOUSE, List.of(
                pos("Quản lý kho",           Role.SUPER_WAREHOUSE),
                pos("Nhân viên kho",         Role.WAREHOUSE)
        )));

        m.put("Tài xế", new Department("Tài xế", PayrollDepartment.DRIVER, Role.DRIVER, List.of(
                pos("Tài xế giao nhận",      Role.DRIVER)
        )));

        DEPARTMENTS = Map.copyOf(m);
    }

    /** Giữ thứ tự hiển thị (Map.copyOf không đảm bảo thứ tự). */
    public static final List<String> DEPARTMENT_ORDER = List.of(
            "Quản lý cấp cao", "Xưởng sản xuất", "Kế Toán", "Kinh doanh", "Kho", "Tài xế");

    /** Danh sách bộ phận theo đúng thứ tự hiển thị trên UI. */
    public static List<Department> departments() {
        List<Department> out = new ArrayList<>();
        for (String k : DEPARTMENT_ORDER) out.add(DEPARTMENTS.get(k));
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRA CỨU
    // ══════════════════════════════════════════════════════════════════════════

    /** Bỏ dấu + gộp khoảng trắng + chữ thường, để so khớp dữ liệu cũ nhập tay. */
    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /** Tìm bộ phận theo nhãn (bỏ qua dấu / hoa thường). {@code null} nếu không khớp. */
    public static Department findDepartment(String label) {
        if (label == null || label.isBlank()) return null;
        String key = norm(label);
        for (String k : DEPARTMENT_ORDER) {
            if (norm(k).equals(key)) return DEPARTMENTS.get(k);
        }
        return null;
    }

    /** Tìm chức vụ trong 1 bộ phận. {@code null} nếu không khớp. */
    public static Position findPosition(String departmentLabel, String positionLabel) {
        Department d = findDepartment(departmentLabel);
        if (d == null || positionLabel == null || positionLabel.isBlank()) return null;
        String key = norm(positionLabel);
        for (Position p : d.getPositions()) {
            if (norm(p.getLabel()).equals(key)) return p;
        }
        return null;
    }

    /**
     * ROLE HƯỞNG LƯƠNG suy ra từ cặp (Bộ phận, Chức vụ).
     *
     * <p>Nếu chức vụ không khớp danh mục nhưng bộ phận thì khớp → trả về role
     * THẤP NHẤT (nhân viên thường) của bộ phận đó, để không mất bộ phận tính
     * lương. Trả {@code null} khi cả hai đều không khớp — khi đó gọi nơi khác
     * giữ nguyên payroll_role cũ.
     */
    public static Role payrollRoleOf(String departmentLabel, String positionLabel) {
        Position p = findPosition(departmentLabel, positionLabel);
        if (p != null) return p.getPayrollRole();

        Department d = findDepartment(departmentLabel);
        if (d != null) return d.getFallbackRole();
        return null;
    }

    /** TRUE nếu bộ phận này là Xưởng sản xuất (lương chia theo ngày công). */
    public static boolean isFactory(String departmentLabel) {
        Department d = findDepartment(departmentLabel);
        return d != null && d.getPayrollDepartment() == PayrollDepartment.FACTORY;
    }
}