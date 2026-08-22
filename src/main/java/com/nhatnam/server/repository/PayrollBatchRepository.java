package com.nhatnam.server.repository;

import com.nhatnam.server.entity.PayrollBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PayrollBatchRepository extends JpaRepository<PayrollBatch, Long> {

    Optional<PayrollBatch> findByMonthAndYear(Integer month, Integer year);

    @Query("SELECT b FROM PayrollBatch b WHERE (:status IS NULL OR b.status = :status) ORDER BY b.year DESC, b.month DESC")
    Page<PayrollBatch> findAllFiltered(@Param("status") String status, Pageable pageable);
}
