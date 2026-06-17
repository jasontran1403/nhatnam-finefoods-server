package com.nhatnam.server.repository;

import com.nhatnam.server.entity.IncomeVoucher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncomeVoucherRepository extends JpaRepository<IncomeVoucher, Long> {
    boolean existsByReceiptNumber(String receiptNumber);

    @Query("SELECT v FROM IncomeVoucher v WHERE v.linkedOrderCodes IS NOT NULL AND v.linkedOrderCodes != ''")
    List<IncomeVoucher> findAllWithLinkedOrders();

    @Query("SELECT v.voucherCode FROM IncomeVoucher v WHERE v.voucherCode LIKE CONCAT(:prefix, '%') ORDER BY v.voucherCode DESC LIMIT 1")
    String findTopVoucherCodeByDatePrefix(@Param("prefix") String prefix);

    boolean existsByVoucherCode(String voucherCode);

    Page<IncomeVoucher> findByCreatedByIdOrderByCreatedAtDesc(Long createdById, Pageable pageable);

    Page<IncomeVoucher> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT v FROM IncomeVoucher v WHERE v.createdAt BETWEEN :from AND :to ORDER BY v.createdAt DESC")
    Page<IncomeVoucher> findByDateRange(@Param("from") Long from, @Param("to") Long to, Pageable pageable);

    @Query("SELECT v FROM IncomeVoucher v LEFT JOIN FETCH v.items WHERE v.createdAt BETWEEN :from AND :to ORDER BY v.createdAt ASC")
    List<IncomeVoucher> findByDateRangeAll(@Param("from") Long from, @Param("to") Long to);

    // Search không kèm ngày
    @Query("""
        SELECT v FROM IncomeVoucher v
        WHERE LOWER(v.voucherCode)    LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.receiptNumber)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)         LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.payerName)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)    LIKE CONCAT('%', :q, '%')
        ORDER BY v.createdAt DESC
        """)
    Page<IncomeVoucher> searchAll(@Param("q") String q, Pageable pageable);

    // Search kèm filter ngày
    @Query("""
        SELECT v FROM IncomeVoucher v
        WHERE v.createdAt BETWEEN :from AND :to
          AND (LOWER(v.voucherCode)   LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.receiptNumber)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)         LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.payerName)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)    LIKE CONCAT('%', :q, '%'))
        ORDER BY v.createdAt DESC
        """)
    Page<IncomeVoucher> searchWithDateRange(
            @Param("q") String q,
            @Param("from") Long from,
            @Param("to") Long to,
            Pageable pageable);
}