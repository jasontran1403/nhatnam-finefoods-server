package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryKpiBonusItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactoryKpiBonusItemRepository extends JpaRepository<FactoryKpiBonusItem, Long> {

    List<FactoryKpiBonusItem> findByKpiBonus_Id(Long kpiBonusId);

    void deleteByKpiBonus_Id(Long kpiBonusId);

    /** Dòng thưởng KPI của 1 nhân viên trong 1 tháng. */
    @Query("SELECT i FROM FactoryKpiBonusItem i " +
           "WHERE i.user.id = :userId AND i.kpiBonus.month = :month AND i.kpiBonus.year = :year")
    Optional<FactoryKpiBonusItem> findByUserAndPeriod(@Param("userId") Long userId,
                                                       @Param("month") Integer month,
                                                       @Param("year") Integer year);
}
