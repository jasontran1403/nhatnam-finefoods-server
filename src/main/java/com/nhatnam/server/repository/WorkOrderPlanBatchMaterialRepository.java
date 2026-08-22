package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WorkOrderPlanBatchMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WorkOrderPlanBatchMaterialRepository extends JpaRepository<WorkOrderPlanBatchMaterial, Long> {

    List<WorkOrderPlanBatchMaterial> findByPlan_IdOrderByBatchNumberAscSortOrderAsc(Long planId);

    List<WorkOrderPlanBatchMaterial> findByPlan_IdAndBatchNumberOrderBySortOrderAsc(Long planId, Integer batchNumber);

    void deleteByPlan_Id(Long planId);
}
