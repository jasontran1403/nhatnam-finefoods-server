package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OrderStockDeduction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderStockDeductionRepository
        extends JpaRepository<OrderStockDeduction, Long> {

    List<OrderStockDeduction> findByOrderId(Long orderId);

    @Modifying
    @Query("DELETE FROM OrderStockDeduction d WHERE d.orderId = :orderId")
    void deleteByOrderId(@Param("orderId") Long orderId);

    /**
     * BUG FIX 2.6: query các deduction đang ở trạng thái PENDING (đơn PREPARING
     * hoặc PENDING_PAYMENT) cho 1 ingredient tại 1 warehouse.
     *
     * <p>Dùng trong WarehouseService.adjustStock để chặn kiểm kê khi có đơn
     * đang giữ hàng — tránh tình trạng cancel đơn sau adjust gây dư ảo.
     */
    @Query("SELECT d FROM OrderStockDeduction d " +
            "JOIN com.nhatnam.server.entity.Order o ON o.id = d.orderId " +
            "WHERE d.ingredientStock.ingredientId = :ingredientId " +
            "  AND d.ingredientStock.warehouse.id = :warehouseId " +
            "  AND o.status IN ('PREPARING', 'PENDING_PAYMENT')")
    List<OrderStockDeduction> findPendingByIngredientAndWarehouse(
            @Param("ingredientId") Long ingredientId,
            @Param("warehouseId") Long warehouseId);
}