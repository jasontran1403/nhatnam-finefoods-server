package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
    List<Warehouse> findByActiveTrue();
    List<Warehouse> findByTypeAndActiveTrue(Warehouse.WarehouseType type);

    boolean existsByName(String name);

    List<Warehouse> findAllByOrderByIdAsc();

    List<Warehouse> findByActiveTrueOrderByIdAsc();
}