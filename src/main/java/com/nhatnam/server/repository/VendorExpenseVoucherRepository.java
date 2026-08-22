package com.nhatnam.server.repository;

import com.nhatnam.server.entity.VendorExpenseVoucher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VendorExpenseVoucherRepository extends JpaRepository<VendorExpenseVoucher, Long> {

    long countByVoucherCodeStartingWith(String prefix);

    Page<VendorExpenseVoucher> findByVendor_IdOrderByCreatedAtDesc(Long vendorId, Pageable pageable);

    @Query("""
        SELECT v FROM VendorExpenseVoucher v
        WHERE (:vendorId IS NULL OR v.vendor.id = :vendorId)
          AND (:search IS NULL OR :search = ''
               OR LOWER(v.voucherCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(v.vendorName) LIKE LOWER(CONCAT('%',:search,'%')))
        ORDER BY v.createdAt DESC
    """)
    Page<VendorExpenseVoucher> findByFilters(@Param("vendorId") Long vendorId,
                                              @Param("search") String search,
                                              Pageable pageable);

    /**
     * Dùng để gộp vào trang "Phiếu chi" chung (ExpenseVoucher) — lấy tất cả phiếu
     * chi trả công nợ NCC trong một khoảng ngày (hoặc toàn bộ nếu from/to = null),
     * không phân trang (gộp + sort + phân trang lại ở service layer cùng với ExpenseVoucher).
     */
    @Query("""
        SELECT v FROM VendorExpenseVoucher v
        WHERE (:from IS NULL OR v.createdAt >= :from)
          AND (:to IS NULL OR v.createdAt <= :to)
        ORDER BY v.createdAt DESC
        """)
    List<VendorExpenseVoucher> findByDateRangeNoPaging(@Param("from") Long from, @Param("to") Long to);

    @Query("""
        SELECT v FROM VendorExpenseVoucher v
        WHERE LOWER(v.voucherCode) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.vendorName)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(COALESCE(v.note, '')) LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string) LIKE CONCAT('%', :q, '%')
        ORDER BY v.createdAt DESC
        """)
    List<VendorExpenseVoucher> searchAllNoPaging(@Param("q") String q);

    @Query("""
        SELECT v FROM VendorExpenseVoucher v
        WHERE v.createdAt BETWEEN :from AND :to
          AND (LOWER(v.voucherCode) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.vendorName)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(COALESCE(v.note, '')) LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string) LIKE CONCAT('%', :q, '%'))
        ORDER BY v.createdAt DESC
        """)
    List<VendorExpenseVoucher> searchWithDateRangeNoPaging(
            @Param("q") String q,
            @Param("from") Long from,
            @Param("to") Long to);
}
