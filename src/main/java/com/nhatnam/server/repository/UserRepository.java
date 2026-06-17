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
    @Query("SELECT u FROM User u JOIN u.roles r WHERE r = :role AND u.isLockAccount = false")
    List<User> findByRolesContaining(@Param("role") Role role);

    Optional<User> findByPhoneNumber(String phoneNumber);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE " +
            "(:q IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:role IS NULL OR u.role = :role) " +
            "AND (:locked IS NULL OR u.isLockAccount = :locked)")
    Page<User> search(@Param("q") String q,
                      @Param("role") Role role,
                      @Param("locked") Boolean locked,
                      Pageable pageable);

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

    /** Lấy tất cả user theo role */
    List<User> findByRole(Role role);

    /** Lấy tất cả user theo role và không bị khoá */
    List<User> findByRoleAndIsLockAccountFalse(Role role);
}