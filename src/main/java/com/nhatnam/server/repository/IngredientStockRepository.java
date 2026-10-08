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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
    UPDATE IngredientStock s
    SET s.stockQuantity = :newQty,
        s.updatedAt = :now
    WHERE s.ingredientId = :ingredientId
    AND s.warehouse.id = :warehouseId
""")
    int setStockQuantity(@Param("ingredientId") Long ingredientId,
                         @Param("warehouseId") Long warehouseId,
                         @Param("newQty") BigDecimal newQty,
                         @Param("now") long now);

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

    // ══════════════════════════════════════════════════════════════════
    //  ATOMIC MUTATIONS — race-safe layer
    // ══════════════════════════════════════════════════════════════════

    /**
     * Trừ tồn kho ATOMIC với điều kiện đủ hàng. MySQL thực hiện WHERE + UPDATE
     * trong 1 statement, giữ row lock. Trả:
     * - 1 nếu stockQuantity >= qty (đã trừ thành công)
     * - 0 nếu KHÔNG đủ (hoặc row không tồn tại)
     */
    @Modifying(flushAutomatically = true)
    @Query("""
    UPDATE IngredientStock s
    SET s.stockQuantity = s.stockQuantity - :qty,
        s.updatedAt = :now
    WHERE s.ingredientId = :ingredientId
    AND s.warehouse.id = :warehouseId
    AND s.stockQuantity >= :qty
""")
    int decreaseStockAtomic(@Param("ingredientId") Long ingredientId,
                            @Param("warehouseId") Long warehouseId,
                            @Param("qty") BigDecimal qty,
                            @Param("now") long now);

    /** Overload không có now — giữ backward compat cho code cũ đang dùng. */
    @Modifying(flushAutomatically = true)
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

    /** Cộng tồn kho ATOMIC. Luôn thành công (nếu row tồn tại). */
    @Modifying(flushAutomatically = true)
    @Query("""
    UPDATE IngredientStock s
    SET s.stockQuantity = s.stockQuantity + :qty,
        s.updatedAt = :now
    WHERE s.ingredientId = :ingredientId
    AND s.warehouse.id = :warehouseId
""")
    int increaseStockAtomic(@Param("ingredientId") Long ingredientId,
                            @Param("warehouseId") Long warehouseId,
                            @Param("qty") BigDecimal qty,
                            @Param("now") long now);

    /** Cộng totalCostValue ATOMIC. Dùng khi confirm cost phiếu nhập, hoàn cost cancel. */
    @Modifying(flushAutomatically = true)
    @Query("""
    UPDATE IngredientStock s
    SET s.totalCostValue = COALESCE(s.totalCostValue, 0) + :costValue,
        s.updatedAt = :now
    WHERE s.ingredientId = :ingredientId
    AND s.warehouse.id = :warehouseId
""")
    int addCostValueAtomic(@Param("ingredientId") Long ingredientId,
                           @Param("warehouseId") Long warehouseId,
                           @Param("costValue") BigDecimal costValue,
                           @Param("now") long now);

    /** Trừ totalCostValue ATOMIC với sàn 0. Dùng khi FIFO deduct bán hàng. */
    @Modifying(flushAutomatically = true)
    @Query("""
    UPDATE IngredientStock s
    SET s.totalCostValue = CASE
        WHEN COALESCE(s.totalCostValue, 0) >= :costValue THEN s.totalCostValue - :costValue
        ELSE 0
    END,
        s.updatedAt = :now
    WHERE s.ingredientId = :ingredientId
    AND s.warehouse.id = :warehouseId
""")
    int subCostValueAtomic(@Param("ingredientId") Long ingredientId,
                           @Param("warehouseId") Long warehouseId,
                           @Param("costValue") BigDecimal costValue,
                           @Param("now") long now);

    boolean existsByIngredientIdAndWarehouseId(Long ingredientId, Long warehouseId);

    @Query("SELECT s FROM IngredientStock s " +
            "WHERE s.warehouse.id = :warehouseId " +
            "AND (:q IS NULL OR LOWER(s.ingredientNameSnapshot) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "ORDER BY s.ingredientNameSnapshot ASC")
    List<IngredientStock> findByWarehouse(@Param("warehouseId") Long warehouseId,
                                          @Param("q") String q);

    Optional<IngredientStock> findByIngredientIdAndWarehouseId(Long ingredientId, Long warehouseId);

    List<IngredientStock> findByWarehouseId(Long warehouseId);

    List<IngredientStock> findByIngredientId(Long ingredientId);

    @Query("""
        SELECT s FROM IngredientStock s
        WHERE s.warehouse.id = :warehouseId
    """)
    List<IngredientStock> findByWarehouseIdWithIngredient(@Param("warehouseId") Long warehouseId);

    @Query("""
        SELECT s FROM IngredientStock s
        JOIN Ingredient i ON i.id = s.ingredientId
        WHERE i.categoryId IN :categoryIds
    """)
    List<IngredientStock> findAllByIngredientCategoryIds(@Param("categoryIds") List<Long> categoryIds);
}