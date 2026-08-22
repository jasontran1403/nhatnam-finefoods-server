package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SupplyWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplyWarehouseRepository extends JpaRepository<SupplyWarehouse, Long> {
    List<SupplyWarehouse> findByActiveTrueOrderBySortOrderAscIdAsc();
    Optional<SupplyWarehouse> findByName(String name);
}
