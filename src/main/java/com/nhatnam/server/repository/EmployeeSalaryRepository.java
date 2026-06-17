package com.nhatnam.server.repository;

import com.nhatnam.server.entity.EmployeeSalary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeSalaryRepository extends JpaRepository<EmployeeSalary, Long> {

    /** Lấy bản ghi lương APPROVED mới nhất của user */
    @Query("SELECT s FROM EmployeeSalary s WHERE s.user.id = :userId AND s.status = 'APPROVED' ORDER BY s.updatedAt DESC")
    List<EmployeeSalary> findApprovedByUserId(@Param("userId") Long userId);

    /** Lấy bản ghi lương PENDING của user */
    @Query("SELECT s FROM EmployeeSalary s WHERE s.user.id = :userId AND s.status = 'PENDING' ORDER BY s.createdAt DESC")
    List<EmployeeSalary> findPendingByUserId(@Param("userId") Long userId);

    /** Tất cả bản ghi theo status, phân trang */
    Page<EmployeeSalary> findAllByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    /** Lấy APPROVED mới nhất của nhiều user (cho tính payslip hàng loạt) */
    @Query("SELECT s FROM EmployeeSalary s WHERE s.user.id IN :userIds AND s.status = 'APPROVED' ORDER BY s.updatedAt DESC")
    List<EmployeeSalary> findApprovedByUserIds(@Param("userIds") List<Long> userIds);

    /** Tất cả records có paging, filter theo status tuỳ chọn */
    @Query("SELECT s FROM EmployeeSalary s WHERE (:status IS NULL OR s.status = :status) ORDER BY s.createdAt DESC")
    Page<EmployeeSalary> findAllFiltered(@Param("status") String status, Pageable pageable);
}
