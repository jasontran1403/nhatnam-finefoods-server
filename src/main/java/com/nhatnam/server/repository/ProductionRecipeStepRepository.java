package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductionRecipeStep;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductionRecipeStepRepository extends JpaRepository<ProductionRecipeStep, Long> {

    List<ProductionRecipeStep> findByRecipe_IdOrderBySortOrderAsc(Long recipeId);

    void deleteByRecipe_Id(Long recipeId);
}
