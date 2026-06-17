package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SubCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubCategoryRepository extends JpaRepository<SubCategory, Long> {

    List<SubCategory> findByCategoryIdAndIsActiveTrueOrderByNameAsc(Long categoryId);

    List<SubCategory> findByIsActiveTrueOrderByNameAsc();

    Optional<SubCategory> findByIdAndIsActiveTrue(Long id);

    boolean existsByNameIgnoreCaseAndCategoryIdAndIsActiveTrue(String name, Long categoryId);
}