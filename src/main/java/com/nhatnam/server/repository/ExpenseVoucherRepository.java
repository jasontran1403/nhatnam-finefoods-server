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

    /**
     * Số phiếu chi của phiếu được tạo GẦN NHẤT — dùng để gợi ý số kế tiếp
     * (số này + 1, quay vòng về 1 khi đạt 15000).
     */
    @Query("SELECT v.paymentNumber FROM ExpenseVoucher v WHERE v.paymentNumber IS NOT NULL AND v.paymentNumber != '' ORDER BY v.createdAt DESC, v.id DESC LIMIT 1")
    String findLatestPaymentNumber();

    /**
     * Đã tồn tại phiếu chi mang SỐ PHIẾU này trong CÙNG NGÀY chưa?
     * (Số phiếu quay vòng tới 15000 nên có thể trùng ở các ngày khác nhau — chỉ chặn trùng trong cùng ngày.)
     * Khoảng [dayStart, dayEnd] là mốc đầu/cuối ngày theo giờ VN.
     */
    @Query("""
           SELECT COUNT(v) > 0 FROM ExpenseVoucher v
           WHERE v.paymentNumber = :paymentNumber
             AND v.expenseDate BETWEEN :dayStart AND :dayEnd
           """)
    boolean existsByPaymentNumberOnDay(@Param("paymentNumber") String paymentNumber,
                                       @Param("dayStart") Long dayStart,
                                       @Param("dayEnd") Long dayEnd);

    /** Đã tồn tại phiếu chi mang SỐ PHIẾU này trong CÙNG KỲ (yyyy-MM) chưa? */
    @Query("""
           SELECT COUNT(v) > 0 FROM ExpenseVoucher v
           WHERE v.paymentNumber = :paymentNumber
             AND v.expensePeriod = :period
             AND v.expenseDate IS NULL
           """)
    boolean existsByPaymentNumberInPeriod(@Param("paymentNumber") String paymentNumber,
                                          @Param("period") String period);

    /**
     * Dữ liệu tối thiểu để TÍNH LẠI cấp duyệt cho các phiếu theo trạng thái:
     * id, danh mục và tổng tiền các khoản chi — gộp bằng {@code SUM} ngay trong SQL.
     */
    @Query("""
           SELECT v.id AS id,
                  v.vendorType AS vendorType,
                  v.approverScope AS approverScope,
                  COALESCE(SUM(i.amount), 0) AS totalAmount
           FROM ExpenseVoucher v
           LEFT JOIN v.items i
           WHERE v.status = :status
           GROUP BY v.id, v.vendorType, v.approverScope
           """)
    List<ScopeRecalcRow> findScopeRecalcRows(@Param("status") ExpenseVoucher.VoucherStatus status);

    /** Projection cho {@link #findScopeRecalcRows}. */
    interface ScopeRecalcRow {
        Long getId();
        String getVendorType();
        ExpenseVoucher.ApproverScope getApproverScope();
        BigDecimal getTotalAmount();
    }

    /**
     * Gán cấp duyệt mới cho một loạt phiếu bằng MỘT câu UPDATE.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE ExpenseVoucher v
           SET v.approverScope = :scope, v.updatedAt = :now
           WHERE v.id IN :ids
           """)
    int updateApproverScopeByIds(@Param("ids") List<Long> ids,
                                 @Param("scope") ExpenseVoucher.ApproverScope scope,
                                 @Param("now") Long now);

    /** Các phiếu chi chưa có mốc tổng hợp effectiveAt (dữ liệu cũ) — để backfill 1 lần. */
    List<ExpenseVoucher> findByEffectiveAtIsNull();

    Page<ExpenseVoucher> findByCreatedByIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<ExpenseVoucher> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // ==================== QUERY THEO NGÀY TẠO (createdAt) - GIỮ NGUYÊN ====================

    @Query("SELECT v FROM ExpenseVoucher v WHERE v.createdAt BETWEEN :from AND :to ORDER BY v.createdAt DESC")
    Page<ExpenseVoucher> findByDateRange(@Param("from") Long from, @Param("to") Long to, Pageable pageable);

    // ==================== QUERY THEO NGÀY CHI / KỲ CHI ====================

    /**
     * Tìm phiếu chi theo khoảng thời gian chi (expenseDate) - có phân trang
     * - Phiếu có expenseDate: so sánh trực tiếp với from/to
     * - Phiếu có expenseDate = null và expensePeriod: chuyển thành ngày đầu tháng
     *   và so sánh với from/to
     */
    @Query("SELECT v FROM ExpenseVoucher v WHERE " +
            "((v.expenseDate IS NOT NULL AND v.expenseDate BETWEEN :from AND :to) " +
            "OR (v.expenseDate IS NULL AND v.expensePeriod IS NOT NULL AND " +
            "   STR_TO_DATE(CONCAT(v.expensePeriod, '-01'), '%Y-%m-%d') BETWEEN :from AND :to)) " +
            "ORDER BY v.expenseDate DESC NULLS LAST, v.createdAt DESC")
    Page<ExpenseVoucher> findByExpenseDateRange(@Param("from") Long from, @Param("to") Long to, Pageable pageable);

    /**
     * Tìm phiếu chi theo khoảng thời gian chi (expenseDate) - không phân trang
     */
    @Query("SELECT v FROM ExpenseVoucher v WHERE " +
            "((v.expenseDate IS NOT NULL AND v.expenseDate BETWEEN :from AND :to) " +
            "OR (v.expenseDate IS NULL AND v.expensePeriod IS NOT NULL AND " +
            "   STR_TO_DATE(CONCAT(v.expensePeriod, '-01'), '%Y-%m-%d') BETWEEN :from AND :to)) " +
            "ORDER BY v.expenseDate DESC NULLS LAST, v.createdAt DESC")
    List<ExpenseVoucher> findByExpenseDateRangeList(@Param("from") Long from, @Param("to") Long to);

    /**
     * Tìm kiếm phiếu chi theo từ khóa và khoảng thời gian chi - có phân trang
     */
    @Query("SELECT DISTINCT v FROM ExpenseVoucher v " +
            "LEFT JOIN v.items i " +
            "WHERE " +
            "(LOWER(v.voucherCode) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(v.paymentNumber) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(v.reason) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(v.vendorName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(i.itemName) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND ((v.expenseDate IS NOT NULL AND v.expenseDate BETWEEN :from AND :to) " +
            "OR (v.expenseDate IS NULL AND v.expensePeriod IS NOT NULL AND " +
            "   STR_TO_DATE(CONCAT(v.expensePeriod, '-01'), '%Y-%m-%d') BETWEEN :from AND :to)) " +
            "ORDER BY v.expenseDate DESC NULLS LAST, v.createdAt DESC")
    Page<ExpenseVoucher> searchByExpenseDateRange(@Param("q") String q,
                                                  @Param("from") Long from,
                                                  @Param("to") Long to,
                                                  Pageable pageable);

    /**
     * Tìm kiếm phiếu chi theo từ khóa và khoảng thời gian chi - không phân trang
     */
    @Query("SELECT DISTINCT v FROM ExpenseVoucher v " +
            "LEFT JOIN v.items i " +
            "WHERE " +
            "(LOWER(v.voucherCode) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(v.paymentNumber) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(v.reason) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(v.vendorName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(i.itemName) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND ((v.expenseDate IS NOT NULL AND v.expenseDate BETWEEN :from AND :to) " +
            "OR (v.expenseDate IS NULL AND v.expensePeriod IS NOT NULL AND " +
            "   STR_TO_DATE(CONCAT(v.expensePeriod, '-01'), '%Y-%m-%d') BETWEEN :from AND :to)) " +
            "ORDER BY v.expenseDate DESC NULLS LAST, v.createdAt DESC")
    List<ExpenseVoucher> searchByExpenseDateRangeList(@Param("q") String q,
                                                      @Param("from") Long from,
                                                      @Param("to") Long to);

    /**
     * Tìm kiếm phiếu chi theo từ khóa - không có lọc ngày
     */
    @Query("SELECT DISTINCT v FROM ExpenseVoucher v " +
            "LEFT JOIN v.items i " +
            "WHERE " +
            "LOWER(v.voucherCode) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(v.paymentNumber) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(v.reason) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(v.vendorName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(i.itemName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "ORDER BY v.expenseDate DESC NULLS LAST, v.createdAt DESC")
    List<ExpenseVoucher> searchAllByExpenseDate(@Param("q") String q);

    /**
     * Tìm kiếm phiếu chi theo số tiền tổng và khoảng thời gian chi
     */
    @Query("SELECT v FROM ExpenseVoucher v " +
            "WHERE (SELECT COALESCE(SUM(i.amount), 0) FROM ExpenseItem i WHERE i.voucher = v) = :amount " +
            "AND ((v.expenseDate IS NOT NULL AND v.expenseDate BETWEEN :from AND :to) " +
            "OR (v.expenseDate IS NULL AND v.expensePeriod IS NOT NULL AND " +
            "   STR_TO_DATE(CONCAT(v.expensePeriod, '-01'), '%Y-%m-%d') BETWEEN :from AND :to)) " +
            "ORDER BY v.expenseDate DESC NULLS LAST, v.createdAt DESC")
    List<ExpenseVoucher> searchByAmountAndExpenseDateRange(@Param("amount") BigDecimal amount,
                                                           @Param("from") Long from,
                                                           @Param("to") Long to);

    /**
     * Tìm kiếm phiếu chi theo số tiền tổng - không có lọc ngày
     */
    @Query("SELECT v FROM ExpenseVoucher v " +
            "WHERE (SELECT COALESCE(SUM(i.amount), 0) FROM ExpenseItem i WHERE i.voucher = v) = :amount " +
            "ORDER BY v.expenseDate DESC NULLS LAST, v.createdAt DESC")
    List<ExpenseVoucher> searchAllByAmount(@Param("amount") BigDecimal amount);

    // ==================== SEARCH TỔNG HỢP (GIỮ NGUYÊN) ====================

    // Search không kèm ngày
    @Query("""
        SELECT DISTINCT v FROM ExpenseVoucher v
        WHERE LOWER(v.voucherCode)   LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.paymentNumber) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)        LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.vendorName)    LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)   LIKE CONCAT('%', :q, '%')
           OR (:amountExact IS NOT NULL AND
               (SELECT COALESCE(SUM(ei.amount), 0) FROM ExpenseItem ei WHERE ei.voucher = v) = :amountExact)
        ORDER BY v.createdAt DESC
        """)
    Page<ExpenseVoucher> searchAll(@Param("q") String q,
                                   @Param("amountExact") BigDecimal amountExact,
                                   Pageable pageable);

    // Search kèm filter ngày (theo createdAt)
    @Query("""
        SELECT DISTINCT v FROM ExpenseVoucher v
        WHERE COALESCE(v.effectiveAt, v.createdAt) BETWEEN :from AND :to
          AND (LOWER(v.voucherCode)   LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.paymentNumber) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)        LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.vendorName)    LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)   LIKE CONCAT('%', :q, '%')
           OR (:amountExact IS NOT NULL AND
               (SELECT COALESCE(SUM(ei.amount), 0) FROM ExpenseItem ei WHERE ei.voucher = v) = :amountExact))
        ORDER BY v.createdAt DESC
        """)
    Page<ExpenseVoucher> searchWithDateRange(
            @Param("q") String q,
            @Param("amountExact") BigDecimal amountExact,
            @Param("from") Long from,
            @Param("to") Long to,
            Pageable pageable);

    // ==================== QUERY THỐNG KÊ (GIỮ NGUYÊN) ====================

    @Query("SELECT COALESCE(SUM(ei.amount), 0) FROM ExpenseVoucher ev JOIN ev.items ei " +
            "WHERE ev.status = :status AND ev.expensePeriod IN :periods")
    BigDecimal sumApprovedByExpensePeriods(@Param("periods") java.util.Collection<String> periods,
                                           @Param("status") ExpenseVoucher.VoucherStatus status);

    @Query("SELECT COALESCE(SUM(ei.amount), 0) FROM ExpenseVoucher ev JOIN ev.items ei " +
            "WHERE ev.status = :status AND ev.approvedAt BETWEEN :from AND :to")
    BigDecimal sumApprovedExpenses(@Param("from") long from, @Param("to") long to,
                                   @Param("status") ExpenseVoucher.VoucherStatus status);

    @Query("SELECT ev FROM ExpenseVoucher ev WHERE ev.status = :status " +
            "AND ev.approvedAt BETWEEN :from AND :to ORDER BY ev.approvedAt DESC")
    List<ExpenseVoucher> findApprovedBetween(@Param("from") long from, @Param("to") long to,
                                             @Param("status") ExpenseVoucher.VoucherStatus status);

    @Query("SELECT COALESCE(SUM(ei.amount), 0) FROM ExpenseVoucher ev JOIN ev.items ei " +
            "WHERE ev.status = :status " +
            "AND COALESCE(ev.effectiveAt, ev.createdAt) BETWEEN :from AND :to")
    BigDecimal sumApprovedInPeriod(@Param("from") Long from, @Param("to") Long to,
                                   @Param("status") ExpenseVoucher.VoucherStatus status);

    @Query("SELECT v FROM ExpenseVoucher v WHERE v.status = :status " +
            "AND COALESCE(v.effectiveAt, v.createdAt) BETWEEN :from AND :to ORDER BY COALESCE(v.effectiveAt, v.createdAt) ASC")
    List<ExpenseVoucher> findApprovedByEffectiveBetween(@Param("status") ExpenseVoucher.VoucherStatus status,
                                                        @Param("from") Long from, @Param("to") Long to);

    /**
     * Tổng tiền ứng lương đã duyệt cho 1 nhân viên trong 1 tháng.
     * Dùng để kiểm tra hạn mức ứng (tối đa = lương cơ bản) và hiển thị
     * trên phiếu lương cuối tháng.
     *
     * @param userId  ID nhân viên
     * @param month   "YYYY-MM"
     * @param status  thường là APPROVED
     */
    @Query("""
        SELECT COALESCE(SUM(i.amount), 0)
        FROM ExpenseVoucher v
        JOIN v.items i
        WHERE v.salaryAdvanceUserId = :userId
          AND v.salaryAdvanceMonth = :month
          AND v.status = :status
        """)
    BigDecimal sumSalaryAdvance(@Param("userId") Long userId,
                                @Param("month") String month,
                                @Param("status") ExpenseVoucher.VoucherStatus status);

    /**
     * Tất cả phiếu ứng lương (đã duyệt + chờ duyệt) cho 1 nhân viên trong 1 tháng.
     * Dùng để hiển thị lịch sử ứng lương trên phiếu lương.
     */
    @Query("""
        SELECT v FROM ExpenseVoucher v
        WHERE v.salaryAdvanceUserId = :userId
          AND v.salaryAdvanceMonth = :month
        ORDER BY v.createdAt DESC
        """)
    List<ExpenseVoucher> findSalaryAdvancesByUserAndMonth(@Param("userId") Long userId,
                                                          @Param("month") String month);
}