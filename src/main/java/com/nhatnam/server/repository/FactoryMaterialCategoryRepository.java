package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryMaterialCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FactoryMaterialCategoryRepository extends JpaRepository<FactoryMaterialCategory, Long> {
    List<FactoryMaterialCategory> findByIsActiveTrueOrderByNameAsc();
    Optional<FactoryMaterialCategory> findByNameIgnoreCase(String name);
}
