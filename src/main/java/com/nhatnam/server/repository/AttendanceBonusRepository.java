package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AttendanceBonus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttendanceBonusRepository extends JpaRepository<AttendanceBonus, Long> {
    List<AttendanceBonus> findByMonthAndYear(Integer month, Integer year);
    Optional<AttendanceBonus> findByUser_IdAndMonthAndYear(Long userId, Integer month, Integer year);

    /** Lấy row NGAY TRƯỚC đó (year-month desc), lấy đầu tiên < (month,year). */
    @org.springframework.data.jpa.repository.Query("""
            SELECT ab FROM AttendanceBonus ab
            WHERE ab.user.id = :userId
              AND (ab.year < :year OR (ab.year = :year AND ab.month < :month))
            ORDER BY ab.year DESC, ab.month DESC
            """)
    List<AttendanceBonus> findLatestBefore(@org.springframework.data.repository.query.Param("userId") Long userId,
                                           @org.springframework.data.repository.query.Param("month") Integer month,
                                           @org.springframework.data.repository.query.Param("year") Integer year);
}