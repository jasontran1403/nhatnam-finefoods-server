package com.nhatnam.server.repository;

import com.nhatnam.server.entity.EmployeeSeniority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeSeniorityRepository extends JpaRepository<EmployeeSeniority, Long> {

    /** Bản chốt thâm niên của 1 nhân viên trong 1 kỳ lương. */
    @Query("""
            SELECT s FROM EmployeeSeniority s
            WHERE s.user.id = :userId AND s.month = :month AND s.year = :year
            """)
    Optional<EmployeeSeniority> findByUserAndPeriod(@Param("userId") Long userId,
                                                    @Param("month") Integer month,
                                                    @Param("year") Integer year);

    /** Toàn bộ bản chốt của 1 kỳ — dùng khi cần nạp một lượt cho cả bộ phận. */
    List<EmployeeSeniority> findByMonthAndYear(Integer month, Integer year);

    /**
     * Nạp một lượt cho danh sách nhân viên của kỳ.
     *
     * <p>Bảng lương của bộ phận 40 người mà hỏi lẻ từng người là 40 lượt truy vấn
     * chỉ để lấy một con số — gom về 1 query.
     */
    @Query("""
            SELECT s FROM EmployeeSeniority s
            WHERE s.month = :month AND s.year = :year AND s.user.id IN :userIds
            """)
    List<EmployeeSeniority> findByPeriodAndUserIds(@Param("month") Integer month,
                                                   @Param("year") Integer year,
                                                   @Param("userIds") List<Long> userIds);
}
