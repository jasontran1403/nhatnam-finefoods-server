package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Voucher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    boolean existsByCode(String code);

    /**
     * Tra voucher theo mã, không phân biệt hoa thường.
     *
     * <p>Mã nhập tay từ phiếu giấy hay bị gõ thường; mã quét từ QR thì luôn đúng dạng.
     * Dùng {@code IgnoreCase} để hai đường vào cùng tìm ra một voucher.
     */
    java.util.Optional<Voucher> findByCodeIgnoreCase(String code);

    /**
     * Tra voucher và KHOÁ BẢN GHI cho tới hết transaction (SELECT … FOR UPDATE).
     *
     * <p>Chống dùng trùng: hai seller cùng quét một voucher cho hai đơn khác nhau, cả hai
     * đọc thấy số dư 1.000.000 rồi cùng trừ — voucher bị tiêu hai lần. Khoá bi quan buộc
     * request thứ hai chờ request đầu commit hoặc rollback, lúc đó nó đọc được số dư
     * THẬT và tự bị chặn nếu không còn đủ.
     *
     * <p>Chọn khoá bi quan thay vì {@code @Version} lạc quan vì ở đây thất bại phải là
     * "chờ rồi xử lý đúng", không phải "ném lỗi bắt người dùng bấm lại" — nhân viên đang
     * đứng trước mặt khách.
     *
     * <p>Khoá được nhả ngay khi transaction kết thúc, kể cả khi thất bại, nên voucher
     * không bao giờ bị kẹt.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT v FROM Voucher v WHERE UPPER(v.code) = UPPER(:code)")
    java.util.Optional<Voucher> findByCodeForUpdate(@Param("code") String code);

    List<Voucher> findByCustomer_IdOrderByCreatedAtDesc(Long customerId);

    /**
     * Tìm kiếm cho màn hình quản lý voucher.
     *
     * <p>{@code status} lọc theo cột status LƯU TRONG DB, không phải trạng thái hiệu lực
     * tính động — voucher hết hạn vẫn mang status = ACTIVE ở DB. Việc lọc theo trạng thái
     * hiệu lực (EXPIRED/USED) làm ở tầng service sau khi đã nạp dữ liệu, vì nó phụ thuộc
     * thời điểm hiện tại nên không viết được thành điều kiện SQL ổn định.
     */
    @Query("SELECT v FROM Voucher v WHERE " +
            "(:q IS NULL OR LOWER(v.code) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "  OR LOWER(v.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "  OR LOWER(v.customer.name) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "  OR LOWER(v.customer.companyName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "  OR LOWER(v.customer.phone) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:customerId IS NULL OR v.customer.id = :customerId) " +
            "AND (:reason IS NULL OR v.reason = :reason) " +
            "AND (:status IS NULL OR v.status = :status)")
    Page<Voucher> search(@Param("q") String q,
                         @Param("customerId") Long customerId,
                         @Param("reason") Voucher.VoucherReason reason,
                         @Param("status") Voucher.VoucherStatus status,
                         Pageable pageable);
}