package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AttendanceSheet;
import com.nhatnam.server.enumtype.PayrollDepartment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceSheetRepository extends JpaRepository<AttendanceSheet, Long> {

    // ── Theo BỘ PHẬN (dùng chính từ khi tách bộ phận) ─────────────────────────

    /** Bảng chấm công của 1 tháng cho 1 bộ phận (mỗi tháng × bộ phận tối đa 1 bảng). */
    Optional<AttendanceSheet> findByMonthAndYearAndDepartment(
            Integer month, Integer year, PayrollDepartment department);

    boolean existsByMonthAndYearAndDepartment(
            Integer month, Integer year, PayrollDepartment department);

    /** Đã HOÀN TẤT xử lý lương cho tháng + bộ phận này chưa. */
    boolean existsByMonthAndYearAndDepartmentAndFinalizedTrue(
            Integer month, Integer year, PayrollDepartment department);

    /** Tất cả bảng của 1 tháng (mọi bộ phận). */
    List<AttendanceSheet> findByMonthAndYear(Integer month, Integer year);

    /** Tất cả bảng của 1 bộ phận, mới nhất trước. */
    List<AttendanceSheet> findByDepartmentOrderByYearDescMonthDesc(PayrollDepartment department);

    /** Danh sách bảng chấm công mới nhất trước — cho trang quản lý của OWNER/HR. */
    List<AttendanceSheet> findAllByOrderByYearDescMonthDesc();
}