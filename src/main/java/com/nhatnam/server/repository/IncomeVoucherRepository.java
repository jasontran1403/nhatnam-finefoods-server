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

    @Query("SELECT v.receiptNumber FROM IncomeVoucher v WHERE v.receiptNumber IS NOT NULL AND v.receiptNumber != ''")
    List<String> findAllReceiptNumbers();

    /**
     * Số phiếu thu của phiếu được tạo GẦN NHẤT (mới nhất theo thời gian tạo). Dùng
     * để gợi ý số kế tiếp = số này + 1 (quay vòng về 1 khi đạt 15000), thay vì lấy
     * số lớn nhất mọi thời điểm — nhờ vậy sau khi người dùng nhập lại số 1 thì phiếu
     * kế tiếp gợi ý là 2.
     */
    @Query("SELECT v.receiptNumber FROM IncomeVoucher v WHERE v.receiptNumber IS NOT NULL AND v.receiptNumber != '' ORDER BY v.createdAt DESC, v.id DESC LIMIT 1")
    String findLatestReceiptNumber();

    @Query("SELECT v FROM IncomeVoucher v WHERE v.linkedOrderCodes IS NOT NULL AND v.linkedOrderCodes != ''")
    List<IncomeVoucher> findAllWithLinkedOrders();

    /**
     * Tìm các phiếu thu có liên kết tới 1 mã đơn hàng cụ thể (LIKE trên JSON text
     * linkedOrderCodes, ví dụ '["ORD-001","ORD-002"]'). Dùng cho việc hiển thị/
     * tìm kiếm số phiếu thu trong trang quản lý đơn hàng.
     */
    @Query("SELECT v FROM IncomeVoucher v WHERE v.linkedOrderCodes LIKE CONCAT('%\"', :orderCode, '\"%')")
    List<IncomeVoucher> findByLinkedOrderCode(@Param("orderCode") String orderCode);

    /**
     * Tìm các phiếu thu có receiptNumber khớp (chứa) 1 chuỗi tìm kiếm — dùng để
     * tìm các đơn hàng có cùng phiếu thu (search theo receiptNumber rồi suy ra
     * danh sách orderCode liên kết).
     */
    @Query("SELECT v FROM IncomeVoucher v WHERE LOWER(v.receiptNumber) LIKE LOWER(CONCAT('%', :receiptNumber, '%')) " +
           "AND v.linkedOrderCodes IS NOT NULL AND v.linkedOrderCodes != ''")
    List<IncomeVoucher> findByReceiptNumberContainingWithLinkedOrders(@Param("receiptNumber") String receiptNumber);

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
        SELECT DISTINCT v FROM IncomeVoucher v
        WHERE LOWER(v.voucherCode)    LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.receiptNumber)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)         LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.payerName)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)    LIKE CONCAT('%', :q, '%')
           OR (:amountExact IS NOT NULL AND
               (SELECT COALESCE(SUM(it.amount), 0) FROM IncomeItem it WHERE it.voucher = v) = :amountExact)
        ORDER BY v.createdAt DESC
        """)
    Page<IncomeVoucher> searchAll(@Param("q") String q, @Param("amountExact") java.math.BigDecimal amountExact, Pageable pageable);

    // Search kèm filter ngày
    @Query("""
        SELECT DISTINCT v FROM IncomeVoucher v
        WHERE v.createdAt BETWEEN :from AND :to
          AND (LOWER(v.voucherCode)   LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.receiptNumber)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)         LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.payerName)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)    LIKE CONCAT('%', :q, '%')
           OR (:amountExact IS NOT NULL AND
               (SELECT COALESCE(SUM(it.amount), 0) FROM IncomeItem it WHERE it.voucher = v) = :amountExact))
        ORDER BY v.createdAt DESC
        """)
    Page<IncomeVoucher> searchWithDateRange(
            @Param("q") String q,
            @Param("amountExact") java.math.BigDecimal amountExact,
            @Param("from") Long from,
            @Param("to") Long to,
            Pageable pageable);

    // ════════════════════════════════════════════════════════════════════════
    // TỔNG TIỀN THEO ĐÚNG BỘ LỌC ĐANG ÁP DỤNG (không phụ thuộc phân trang)
    //
    // Trước đây FE cộng totalAmount của các phiếu TRÊN TRANG HIỆN TẠI → số tổng
    // sai (chỉ đúng khi kết quả vừa đúng 1 trang). Các query dưới đây SUM trên
    // TOÀN BỘ kết quả khớp bộ lọc, dùng ĐÚNG điều kiện WHERE với các query phân trang
    // ở trên để hai con số luôn khớp nhau.
    // ════════════════════════════════════════════════════════════════════════

    // LƯU Ý: IncomeVoucher KHÔNG có cột totalAmount — tổng tiền của 1 phiếu là
    // tổng amount của các IncomeItem con. Vì vậy phải JOIN sang items để SUM.

    /** Tổng tiền TẤT CẢ phiếu thu */
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM IncomeVoucher v JOIN v.items i")
    java.math.BigDecimal sumAll();

    /** Tổng tiền phiếu thu trong khoảng ngày */
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM IncomeVoucher v JOIN v.items i " +
           "WHERE v.createdAt BETWEEN :from AND :to")
    java.math.BigDecimal sumByDateRange(@Param("from") Long from, @Param("to") Long to);

    /** Tổng tiền theo từ khoá tìm kiếm (không lọc ngày) */
    @Query("""
        SELECT COALESCE(SUM(i.amount), 0) FROM IncomeVoucher v JOIN v.items i
        WHERE LOWER(v.voucherCode)    LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.receiptNumber)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)         LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.payerName)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)    LIKE CONCAT('%', :q, '%')
           OR (:amountExact IS NOT NULL AND
               (SELECT COALESCE(SUM(it.amount), 0) FROM IncomeItem it WHERE it.voucher = v) = :amountExact)
        """)
    java.math.BigDecimal sumSearchAll(@Param("q") String q, @Param("amountExact") java.math.BigDecimal amountExact);

    /** Tổng tiền theo từ khoá + khoảng ngày */
    @Query("""
        SELECT COALESCE(SUM(i.amount), 0) FROM IncomeVoucher v JOIN v.items i
        WHERE v.createdAt BETWEEN :from AND :to
          AND (LOWER(v.voucherCode)   LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.receiptNumber)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)         LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.payerName)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)    LIKE CONCAT('%', :q, '%')
           OR (:amountExact IS NOT NULL AND
               (SELECT COALESCE(SUM(it.amount), 0) FROM IncomeItem it WHERE it.voucher = v) = :amountExact))
        """)
    java.math.BigDecimal sumSearchWithDateRange(@Param("q") String q,
                                                @Param("amountExact") java.math.BigDecimal amountExact,
                                                @Param("from") Long from,
                                                @Param("to") Long to);

    /** Đếm tương ứng — dùng chung với các SUM ở trên */
    @Query("SELECT COUNT(v) FROM IncomeVoucher v")
    long countAllVouchers();

    @Query("SELECT COUNT(v) FROM IncomeVoucher v WHERE v.createdAt BETWEEN :from AND :to")
    long countByDateRange(@Param("from") Long from, @Param("to") Long to);

    @Query("""
        SELECT COUNT(DISTINCT v) FROM IncomeVoucher v
        WHERE LOWER(v.voucherCode)    LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.receiptNumber)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)         LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.payerName)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)    LIKE CONCAT('%', :q, '%')
           OR (:amountExact IS NOT NULL AND
               (SELECT COALESCE(SUM(it.amount), 0) FROM IncomeItem it WHERE it.voucher = v) = :amountExact)
        """)
    long countSearchAll(@Param("q") String q, @Param("amountExact") java.math.BigDecimal amountExact);

    @Query("""
        SELECT COUNT(DISTINCT v) FROM IncomeVoucher v
        WHERE v.createdAt BETWEEN :from AND :to
          AND (LOWER(v.voucherCode)   LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.receiptNumber)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.reason)         LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(v.payerName)      LIKE LOWER(CONCAT('%', :q, '%'))
           OR CAST(v.id AS string)    LIKE CONCAT('%', :q, '%')
           OR (:amountExact IS NOT NULL AND
               (SELECT COALESCE(SUM(it.amount), 0) FROM IncomeItem it WHERE it.voucher = v) = :amountExact))
        """)
    long countSearchWithDateRange(@Param("q") String q,
                                  @Param("amountExact") java.math.BigDecimal amountExact,
                                  @Param("from") Long from,
                                  @Param("to") Long to);

    /** Phiếu thu KHÔNG bị từ chối trong [from,to] theo createdAt — dùng cho dòng tiền. */
    @Query("SELECT v FROM IncomeVoucher v WHERE v.status <> :excludeStatus " +
           "AND v.createdAt BETWEEN :from AND :to ORDER BY v.createdAt ASC")
    java.util.List<IncomeVoucher> findCountedBetween(@org.springframework.data.repository.query.Param("excludeStatus") IncomeVoucher.VoucherStatus excludeStatus,
                                                     @org.springframework.data.repository.query.Param("from") Long from,
                                                     @org.springframework.data.repository.query.Param("to") Long to);
}
