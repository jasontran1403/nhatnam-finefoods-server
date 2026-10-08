package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByIsActiveTrue();

    @Query("SELECT p.sku FROM Product p WHERE p.sku LIKE :prefix% AND p.isActive = true")
    List<String> findSkusByPrefix(@Param("prefix") String prefix);
    List<Product> findByCategoryAndIsActiveTrue(String category);
    Optional<Product> findByIdAndIsActiveTrue(Long id);
    long countByIsActiveTrueAndCreatedAtBetween(long from, long to);

    /** ID sản phẩm thuộc 1 danh mục (dùng cho báo cáo KH×SP chọn danh mục). */
    @Query("SELECT p.id FROM Product p WHERE p.categoryId = :categoryId")
    java.util.List<Long> findIdsByCategoryId(@Param("categoryId") Long categoryId);

    /** ID sản phẩm thuộc danh mục theo ID HOẶC theo TÊN (product.category) — bền hơn. */
    @Query("SELECT p.id FROM Product p WHERE p.categoryId = :categoryId " +
           "OR (p.category IS NOT NULL AND LOWER(TRIM(p.category)) = LOWER(TRIM(:name)))")
    java.util.List<Long> findIdsByCategoryIdOrName(@Param("categoryId") Long categoryId,
                                                   @Param("name") String name);
}
