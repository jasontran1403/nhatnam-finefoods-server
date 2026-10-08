package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OfficeBonusResult;
import com.nhatnam.server.enumtype.PayrollDepartment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OfficeBonusResultRepository extends JpaRepository<OfficeBonusResult, Long> {

    Optional<OfficeBonusResult> findByMonthAndYearAndDepartment(
            int month, int year, PayrollDepartment department);

    void deleteByMonthAndYearAndDepartment(int month, int year, PayrollDepartment department);

    /**
     * Tổng thưởng của 1 nhân viên trong 1 kỳ (join qua OfficeBonusItem).
     * Trả 0 nếu chưa tính hoặc nhân viên không có trong kết quả.
     */
    @Query("""
            SELECT COALESCE(SUM(i.bonusAmount), 0)
            FROM OfficeBonusItem i
            WHERE i.bonusResult.month  = :month
              AND i.bonusResult.year   = :year
              AND i.bonusResult.department = :dept
              AND i.user.id = :userId
            """)
    Long sumBonusForUser(@Param("month") int month,
                         @Param("year") int year,
                         @Param("dept") PayrollDepartment dept,
                         @Param("userId") Long userId);
}