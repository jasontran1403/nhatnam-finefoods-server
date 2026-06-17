// FILE 2: IngredientWarehouseRepository.java
// THAY ĐỔI: sửa JPQL vì IngredientWarehouse không còn @ManyToOne ingredient
package com.nhatnam.server.repository;

import com.nhatnam.server.entity.IngredientWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface IngredientWarehouseRepository extends JpaRepository<IngredientWarehouse, Long> {

    // Sửa: dùng iw.ingredientId thay vì iw.ingredient.id
    @Modifying
    @Transactional
    @Query("DELETE FROM IngredientWarehouse iw WHERE iw.ingredientId = :ingredientId")
    void deleteByIngredientId(@Param("ingredientId") Long ingredientId);

    List<IngredientWarehouse> findByWarehouseId(Long warehouseId);

    // Dùng plain column — Spring Data tự build query
    List<IngredientWarehouse> findByIngredientId(Long ingredientId);

    Optional<IngredientWarehouse> findByIngredientIdAndWarehouseId(Long ingredientId, Long warehouseId);

    boolean existsByIngredientIdAndWarehouseId(Long ingredientId, Long warehouseId);

    void deleteByIngredientIdAndWarehouseId(Long ingredientId, Long warehouseId);

    // Sửa: dùng iw.ingredientId (plain column)
    @Query("SELECT iw.ingredientId FROM IngredientWarehouse iw WHERE iw.warehouse.id = :warehouseId")
    List<Long> findIngredientIdsByWarehouseId(@Param("warehouseId") Long warehouseId);

    @Query("SELECT iw.warehouse.id FROM IngredientWarehouse iw WHERE iw.ingredientId = :ingredientId")
    List<Long> findWarehouseIdsByIngredientId(@Param("ingredientId") Long ingredientId);
}
