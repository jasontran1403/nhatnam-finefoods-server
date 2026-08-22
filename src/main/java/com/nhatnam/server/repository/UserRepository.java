package com.nhatnam.server.repository;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * User đang hoạt động có role đã cho — xét CẢ {@code _user_roles} lẫn role chính.
     * (Trước đây chỉ JOIN u.roles nên bỏ sót user cũ chỉ có {@code u.role}.)
     */
    @Query("SELECT u FROM User u WHERE (:role MEMBER OF u.roles OR u.role = :role) " +
            "AND u.isLockAccount = false AND u.deleted = false")
    List<User> findByRolesContaining(@Param("role") Role role);

    Optional<User> findByPhoneNumber(String phoneNumber);

    /**
     * Nhân viên đang bị KHOÁ xem lương (sai passcode quá số lần cho phép).
     * Bỏ qua tài khoản đã xoá mềm — họ không đăng nhập được nên mở khoá vô nghĩa.
     */
    @Query("SELECT u FROM User u WHERE u.payrollPasscodeLocked = true AND u.deleted = false " +
            "ORDER BY u.payrollPasscodeLockedAt DESC")
    List<User> findPayrollPasscodeLocked();

    // ── Đăng nhập / tra cứu ──────────────────────────────────────────────────
    // Không cần lọc `deleted` ở đây: khi xoá mềm, username/email/phone đã bị đổi
    // thành "SOFT_DELETED_{id}_{giá trị cũ}" nên không thể khớp với giá trị gốc nữa.
    // Nhờ vậy vừa chặn đăng nhập, vừa GIẢI PHÓNG giá trị unique để tạo lại user mới.
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * Tìm kiếm nhân viên.
     *
     * <p><b>FIX — lọc theo role:</b> trước đây chỉ so {@code u.role} (role CHÍNH,
     * tức role mặc định khi đăng nhập). User đa role — VD role chính là ACCOUNTANT
     * nhưng trong {@code _user_roles} có thêm SUPER_FACTORY_WORKER — sẽ KHÔNG trả về,
     * khiến màn hình Owner gán nhân viên xưởng bị thiếu người.
     *
     * <p>Giờ khớp nếu role nằm trong TẬP ROLE của user ({@code u.roles}, bảng
     * {@code _user_roles}) HOẶC là role chính. Giữ cả 2 vế vì dữ liệu cũ có thể chỉ
     * set {@code u.role} mà chưa thêm vào {@code roles} (xem {@link User#getAllRoles()},
     * cũng hợp nhất 2 nguồn theo cách này).
     *
     * <p>MẶC ĐỊNH LOẠI BỎ nhân viên đã xoá mềm ({@code deleted = true}).
     * Truyền {@code includeDeleted = true} để xem cả nhân viên đã xoá (trang "Đã xoá").
     */
    @Query("SELECT u FROM User u WHERE " +
            "(:q IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:role IS NULL OR :role MEMBER OF u.roles OR u.role = :role) " +
            "AND (:locked IS NULL OR u.isLockAccount = :locked) " +
            "AND (:includeDeleted = true OR u.deleted = false)")
    Page<User> search(@Param("q") String q,
                      @Param("role") Role role,
                      @Param("locked") Boolean locked,
                      @Param("includeDeleted") boolean includeDeleted,
                      Pageable pageable);

    /** Chỉ nhân viên ĐÃ XOÁ (để xem lại / khôi phục) */
    @Query("SELECT u FROM User u WHERE u.deleted = true")
    Page<User> findDeleted(Pageable pageable);

    /** Lấy tất cả user có role WAREHOUSE hoặc SUPER_WAREHOUSE gắn với kho cụ thể.
     *  Kiểm tra cả warehouse_id đơn (legacy) lẫn bảng _user_warehouses (đa kho). */
    @Query(value =
            "SELECT DISTINCT u.* FROM _user u " +
                    "LEFT JOIN _user_roles ur ON ur.user_id = u.id " +
                    "LEFT JOIN _user_warehouses uw ON uw.user_id = u.id " +
                    "WHERE (u.warehouse_id = :warehouseId OR uw.warehouse_id = :warehouseId) " +
                    "AND u.is_lock_account = false " +
                    "AND (u.role IN ('WAREHOUSE','SUPER_WAREHOUSE') OR ur.role IN ('WAREHOUSE','SUPER_WAREHOUSE'))",
            nativeQuery = true)
    List<User> findActiveWarehouseUsersByWarehouseId(@Param("warehouseId") Long warehouseId);

    /** Lấy tất cả user theo role CHÍNH (ít dùng — thường muốn findByRolesContaining) */
    List<User> findByRole(Role role);

    /**
     * Dùng cho GỬI THÔNG BÁO theo role.
     *
     * <p>FIX: tên method giữ nguyên (nhiều nơi đang gọi) nhưng query đã được viết lại để
     * xét CẢ {@code _user_roles} lẫn role chính — trước đây user đa role không nhận được
     * thông báo của các role phụ. Đồng thời loại user đã xoá mềm.
     */
    @Query("SELECT u FROM User u WHERE (:role MEMBER OF u.roles OR u.role = :role) " +
            "AND u.isLockAccount = false AND u.deleted = false")
    List<User> findByRoleAndIsLockAccountFalse(@Param("role") Role role);

    /**
     * Lấy user theo CHỨC VỤ TRẢ LƯƠNG ({@code _user.payroll_role}).
     *
     * <p><b>Vì sao cần:</b> trước đây danh sách nhân sự của một bộ phận chỉ được dựng
     * từ ROLE ĐĂNG NHẬP ({@code findByRole} + {@code findByRolesContaining}). Nhân
     * viên được Kế toán trưởng set Bộ phận = "Xưởng sản xuất" / Chức vụ = "Kế toán
     * xưởng" (payroll_role = {@code FACTORY_ACCOUNTANT}) nhưng role đăng nhập chỉ có
     * {@code ACCOUNTANT} sẽ KHÔNG BAO GIỜ được tìm thấy → biến mất khỏi bảng lương
     * xưởng và khỏi bảng chia thưởng KPI.
     *
     * <p>Chức vụ trả lương là dữ liệu do người dùng chỉ định tường minh nên phải được
     * dùng làm nguồn tra cứu, không chỉ dùng để lọc.
     */
    List<User> findByPayrollRole(Role payrollRole);
}