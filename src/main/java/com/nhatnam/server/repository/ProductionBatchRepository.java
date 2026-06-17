package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductionBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ProductionBatchRepository extends JpaRepository<ProductionBatch, Long> {

    Page<ProductionBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ProductionBatch> findByCreatedBy_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<ProductionBatch> findByWorkOrder_IdOrderByBatchNumberAsc(Long workOrderId);

    // Tách thành 2 query riêng để tránh MultipleBagFetchException
    // Query 1: fetch items
    @Query("SELECT b FROM ProductionBatch b " +
            "LEFT JOIN FETCH b.items i " +
            "LEFT JOIN FETCH i.factoryMaterial " +
            "WHERE b.id = :id")
    Optional<ProductionBatch> findByIdWithItems(@Param("id") Long id);

    // Query 2: fetch steps
    @Query("SELECT b FROM ProductionBatch b " +
            "LEFT JOIN FETCH b.steps " +
            "WHERE b.id = :id")
    Optional<ProductionBatch> findByIdWithSteps(@Param("id") Long id);

    // findByIdWithDetails: dùng findByIdWithSteps rồi load items lazily,
    // hoặc gọi cả 2 query từ service. Giữ lại để không break code cũ — chỉ fetch steps.
    @Query("SELECT b FROM ProductionBatch b " +
            "LEFT JOIN FETCH b.steps " +
            "WHERE b.id = :id")
    Optional<ProductionBatch> findByIdWithDetails(@Param("id") Long id);

    /** Tổng sản lượng đã hoàn thành của 1 lệnh */
    @Query("SELECT COALESCE(SUM(b.actualOutputQty), 0) FROM ProductionBatch b " +
            "WHERE b.workOrder.id = :workOrderId AND b.status = 'COMPLETED'")
    BigDecimal sumCompletedQtyByWorkOrder(@Param("workOrderId") Long workOrderId);

    /** Đếm mẻ theo trạng thái trong 1 lệnh */
    long countByWorkOrder_IdAndStatus(Long workOrderId, ProductionBatch.BatchStatus status);

    /** Số mẻ tiếp theo trong lệnh */
    @Query("SELECT COALESCE(MAX(b.batchNumber), 0) + 1 FROM ProductionBatch b WHERE b.workOrder.id = :workOrderId")
    int nextBatchNumber(@Param("workOrderId") Long workOrderId);

    @Query("SELECT COUNT(b) FROM ProductionBatch b WHERE b.createdAt >= :from AND b.createdAt <= :to")
    long countByPeriod(@Param("from") Long from, @Param("to") Long to);

    List<ProductionBatch> findByProducedAtBetweenOrderByProducedAtDesc(Long from, Long to);

    @Query("SELECT COUNT(b) FROM ProductionBatch b WHERE b.batchCode LIKE :prefix%")
    long countByBatchCodePrefix(@Param("prefix") String prefix);
}