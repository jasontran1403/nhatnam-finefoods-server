package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WorkOrderStockDeduction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WorkOrderStockDeductionRepository extends JpaRepository<WorkOrderStockDeduction, Long> {

    /** Tất cả bản ghi trừ kho của một lệnh sản xuất (kể cả đã hoàn) */
    List<WorkOrderStockDeduction> findByWorkOrder_IdOrderByCreatedAtAsc(Long workOrderId);

    /** Các bản ghi chưa hoàn kho của một lệnh sản xuất — dùng khi hủy mẻ */
    @Query("SELECT d FROM WorkOrderStockDeduction d " +
            "WHERE d.workOrder.id = :workOrderId AND d.returned = false " +
            "ORDER BY d.createdAt ASC")
    List<WorkOrderStockDeduction> findUnreturnedByWorkOrderId(@Param("workOrderId") Long workOrderId);
}