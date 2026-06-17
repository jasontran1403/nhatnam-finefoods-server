// ── ProductionPlanRepository.java ─────────────────────────────────────────────
package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductionPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProductionPlanRepository extends JpaRepository<ProductionPlan, Long> {

    Page<ProductionPlan> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ProductionPlan> findByStatusOrderByCreatedAtDesc(ProductionPlan.PlanStatus status, Pageable pageable);

    @Query("SELECT p FROM ProductionPlan p JOIN FETCH p.factoryProduct " +
           "WHERE p.id = :id")
    Optional<ProductionPlan> findByIdWithProduct(@Param("id") Long id);

    @Query("SELECT p FROM ProductionPlan p WHERE p.startDate >= :fromMs AND p.startDate < :toMs " +
           "ORDER BY p.startDate ASC")
    List<ProductionPlan> findByDateRange(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    @Query("SELECT p FROM ProductionPlan p WHERE " +
           "(p.startDate >= :fromMs AND p.startDate < :toMs) " +
           "OR (p.endDate >= :fromMs AND p.endDate < :toMs) " +
           "ORDER BY p.startDate ASC")
    List<ProductionPlan> findByDateRangeExtended(@Param("fromMs") long fromMs,
                                                  @Param("toMs") long toMs);

    @Query("SELECT COUNT(p) FROM ProductionPlan p WHERE p.planCode LIKE :prefix%")
    long countByPlanCodePrefix(@Param("prefix") String prefix);
}
