package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SalesBonusOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SalesBonusOverrideRepository extends JpaRepository<SalesBonusOverride, Long> {

    List<SalesBonusOverride> findByMonthAndYear(Integer month, Integer year);

    Optional<SalesBonusOverride> findByUser_IdAndMonthAndYear(Long userId, Integer month, Integer year);

    @Modifying
    @Query("DELETE FROM SalesBonusOverride s WHERE s.month = :m AND s.year = :y")
    void deleteAllByMonthAndYear(@Param("m") Integer month, @Param("y") Integer year);

    /** Load tất cả SBO trong khoảng (year, month) ≥ cutoff — dùng cho trend 12 tháng */
    @Query("""
            SELECT s FROM SalesBonusOverride s
            WHERE (s.year > :fromYear OR (s.year = :fromYear AND s.month >= :fromMonth))
              AND s.totalBonus IS NOT NULL AND s.totalBonus > 0
            """)
    List<SalesBonusOverride> findAllSinceMonth(@Param("fromMonth") int fromMonth,
                                               @Param("fromYear") int fromYear);
}