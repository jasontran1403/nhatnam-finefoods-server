package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MisaReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MisaReceiptRepository extends JpaRepository<MisaReceipt, Long> {
    List<MisaReceipt> findByOrderIdOrderByCreatedAtDesc(Long orderId);
    long countByOrderId(Long orderId);
}