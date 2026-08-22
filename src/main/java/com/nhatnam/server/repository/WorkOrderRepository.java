package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WorkOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    Page<WorkOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<WorkOrder> findByStatusOrderByCreatedAtDesc(WorkOrder.WorkOrderStatus status, Pageable pageable);
    List<WorkOrder> findByProductionPlan_IdOrderByCreatedAtDesc(Long planId);

    @Query("SELECT w FROM WorkOrder w " +
            "LEFT JOIN FETCH w.factoryProduct " +
            "LEFT JOIN FETCH w.productionPlan " +
            "WHERE w.id = :id")
    Optional<WorkOrder> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT w FROM WorkOrder w " +
            "LEFT JOIN FETCH w.factoryProduct " +
            "LEFT JOIN FETCH w.workOrderPlan p " +
            "LEFT JOIN FETCH p.materials " +
            "LEFT JOIN FETCH p.recipe " +
            "WHERE w.id = :id")
    Optional<WorkOrder> findByIdWithPlan(@Param("id") Long id);

    /**
     * Lệnh active cho factory — KHÔNG lọc theo user nữa.
     * Lọc theo factoryId nếu có, lấy tất cả nếu null.
     */
    @Query("SELECT w FROM WorkOrder w WHERE " +
            "(:factoryId IS NULL OR w.productionFactory.id = :factoryId) " +
            "AND (w.status IN ('SCHEDULED','PENDING_PLAN','PLANNED','IN_PROGRESS') " +
            "  OR (w.status = 'COMPLETED' AND w.actualEndDate >= :sinceMs)) " +
            "ORDER BY w.scheduledStartDate ASC")
    List<WorkOrder> findActiveOrdersByFactory(@Param("factoryId") Long factoryId,
                                              @Param("sinceMs") long sinceMs);

    @Query("SELECT w FROM WorkOrder w WHERE " +
            "(:factoryId IS NULL OR w.productionFactory.id = :factoryId) " +
            "AND w.status IN ('SCHEDULED','PENDING_PLAN','PLANNED','IN_PROGRESS') " +
            "ORDER BY w.scheduledStartDate ASC")
    List<WorkOrder> findActiveOrdersByFactoryStrict(@Param("factoryId") Long factoryId);

    /** Tất cả lệnh active (không lọc factory) */
    @Query("SELECT w FROM WorkOrder w WHERE w.status IN " +
            "('SCHEDULED','PENDING_PLAN','PLANNED','IN_PROGRESS') " +
            "ORDER BY w.scheduledStartDate ASC")
    List<WorkOrder> findActiveOrders();

    long countByWorkOrderCodeStartingWith(String prefix);

    @Query("SELECT w FROM WorkOrder w WHERE w.scheduledStartDate >= :fromMs " +
            "AND w.scheduledStartDate < :toMs ORDER BY w.scheduledStartDate ASC")
    List<WorkOrder> findByScheduledDateRange(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    @Query("SELECT w FROM WorkOrder w WHERE " +
            "(w.scheduledStartDate >= :fromMs AND w.scheduledStartDate < :toMs) " +
            "OR (w.plannedEndDate >= :fromMs AND w.plannedEndDate < :toMs) " +
            "ORDER BY w.scheduledStartDate ASC")
    List<WorkOrder> findByDateRangeExtended(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    long countByStatus(WorkOrder.WorkOrderStatus status);

    /**
     * TỔNG SẢN LƯỢNG (kg) của các lệnh sản xuất ĐÃ HOÀN THÀNH trong khoảng thời
     * gian, tính theo NGÀY HOÀN THÀNH THỰC TẾ ({@code actualEndDate}).
     *
     * <p>Lệnh tạo 30/5, bắt đầu chiều 30/5 nhưng xong 1/6 ⇒ tính cho THÁNG 6.
     * Dùng làm căn cứ tính quỹ thưởng KPI của phòng sản xuất.
     */
    @Query("SELECT COALESCE(SUM(w.accumulatedQty), 0) FROM WorkOrder w " +
           "WHERE w.status = :status " +
           "AND w.actualEndDate >= :fromMs AND w.actualEndDate < :toMs")
    BigDecimal sumCompletedOutputBetween(@Param("status") WorkOrder.WorkOrderStatus status,
                                         @Param("fromMs") long fromMs,
                                         @Param("toMs") long toMs);

    /** Tổng sản lượng của TẤT CẢ lệnh sản xuất đã hoàn thành (card dashboard). */
    @Query("SELECT COALESCE(SUM(w.accumulatedQty), 0) FROM WorkOrder w " +
           "WHERE w.status = :status")
    BigDecimal sumAllCompletedOutput(@Param("status") WorkOrder.WorkOrderStatus status);

    /** Tiện ích: gọi {@link #sumCompletedOutputBetween} với status = COMPLETED. */
    default BigDecimal sumCompletedOutputBetween(long fromMs, long toMs) {
        return sumCompletedOutputBetween(WorkOrder.WorkOrderStatus.COMPLETED, fromMs, toMs);
    }

    /** Tiện ích: gọi {@link #sumAllCompletedOutput} với status = COMPLETED. */
    default BigDecimal sumAllCompletedOutput() {
        return sumAllCompletedOutput(WorkOrder.WorkOrderStatus.COMPLETED);
    }
}