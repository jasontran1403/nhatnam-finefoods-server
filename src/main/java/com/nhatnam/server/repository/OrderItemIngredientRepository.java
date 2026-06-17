package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OrderItemIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderItemIngredientRepository extends JpaRepository<OrderItemIngredient, Long> {

    @Modifying
    @Query("DELETE FROM OrderItemIngredient oii WHERE oii.orderItem.order.id = :orderId")
    void deleteByOrderId(@Param("orderId") Long orderId);
}