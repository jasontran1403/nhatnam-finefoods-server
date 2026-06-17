package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WarehouseReceipt;
import com.nhatnam.server.entity.WarehouseReceipt.CostStatus;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarehouseReceiptRepository extends JpaRepository<WarehouseReceipt, Long> {

    @Query("SELECT r FROM WarehouseReceipt r " +
            "JOIN FETCH r.warehouse " +
            "JOIN FETCH r.createdBy " +
            "WHERE r.receiptType IN :types " +
            "ORDER BY r.createdAt DESC")
    Page<WarehouseReceipt> findByTypes(@Param("types") List<ReceiptType> types, Pageable pageable);

    @Query("SELECT r FROM WarehouseReceipt r " +
            "JOIN FETCH r.warehouse " +
            "WHERE r.warehouse.id = :warehouseId AND r.receiptType IN :types " +
            "ORDER BY r.createdAt DESC")
    Page<WarehouseReceipt> findByWarehouseIdAndTypes(
            @Param("warehouseId") Long warehouseId,
            @Param("types") List<ReceiptType> types,
            Pageable pageable);

    boolean existsByReceiptCode(String receiptCode);

    // Feature 2: Lấy phiếu nhập kho chờ kế toán nhập giá vốn
    List<WarehouseReceipt> findByReceiptTypeAndCostStatusOrderByCreatedAtDesc(
            ReceiptType receiptType, CostStatus costStatus);
}
