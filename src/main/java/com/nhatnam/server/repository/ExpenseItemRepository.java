package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ExpenseItem;
import com.nhatnam.server.entity.ExpenseVoucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExpenseItemRepository extends JpaRepository<ExpenseItem, Long> {
    List<ExpenseItem> findByVoucherId(Long voucherId);

    /**
     * Tất cả khoản chi thuộc phiếu chi ĐÃ DUYỆT — dùng cho trang "Phân tích danh
     * mục chi" (gom theo {@code VendorExpenseCategory}, hoặc theo itemName với các
     * phiếu cũ chưa gắn categoryId).
     * <p>Chỉ lấy phiếu APPROVED vì phiếu PENDING/REJECTED chưa được tính là chi phí.
     */
    @Query("""
        SELECT i FROM ExpenseItem i
        JOIN FETCH i.voucher v
        WHERE v.status = :status
    """)
    List<ExpenseItem> findAllByVoucherStatus(@Param("status") ExpenseVoucher.VoucherStatus status);
}