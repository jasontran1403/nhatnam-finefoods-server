package com.nhatnam.server.repository;

import com.nhatnam.server.entity.CustomerContactLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerContactLogRepository extends JpaRepository<CustomerContactLog, Long> {

    Optional<CustomerContactLog> findByCustomerIdAndSellerIdAndContactDate(
            Long customerId, Long sellerId, String contactDate);

    /** Toàn bộ lần đánh dấu của seller trong 1 ngày — dùng để dựng màn hình dự báo. */
    List<CustomerContactLog> findBySellerIdAndContactDate(Long sellerId, String contactDate);

    /** Lịch sử liên hệ gần đây của 1 khách (mọi seller). */
    List<CustomerContactLog> findTop20ByCustomerIdOrderByContactedAtDesc(Long customerId);
}
