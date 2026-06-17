package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WorkOrderPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface WorkOrderPlanRepository extends JpaRepository<WorkOrderPlan, Long> {

    Optional<WorkOrderPlan> findByWorkOrder_Id(Long workOrderId);

    @Query("SELECT p FROM WorkOrderPlan p LEFT JOIN FETCH p.materials WHERE p.workOrder.id = :workOrderId")
    Optional<WorkOrderPlan> findByWorkOrderIdWithMaterials(@Param("workOrderId") Long workOrderId);
}
