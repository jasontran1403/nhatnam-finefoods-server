package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AttendanceLeaveRequest;
import com.nhatnam.server.enumtype.PayrollDepartment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AttendanceLeaveRequestRepository extends JpaRepository<AttendanceLeaveRequest, Long> {

    /** Toàn bộ đơn của 1 tháng cho 1 bộ phận. */
    List<AttendanceLeaveRequest> findByYearAndMonthAndDepartmentOrderByDayAsc(
            Integer year, Integer month, PayrollDepartment department);

    long countByYearAndMonthAndDepartment(Integer year, Integer month, PayrollDepartment department);

    void deleteByYearAndMonthAndDepartment(Integer year, Integer month, PayrollDepartment department);

    /** Đơn của 1 nhân viên trong 1 tháng. */
    List<AttendanceLeaveRequest> findByUser_IdAndYearAndMonth(Long userId, Integer year, Integer month);

    // ── Giữ lại cho các chỗ gọi cũ ────────────────────────────────────────────
    List<AttendanceLeaveRequest> findByYearAndMonthOrderByDayAsc(Integer year, Integer month);
}