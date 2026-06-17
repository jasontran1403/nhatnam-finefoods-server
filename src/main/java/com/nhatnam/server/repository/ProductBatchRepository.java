package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductBatch;
import com.nhatnam.server.entity.ProductBatch.BatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductBatchRepository extends JpaRepository<ProductBatch, Long> {
    Optional<ProductBatch> findByBatchCode(String batchCode);
    boolean existsByBatchCode(String batchCode);

    @Query("SELECT b FROM ProductBatch b WHERE (:status IS NULL OR b.status = :status) ORDER BY b.createdAt DESC")
    Page<ProductBatch> findAllFiltered(@Param("status") BatchStatus status, Pageable pageable);

    List<ProductBatch> findByStatusOrderByCreatedAtDesc(BatchStatus status);
}
