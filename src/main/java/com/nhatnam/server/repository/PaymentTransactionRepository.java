package com.nhatnam.server.repository;

import com.nhatnam.server.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    List<PaymentTransaction> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}
