package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OfficeSupplyRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OfficeSupplyRequestRepository extends JpaRepository<OfficeSupplyRequest, Long> {

    /** Phiếu đang pending của user tại 1 văn phòng cụ thể. */
    Optional<OfficeSupplyRequest> findByUserIdAndWarehouseId(Long userId, Long warehouseId);

    /** Toàn bộ phiếu pending tại 1 văn phòng (OWNER tổng hợp đặt hàng). */
    @Query("""
        SELECT r FROM OfficeSupplyRequest r
        LEFT JOIN FETCH r.items i
        LEFT JOIN FETCH i.supplyItem
        LEFT JOIN FETCH r.user
        WHERE r.warehouse.id = :warehouseId
        ORDER BY r.user.fullName ASC
        """)
    List<OfficeSupplyRequest> findAllByWarehouseIdWithItems(@Param("warehouseId") Long warehouseId);

    /** Phiếu của 1 user — dùng cho trang "đơn của tôi". */
    @Query("""
        SELECT r FROM OfficeSupplyRequest r
        LEFT JOIN FETCH r.items i
        LEFT JOIN FETCH i.supplyItem
        WHERE r.user.id = :userId
        ORDER BY r.warehouse.name ASC
        """)
    List<OfficeSupplyRequest> findAllByUserIdWithItems(@Param("userId") Long userId);
}
