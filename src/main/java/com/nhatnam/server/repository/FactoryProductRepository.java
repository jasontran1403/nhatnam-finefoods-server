package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FactoryProductRepository extends JpaRepository<FactoryProduct, Long> {
    List<FactoryProduct> findByIsActiveTrueOrderByNameAsc();
    List<FactoryProduct> findAllByOrderByNameAsc();

    /** Tất cả FactoryProduct đang liên kết tới 1 Ingredient — dùng để đồng bộ tên/đơn vị khi Ingredient đổi */
    List<FactoryProduct> findByIngredientId(Long ingredientId);
}