package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialRequestCostEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialRequestCostEntryRepository extends JpaRepository<MaterialRequestCostEntry, Long> {
    List<MaterialRequestCostEntry> findByMaterialRequestIdOrderBySortOrderAsc(Long materialRequestId);
}
