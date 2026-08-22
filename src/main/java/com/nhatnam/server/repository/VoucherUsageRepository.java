package com.nhatnam.server.repository;

import com.nhatnam.server.entity.VoucherUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VoucherUsageRepository extends JpaRepository<VoucherUsage, Long> {

    /** Lịch sử tiêu của một voucher — voucher này đã dùng cho những đơn nào. */
    List<VoucherUsage> findByVoucher_IdOrderByCreatedAtDesc(Long voucherId);

    /** Các voucher đã trừ vào một đơn — hiển thị ở chi tiết đơn hàng. */
    List<VoucherUsage> findByOrder_IdOrderByCreatedAtAsc(Long orderId);
}
