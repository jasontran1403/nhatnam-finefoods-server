package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AttendanceSheet;
import com.nhatnam.server.enumtype.PayrollCalcStatus;
import com.nhatnam.server.enumtype.PayrollDepartment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceSheetRepository extends JpaRepository<AttendanceSheet, Long> {
    boolean existsByMonthAndYearAndFinalizedTrue(int month, int year);

    // ── Theo BỘ PHẬN (API cũ — Phase 2 giữ cho các màn chưa migrate xong) ─────

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

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 2 — API MỚI: BẢNG CHẤM CÔNG CẢ CÔNG TY (SINGLE SHEET)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Bộ phận đóng vai trò "canonical sheet" cho cả công ty.
     *
     * <p>Hệ thống cũ tạo 1 AttendanceSheet riêng cho mỗi phòng ban. Phase 2
     * dùng chung 1 sheet / tháng cho toàn công ty — vẫn tận dụng entity cũ
     * bằng cách lưu dưới cờ bộ phận FACTORY. Lựa chọn FACTORY (không phải
     * ACCOUNTING hay DRIVER) là vì các field cấu hình bổ sung ở AttendanceSheet
     * (driverGasPrice, driverBonusUnitPrice…) không ép buộc giá trị default; để
     * ở FACTORY thì các field driver vẫn null và không gây tác dụng phụ.
     */
    PayrollDepartment COMPANY_SENTINEL = PayrollDepartment.FACTORY;

    /** Lấy sheet cả công ty của 1 tháng — có thể trống. */
    default Optional<AttendanceSheet> findCompanySheet(int month, int year) {
        return findByMonthAndYearAndDepartment(month, year, COMPANY_SENTINEL);
    }

    default boolean existsCompanySheet(int month, int year) {
        return existsByMonthAndYearAndDepartment(month, year, COMPANY_SENTINEL);
    }

    /** TRUE nếu lương tháng đã được PUBLISH cho nhân viên xem. */
    default boolean isPublished(int month, int year) {
        return findCompanySheet(month, year)
                .map(s -> s.getCalcStatus() == PayrollCalcStatus.PUBLISHED)
                .orElse(false);
    }
}
