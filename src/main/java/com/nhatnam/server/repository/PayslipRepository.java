package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Payslip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    List<Payslip> findByBatchIdOrderByUserFullNameAsc(Long batchId);

    @Query("SELECT p FROM Payslip p WHERE p.batch.id = :batchId AND p.user.id = :userId")
    Optional<Payslip> findByBatchIdAndUserId(@Param("batchId") Long batchId, @Param("userId") Long userId);

    void deleteByBatchId(Long batchId);

    long countByBatchId(Long batchId);

    @Query("SELECT p FROM Payslip p WHERE p.user.id = :userId ORDER BY p.batch.year DESC, p.batch.month DESC")
    List<Payslip> findByUserIdOrderByLatest(@Param("userId") Long userId);
}
