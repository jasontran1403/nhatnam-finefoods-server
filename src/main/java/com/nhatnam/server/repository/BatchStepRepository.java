package com.nhatnam.server.repository;

import com.nhatnam.server.entity.BatchStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface BatchStepRepository extends JpaRepository<BatchStep, Long> {
    List<BatchStep> findByBatch_IdOrderByStepSequenceAsc(Long batchId);
    Optional<BatchStep> findByBatch_IdAndStepSequence(Long batchId, int stepSequence);

    /** Tìm bước đang chạy (IN_PROGRESS) trên 1 máy — dùng để kiểm tra máy có đang busy không trước khi bắt đầu bước mới */
    List<BatchStep> findByMachine_IdAndStatus(Long machineId, BatchStep.StepStatus status);

    /** Các bước có gán máy và đã từng bắt đầu (startedAt != null) trong khoảng thời gian — dùng để vẽ Gantt máy */
    @Query("SELECT s FROM BatchStep s " +
            "LEFT JOIN FETCH s.batch b " +
            "LEFT JOIN FETCH b.workOrder " +
            "WHERE s.machine.id IS NOT NULL AND s.startedAt IS NOT NULL " +
            "AND s.startedAt < :toMs AND (s.completedAt IS NULL OR s.completedAt > :fromMs)")
    List<BatchStep> findMachineOccupancyInRange(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    /** Toàn bộ các bước đã từng chạy trên 1 máy cụ thể (đã bắt đầu) — dùng để tính tổng giờ hoạt động & vẽ chart timeline của máy */
    @Query("SELECT s FROM BatchStep s " +
            "LEFT JOIN FETCH s.batch b " +
            "LEFT JOIN FETCH b.workOrder " +
            "WHERE s.machine.id = :machineId AND s.startedAt IS NOT NULL " +
            "ORDER BY s.startedAt ASC")
    List<BatchStep> findAllByMachineIdOrderByStartedAt(@Param("machineId") Long machineId);
}