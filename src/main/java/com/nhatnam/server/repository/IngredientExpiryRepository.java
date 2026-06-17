// FILE 3: IngredientExpiryRepository.java
// THAY ĐỔI: sửa các method nhận Ingredient object → Long ingredientId,
//           vì IngredientExpiry không còn @ManyToOne ingredient
package com.nhatnam.server.repository;

import com.nhatnam.server.entity.IngredientExpiry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IngredientExpiryRepository extends JpaRepository<IngredientExpiry, Long> {

    // Sửa: nhận Long warehouseId + Long ingredientId thay vì object
    List<IngredientExpiry> findByWarehouseIdAndIngredientIdOrderByExpiryDateAsc(
            Long warehouseId, Long ingredientId);

    Optional<IngredientExpiry> findByWarehouseIdAndIngredientIdAndExpiryDateAndCostPrice(
            Long warehouseId, Long ingredientId, LocalDate expiryDate, BigDecimal costPrice);

    List<IngredientExpiry> findByWarehouseIdAndIngredientIdOrderByCreatedAtAsc(
            Long warehouseId, Long ingredientId);

    @Query(value = """
            SELECT e.ingredient_id AS ingredientId,
                   MIN(CASE WHEN e.expiry_date IS NOT NULL AND e.quantity > 0
                            THEN e.expiry_date END) AS earliestExpiry,
                   COALESCE(SUM(CASE WHEN e.expiry_date IS NOT NULL
                                      AND e.expiry_date <= :threshold
                                      AND e.quantity > 0
                                     THEN e.quantity ELSE 0 END), 0) AS nearExpiryQty
            FROM ingredient_expiry e
            WHERE e.warehouse_id = :warehouseId
            GROUP BY e.ingredient_id
            """, nativeQuery = true)
    List<Object[]> summarizeByWarehouse(@Param("warehouseId") Long warehouseId,
                                        @Param("threshold") LocalDate threshold);

    List<IngredientExpiry> findByWarehouseIdAndIngredientIdOrderByExpiryDate(
            Long warehouseId, Long ingredientId);

    /** FIFO: lô cũ nhất, sửa dùng plain column ingredientId */
    @Query("SELECT e FROM IngredientExpiry e " +
            "WHERE e.warehouse.id = :warehouseId AND e.ingredientId = :ingredientId " +
            "AND e.quantity > 0 " +
            "ORDER BY CASE WHEN e.expiryDate IS NULL THEN 1 ELSE 0 END ASC, e.expiryDate ASC, e.id ASC")
    List<IngredientExpiry> findFifoLots(@Param("warehouseId") Long warehouseId,
                                        @Param("ingredientId") Long ingredientId);

    @Query("SELECT e FROM IngredientExpiry e " +
            "WHERE e.warehouse.id = :warehouseId " +
            "ORDER BY e.expiryDate ASC")
    List<IngredientExpiry> findByWarehouseId(@Param("warehouseId") Long warehouseId);

    @Query("SELECT e FROM IngredientExpiry e " +
            "WHERE e.warehouse.id = :warehouseId AND e.ingredientId = :ingredientId " +
            "ORDER BY e.expiryDate ASC")
    List<IngredientExpiry> findByWarehouseIdAndIngredientId(
            @Param("warehouseId") Long warehouseId,
            @Param("ingredientId") Long ingredientId);
}
