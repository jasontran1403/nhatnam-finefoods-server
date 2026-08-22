package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryMaterialSubCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FactoryMaterialSubCategoryRepository extends JpaRepository<FactoryMaterialSubCategory, Long> {
    List<FactoryMaterialSubCategory> findByIsActiveTrueOrderByNameAsc();
    List<FactoryMaterialSubCategory> findByCategory_IdAndIsActiveTrueOrderByNameAsc(Long categoryId);
}
