package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FactoryMaterialRepository extends JpaRepository<FactoryMaterial, Long> {
    List<FactoryMaterial> findByIsActiveTrueOrderByNameAsc();
    List<FactoryMaterial> findAllByOrderByNameAsc();
}
