package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WorkOrderStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface WorkOrderStepRepository extends JpaRepository<WorkOrderStep, Long> {

    List<WorkOrderStep> findByWorkOrder_IdOrderByStageSequenceAscRunNumberAsc(Long workOrderId);

    List<WorkOrderStep> findByWorkOrder_IdAndStageSequenceOrderByRunNumberAsc(Long workOrderId, Integer stageSequence);

    /** Bước đang chạy trên 1 máy — kiểm tra máy busy trước khi bắt đầu lần chạy mới */
    List<WorkOrderStep> findByMachine_IdAndStatus(Long machineId, WorkOrderStep.Status status);

    /** Occupancy máy trong khoảng thời gian — vẽ Gantt máy */
    @Query("SELECT s FROM WorkOrderStep s " +
            "LEFT JOIN FETCH s.workOrder " +
            "WHERE s.machine.id IS NOT NULL AND s.startedAt IS NOT NULL " +
            "AND s.startedAt < :toMs AND (s.completedAt IS NULL OR s.completedAt > :fromMs)")
    List<WorkOrderStep> findMachineOccupancyInRange(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    /** Toàn bộ lần chạy đã bắt đầu trên 1 máy — tính tổng giờ hoạt động & timeline */
    @Query("SELECT s FROM WorkOrderStep s " +
            "LEFT JOIN FETCH s.workOrder " +
            "WHERE s.machine.id = :machineId AND s.startedAt IS NOT NULL " +
            "ORDER BY s.startedAt ASC")
    List<WorkOrderStep> findAllByMachineIdOrderByStartedAt(@Param("machineId") Long machineId);
}
