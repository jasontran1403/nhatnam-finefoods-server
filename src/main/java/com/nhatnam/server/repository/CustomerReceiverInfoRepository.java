package com.nhatnam.server.repository;

import com.nhatnam.server.entity.CustomerReceiverInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerReceiverInfoRepository extends JpaRepository<CustomerReceiverInfo, Long> {
    List<CustomerReceiverInfo> findByCustomerId(Long customerId);
    boolean existsByReceiverPhone(String receiverPhone);
}