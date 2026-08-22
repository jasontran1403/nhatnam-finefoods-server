package com.nhatnam.server.repository;

import com.nhatnam.server.entity.UserSupplyWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserSupplyWarehouseRepository extends JpaRepository<UserSupplyWarehouse, Long> {
    List<UserSupplyWarehouse> findByUserId(Long userId);
    List<UserSupplyWarehouse> findByWarehouseId(Long warehouseId);
    boolean existsByUserIdAndWarehouseId(Long userId, Long warehouseId);
    void deleteByUserIdAndWarehouseId(Long userId, Long warehouseId);
    void deleteByUserId(Long userId);
}
