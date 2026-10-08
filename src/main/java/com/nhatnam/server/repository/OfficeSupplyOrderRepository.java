package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OfficeSupplyOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OfficeSupplyOrderRepository extends JpaRepository<OfficeSupplyOrder, Long> {

    /** Lịch sử đặt hàng của 1 văn phòng — mới nhất trước. */
    List<OfficeSupplyOrder> findAllByWarehouseIdOrderByPlacedAtDesc(Long warehouseId);

    /** Đơn hàng gần nhất của 1 văn phòng. */
    Optional<OfficeSupplyOrder> findFirstByWarehouseIdOrderByPlacedAtDesc(Long warehouseId);

    /** Load kèm items để tránh N+1. */
    @Query("""
        SELECT o FROM OfficeSupplyOrder o
        LEFT JOIN FETCH o.items i
        LEFT JOIN FETCH i.supplyItem
        WHERE o.id = :id
        """)
    Optional<OfficeSupplyOrder> findByIdWithItems(@Param("id") Long id);

    /** Tất cả đơn hàng của 1 văn phòng kèm items — dùng cho trang báo cáo. */
    @Query("""
        SELECT DISTINCT o FROM OfficeSupplyOrder o
        LEFT JOIN FETCH o.items i
        LEFT JOIN FETCH i.supplyItem
        WHERE o.warehouse.id = :warehouseId
        ORDER BY o.placedAt DESC
        """)
    List<OfficeSupplyOrder> findAllWithItemsByWarehouseId(@Param("warehouseId") Long warehouseId);

    /**
     * Thống kê theo từng SupplyItem: số lần mua và ngày mua gần nhất.
     * Dùng cho bảng báo cáo chính.
     */
    @Query("""
        SELECT i.supplyItem.id,
               COUNT(DISTINCT o.id),
               MAX(o.placedAt),
               SUM(i.quantity)
        FROM OfficeSupplyOrderItem i
        JOIN i.order o
        WHERE o.warehouse.id = :warehouseId
        GROUP BY i.supplyItem.id
        """)
    List<Object[]> statsByItemAndWarehouse(@Param("warehouseId") Long warehouseId);
}
