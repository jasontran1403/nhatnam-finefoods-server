package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaintenanceSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;

public interface MaintenanceScheduleRepository extends JpaRepository<MaintenanceSchedule, Long> {

    @Query("SELECT m FROM MaintenanceSchedule m JOIN FETCH m.machine " +
           "WHERE m.plannedStart >= :fromMs AND m.plannedStart < :toMs " +
           "ORDER BY m.plannedStart ASC")
    List<MaintenanceSchedule> findByYearRange(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    @Query("SELECT m FROM MaintenanceSchedule m JOIN FETCH m.machine " +
           "WHERE m.plannedStart >= :fromMs AND m.plannedStart < :toMs " +
           "AND m.machine.id = :machineId ORDER BY m.plannedStart ASC")
    List<MaintenanceSchedule> findByYearRangeAndMachine(
            @Param("fromMs") long fromMs,
            @Param("toMs") long toMs,
            @Param("machineId") Long machineId);

    @Query("SELECT m FROM MaintenanceSchedule m JOIN FETCH m.machine " +
           "WHERE m.machine.id = :machineId ORDER BY m.plannedStart ASC")
    List<MaintenanceSchedule> findByMachineId(@Param("machineId") Long machineId);

    /** Upcoming maintenance trong 30 ngày tới */
    @Query("SELECT m FROM MaintenanceSchedule m JOIN FETCH m.machine " +
           "WHERE m.plannedStart >= :now AND m.plannedStart < :future " +
           "AND m.status = 'PLANNED' ORDER BY m.plannedStart ASC")
    List<MaintenanceSchedule> findUpcoming(@Param("now") long now, @Param("future") long future);

    /** Tổng chi phí thực tế của 1 máy */
    @Query("SELECT COALESCE(SUM(m.actualCost), 0) FROM MaintenanceSchedule m WHERE m.machine.id = :machineId")
    BigDecimal sumActualCostByMachine(@Param("machineId") Long machineId);
}
