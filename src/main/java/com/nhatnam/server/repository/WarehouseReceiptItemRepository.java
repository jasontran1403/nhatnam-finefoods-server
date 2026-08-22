package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WarehouseReceiptItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarehouseReceiptItemRepository extends JpaRepository<WarehouseReceiptItem, Long> {

    /**
     * Tổng chênh lệch (difference) theo (ingredientId, warehouseId) cho các phiếu
     * có createdAt >= :from. Dùng để suy ra ĐẦU KỲ = tồn hiện tại − tổng phát sinh từ :from.
     * Trả về [ingredientId(Long), warehouseId(Long), sumDiff(BigDecimal)].
     */
    @Query("SELECT i.ingredientId, r.warehouse.id, SUM(i.difference) " +
           "FROM WarehouseReceiptItem i JOIN i.receipt r " +
           "WHERE r.createdAt >= :from " +
           "GROUP BY i.ingredientId, r.warehouse.id")
    List<Object[]> sumDiffSince(@Param("from") long from);

    /**
     * Tổng chênh lệch theo (ingredientId, warehouseId) cho các phiếu có createdAt > :after.
     * Dùng để suy ra CUỐI KỲ (khi mốc cuối < hiện tại) = tồn hiện tại − tổng phát sinh sau :after.
     */
    @Query("SELECT i.ingredientId, r.warehouse.id, SUM(i.difference) " +
           "FROM WarehouseReceiptItem i JOIN i.receipt r " +
           "WHERE r.createdAt > :after " +
           "GROUP BY i.ingredientId, r.warehouse.id")
    List<Object[]> sumDiffAfter(@Param("after") long after);

    /**
     * Phát sinh trong kỳ [from,to], tách phần DƯƠNG và ÂM theo
     * (ingredientId, warehouseId, receiptType) — để phân loại nhập/bán/xuất.
     * Trả về [ingredientId, warehouseId, receiptType, posSum, negSum].
     */
    @Query("SELECT i.ingredientId, r.warehouse.id, r.receiptType, " +
           "SUM(CASE WHEN i.difference > 0 THEN i.difference ELSE 0 END), " +
           "SUM(CASE WHEN i.difference < 0 THEN i.difference ELSE 0 END) " +
           "FROM WarehouseReceiptItem i JOIN i.receipt r " +
           "WHERE r.createdAt BETWEEN :from AND :to " +
           "GROUP BY i.ingredientId, r.warehouse.id, r.receiptType")
    List<Object[]> periodBreakdown(@Param("from") long from, @Param("to") long to);
}
