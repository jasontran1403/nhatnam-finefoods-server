package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductionRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ProductionRecipeRepository extends JpaRepository<ProductionRecipe, Long> {

    List<ProductionRecipe> findByFactoryProduct_IdAndIsActiveTrueOrderByCreatedAtDesc(Long productId);

    List<ProductionRecipe> findByIsActiveTrueOrderByCreatedAtDesc();

    @Query("SELECT r FROM ProductionRecipe r LEFT JOIN FETCH r.items i LEFT JOIN FETCH i.factoryMaterial WHERE r.id = :id")
    java.util.Optional<ProductionRecipe> findByIdWithItems(@Param("id") Long id);
}
