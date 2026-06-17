// ────────────────────────────────────────────────────────────────────────────
// FILE 1: ProductIngredientRepository.java
// THAY ĐỔI: sửa JPQL query vì ProductIngredient không còn @ManyToOne product/ingredient
// ────────────────────────────────────────────────────────────────────────────
package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ProductIngredientRepository extends JpaRepository<ProductIngredient, Long> {

    // findByProductId vẫn hoạt động vì productId là plain column
    List<ProductIngredient> findByProductId(Long productId);

    // findByIngredientId — dùng plain column
    List<ProductIngredient> findByIngredientId(Long ingredientId);

    @Modifying
    @Transactional
    // Sửa: dùng pi.productId thay vì pi.product.id (đã bỏ @ManyToOne)
    @Query("DELETE FROM ProductIngredient pi WHERE pi.productId = :productId")
    void deleteByProductId(@Param("productId") Long productId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ProductIngredient pi WHERE pi.ingredientId = :ingredientId")
    void deleteByIngredientId(@Param("ingredientId") Long ingredientId);
}
