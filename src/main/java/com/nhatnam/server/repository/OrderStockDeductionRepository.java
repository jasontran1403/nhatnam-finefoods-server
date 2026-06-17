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
}
