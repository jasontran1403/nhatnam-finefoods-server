// src/main/java/com/nhatnam/server/repository/OrderLogRepository.java
package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OrderLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderLogRepository extends JpaRepository<OrderLog, Long> {
    List<OrderLog> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}