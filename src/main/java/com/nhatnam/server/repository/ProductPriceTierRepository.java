package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductPriceTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ProductPriceTierRepository extends JpaRepository<ProductPriceTier, Long> {

    Optional<ProductPriceTier> findByIdAndProductId(Long id, Long productId);

    void deleteByProductId(Long productId);

    // Tìm tier phù hợp theo quantity
    @Query("SELECT t FROM ProductPriceTier t WHERE t.product.id = :productId " +
            "AND t.isActive = true " +
            "AND t.minQuantity <= :quantity " +
            "AND (t.maxQuantity IS NULL OR t.maxQuantity > :quantity) " +
            "ORDER BY t.minQuantity DESC")
    List<ProductPriceTier> findMatchingTiers(
            @Param("productId") Long productId,
            @Param("quantity") BigDecimal quantity);

    // Lấy tất cả tier của product, sắp xếp theo sortOrder ASC
    // Dùng để lấy tier đầu tiên (khung thấp nhất) cho DISCOUNT_PERCENT sỉ
    @Query("SELECT t FROM ProductPriceTier t WHERE t.product.id = :productId " +
            "AND t.isActive = true ORDER BY t.sortOrder ASC")
    List<ProductPriceTier> findByProductIdSortedAsc(@Param("productId") Long productId);
}