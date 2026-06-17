package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryMaterialStock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FactoryMaterialStockRepository extends JpaRepository<FactoryMaterialStock, Long> {

    List<FactoryMaterialStock> findByIsActiveTrueOrderByCreatedAtAsc();

    List<FactoryMaterialStock> findByMaterialNameAndUnitAndIsActiveTrueOrderByCreatedAtAsc(
            String materialName, String unit);
}