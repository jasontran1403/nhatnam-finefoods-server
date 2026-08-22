package com.nhatnam.server.repository;

import com.nhatnam.server.entity.IncomeVoucherLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncomeVoucherLogRepository extends JpaRepository<IncomeVoucherLog, Long> {

    /** Nhật ký của một phiếu, mới nhất trước. */
    List<IncomeVoucherLog> findByVoucher_IdOrderByCreatedAtDesc(Long voucherId);

    /** Lần thao tác gần nhất theo loại (VD action="UPDATE") — để hiện "sửa gần nhất". */
    java.util.Optional<IncomeVoucherLog> findFirstByVoucher_IdAndActionOrderByCreatedAtDesc(
            Long voucherId, String action);
}
