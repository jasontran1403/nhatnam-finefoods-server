package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OrderCodePrefix;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderCodePrefixRepository extends JpaRepository<OrderCodePrefix, Long> {

    // Lấy prefix đang active (năm hiện tại)
    Optional<OrderCodePrefix> findByIsActiveTrue();

    // Lấy tất cả prefix đã từng dùng (để tránh trùng)
    List<OrderCodePrefix> findAll();
}