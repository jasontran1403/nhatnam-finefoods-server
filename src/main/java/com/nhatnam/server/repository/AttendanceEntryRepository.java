package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AttendanceEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceEntryRepository extends JpaRepository<AttendanceEntry, Long> {

    /** Dòng chấm công của 1 nhân viên trong 1 bảng. */
    Optional<AttendanceEntry> findBySheet_IdAndUser_Id(Long sheetId, Long userId);

    List<AttendanceEntry> findBySheet_Id(Long sheetId);

    void deleteBySheet_Id(Long sheetId);

    /**
     * Chấm công của 1 nhân viên trong 1 tháng, MỚI NHẤT TRƯỚC.
     *
     * <p>Trả về LIST thay vì Optional để không vỡ khi nhân viên từng được import
     * ở 2 BỘ PHẬN khác nhau cho cùng một tháng — xảy ra khi OWNER đổi role nhận
     * lương của một người rồi import lại tháng cũ ở bộ phận mới.
     */
    @Query("SELECT e FROM AttendanceEntry e " +
            "WHERE e.user.id = :userId AND e.sheet.month = :month AND e.sheet.year = :year " +
            "ORDER BY e.sheet.updatedAt DESC, e.id DESC")
    List<AttendanceEntry> findAllByUserAndPeriod(@Param("userId") Long userId,
                                                 @Param("month") Integer month,
                                                 @Param("year") Integer year);

    /** Bản ghi chấm công MỚI NHẤT của nhân viên trong tháng. */
    default Optional<AttendanceEntry> findByUserAndPeriod(Long userId, Integer month, Integer year) {
        return findAllByUserAndPeriod(userId, month, year).stream().findFirst();
    }

    /**
     * MÃ MÁY CHẤM CÔNG đã ghi nhận cho nhân viên ở các lần import trước,
     * mới nhất trước. Dùng để khớp nhân viên với file nhanh và chắc chắn hơn
     * so với khớp theo họ tên (tên trong file thường viết tắt / sai chính tả).
     */
    @Query("SELECT e.employeeCode FROM AttendanceEntry e " +
            "WHERE e.user.id = :userId AND e.employeeCode IS NOT NULL " +
            "ORDER BY e.sheet.year DESC, e.sheet.month DESC")
    List<String> findLatestEmployeeCode(@Param("userId") Long userId);
}