package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SupplyStock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface SupplyStockRepository extends JpaRepository<SupplyStock, Long> {

    Optional<SupplyStock> findByWarehouseIdAndSupplyItemId(Long warehouseId, Long supplyItemId);

    /**
     * Khoá bi quan khi CỘNG/TRỪ tồn — tránh 2 người rút cùng lúc làm âm kho.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SupplyStock> findWithLockByWarehouseIdAndSupplyItemId(Long warehouseId, Long supplyItemId);

    List<SupplyStock> findByWarehouseId(Long warehouseId);

    List<SupplyStock> findBySupplyItemId(Long supplyItemId);
}
