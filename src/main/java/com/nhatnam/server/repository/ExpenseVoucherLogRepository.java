package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ExpenseVoucherLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseVoucherLogRepository extends JpaRepository<ExpenseVoucherLog, Long> {

    /** Nhật ký của một phiếu chi, mới nhất trước. */
    List<ExpenseVoucherLog> findByVoucher_IdOrderByCreatedAtDesc(Long voucherId);

    /** Lần thao tác gần nhất theo loại (VD action="REOPENED"). */
    java.util.Optional<ExpenseVoucherLog> findFirstByVoucher_IdAndActionOrderByCreatedAtDesc(
            Long voucherId, String action);
}