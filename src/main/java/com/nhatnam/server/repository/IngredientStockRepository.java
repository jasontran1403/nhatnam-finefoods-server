// FILE 4: IngredientStockRepository.java
// THAY ĐỔI: sửa JPQL vì IngredientStock không còn @ManyToOne ingredient
package com.nhatnam.server.repository;

import com.nhatnam.server.entity.IngredientStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface IngredientStockRepository extends JpaRepository<IngredientStock, Long> {

    // Sửa: JOIN ProductIngredient qua ingredientId (plain column)
    @Query("""
    SELECT s FROM IngredientStock s
    JOIN ProductIngredient pi ON pi.ingredientId = s.ingredientId
    WHERE s.warehouse.id = :warehouseId
    AND pi.productId IN :productIds
""")
    List<IngredientStock> findByWarehouseIdAndProductIds(
            @Param("warehouseId") Long warehouseId,
            @Param("productIds") List<Long> productIds);

    @Query("""
    SELECT s FROM IngredientStock s
    WHERE s.warehouse.id = :warehouseId
    AND s.ingredientId IN :ingredientIds
""")
    List<IngredientStock> findByWarehouseIdAndIngredientIds(
            @Param("warehouseId") Long warehouseId,
            @Param("ingredientIds") List<Long> ingredientIds);

    @Modifying
    @Query("""
    UPDATE IngredientStock s
    SET s.stockQuantity = s.stockQuantity - :qty
    WHERE s.ingredientId = :ingredientId
    AND s.warehouse.id = :warehouseId
    AND s.stockQuantity >= :qty
""")
    int decreaseStockAtomic(@Param("ingredientId") Long ingredientId,
                            @Param("warehouseId") Long warehouseId,
                            @Param("qty") BigDecimal qty);

    // q dùng để filter theo tên — nếu null/blank thì lấy tất cả
    @Query("SELECT s FROM IngredientStock s " +
            "WHERE s.warehouse.id = :warehouseId " +
            "AND (:q IS NULL OR LOWER(s.ingredientNameSnapshot) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "ORDER BY s.ingredientNameSnapshot ASC")
    List<IngredientStock> findByWarehouse(@Param("warehouseId") Long warehouseId,
                                          @Param("q") String q);

    Optional<IngredientStock> findByIngredientIdAndWarehouseId(Long ingredientId, Long warehouseId);

    List<IngredientStock> findByWarehouseId(Long warehouseId);

    List<IngredientStock> findByIngredientId(Long ingredientId);

    // Sửa: không còn JOIN FETCH s.ingredient vì không có @ManyToOne
    // Lọc theo isActive phải join Ingredient entity riêng nếu cần
    // Đơn giản: trả tất cả stock của kho đó, service tự filter
    @Query("""
        SELECT s FROM IngredientStock s
        WHERE s.warehouse.id = :warehouseId
    """)
    List<IngredientStock> findByWarehouseIdWithIngredient(@Param("warehouseId") Long warehouseId);
}