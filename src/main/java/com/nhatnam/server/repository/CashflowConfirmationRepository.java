package com.nhatnam.server.repository;

import com.nhatnam.server.entity.CashflowConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CashflowConfirmationRepository extends JpaRepository<CashflowConfirmation, Long> {

    /** Lần chốt LỆCH (baseline) gần nhất TRƯỚC mốc t. */
    @Query("SELECT c FROM CashflowConfirmation c WHERE c.matched = false AND c.confirmedAt < :t " +
           "ORDER BY c.confirmedAt DESC LIMIT 1")
    CashflowConfirmation findBaselineBefore(@Param("t") Long t);

    /** Tất cả lần xác nhận trong [from,to] (để hiển thị marker & load log quá khứ). */
    @Query("SELECT c FROM CashflowConfirmation c WHERE c.confirmedAt BETWEEN :from AND :to " +
           "ORDER BY c.confirmedAt ASC")
    List<CashflowConfirmation> findBetween(@Param("from") Long from, @Param("to") Long to);
}
