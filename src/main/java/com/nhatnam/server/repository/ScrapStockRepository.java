package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ScrapStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScrapStockRepository extends JpaRepository<ScrapStock, Long> {

    List<ScrapStock> findByBatch_Id(Long batchId);

    /** Tổng kg scrap theo sản phẩm — cho báo cáo/dashboard */
    @Query("SELECT COALESCE(SUM(s.quantity), 0) FROM ScrapStock s WHERE s.productName = :productName")
    java.math.BigDecimal sumQuantityByProductName(@Param("productName") String productName);

    List<ScrapStock> findAllByOrderByCreatedAtDesc(org.springframework.data.domain.Pageable pageable);
}
