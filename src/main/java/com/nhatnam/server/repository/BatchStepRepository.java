package com.nhatnam.server.repository;

import com.nhatnam.server.entity.BatchStep;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BatchStepRepository extends JpaRepository<BatchStep, Long> {
    List<BatchStep> findByBatch_IdOrderByStepSequenceAsc(Long batchId);
    Optional<BatchStep> findByBatch_IdAndStepSequence(Long batchId, int stepSequence);
}
