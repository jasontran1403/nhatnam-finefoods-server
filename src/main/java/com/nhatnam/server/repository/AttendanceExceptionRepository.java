package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AttendanceException;
import com.nhatnam.server.enumtype.PayrollDepartment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AttendanceExceptionRepository extends JpaRepository<AttendanceException, Long> {

    /** Toàn bộ ngoại lệ của 1 tháng cho 1 bộ phận, sắp theo ngày tăng dần. */
    List<AttendanceException> findByYearAndMonthAndDepartmentOrderByDayAsc(
            Integer year, Integer month, PayrollDepartment department);

    long countByYearAndMonthAndDepartment(Integer year, Integer month, PayrollDepartment department);

    void deleteByYearAndMonthAndDepartment(Integer year, Integer month, PayrollDepartment department);

    // ── Giữ lại cho các chỗ gọi cũ / báo cáo tổng hợp toàn tháng ───────────────
    List<AttendanceException> findByYearAndMonthOrderByDayAsc(Integer year, Integer month);
}