package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ExpenseVoucher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ExpenseVoucherRepository extends JpaRepository<ExpenseVoucher, Long> {

    boolean existsByVoucherCode(String voucherCode);

    Page<ExpenseVoucher> findByCreatedByIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<ExpenseVoucher> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT v FROM ExpenseVoucher v WHERE v.createdAt BETWEEN :from AND :to ORDER BY v.createdAt DESC")
    Page<ExpenseVoucher> findByDateRange(@Param("from") Long from, @Param("to") Long to, Pageable pageable);

    // Search không kèm ngày
    @Query("""
        SELECT v FROM ExpenseVoucher v
        WHERE LOWER(v.voucherCode) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.vendorName)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string) LIKE CONCAT('%', :q, '%')
        ORDER BY v.createdAt DESC
        """)
    Page<ExpenseVoucher> searchAll(@Param("q") String q, Pageable pageable);

    // Search kèm filter ngày
    @Query("""
        SELECT v FROM ExpenseVoucher v
        WHERE v.createdAt BETWEEN :from AND :to
          AND (LOWER(v.voucherCode) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.vendorName)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string) LIKE CONCAT('%', :q, '%'))
        ORDER BY v.createdAt DESC
        """)
    Page<ExpenseVoucher> searchWithDateRange(
            @Param("q") String q,
            @Param("from") Long from,
            @Param("to") Long to,
            Pageable pageable);

    @Query("SELECT COALESCE(SUM(ei.amount), 0) FROM ExpenseVoucher ev JOIN ev.items ei " +
            "WHERE ev.status = :status AND ev.approvedAt BETWEEN :from AND :to")
    BigDecimal sumApprovedExpenses(@Param("from") long from, @Param("to") long to,
                                   @Param("status") ExpenseVoucher.VoucherStatus status);

    @Query("SELECT ev FROM ExpenseVoucher ev WHERE ev.status = :status " +
            "AND ev.approvedAt BETWEEN :from AND :to ORDER BY ev.approvedAt DESC")
    List<ExpenseVoucher> findApprovedBetween(@Param("from") long from, @Param("to") long to,
                                             @Param("status") ExpenseVoucher.VoucherStatus status);

    @Query("SELECT COALESCE(SUM(ei.amount), 0) FROM ExpenseVoucher ev JOIN ev.items ei " +
            "WHERE ev.status = :status AND ev.createdAt BETWEEN :from AND :to")
    BigDecimal sumApprovedInPeriod(@Param("from") Long from, @Param("to") Long to,
                                   @Param("status") ExpenseVoucher.VoucherStatus status);
}