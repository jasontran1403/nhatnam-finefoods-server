package com.nhatnam.server.repository;

import com.nhatnam.server.entity.BatchStepTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BatchStepTemplateRepository extends JpaRepository<BatchStepTemplate, Long> {
    List<BatchStepTemplate> findByIsActiveTrueOrderBySortOrderAscNameAsc();
    Optional<BatchStepTemplate> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
}