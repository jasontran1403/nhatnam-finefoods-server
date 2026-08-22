package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductionRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProductionRecipeRepository extends JpaRepository<ProductionRecipe, Long> {

    List<ProductionRecipe> findByFactoryProduct_IdAndIsActiveTrueOrderByCreatedAtDesc(Long productId);

    List<ProductionRecipe> findByIsActiveTrueOrderByCreatedAtDesc();

    // ĐÃ XOÁ findByIdWithItemsAndSteps: JOIN FETCH đồng thời 2 collection kiểu List
    // (items + steps, cả 2 đều @OneToMany List — Hibernate gọi là "bag") trong CÙNG
    // 1 câu query luôn gây MultipleBagFetchException (hoặc cartesian product nhân
    // sai số dòng nếu dùng Set thay List). Quy tắc an toàn: chỉ JOIN FETCH tối đa
    // 1 collection-bag mỗi query. Cần cả 2 collection → gọi findByIdWithItems() rồi
    // trigger lazy-load collection còn lại bằng recipe.getSteps().size() (cùng
    // transaction nên không lỗi LazyInitializationException).

    @Query("SELECT DISTINCT r FROM ProductionRecipe r " +
            "LEFT JOIN FETCH r.items i LEFT JOIN FETCH i.factoryMaterial " +
            "WHERE r.id = :id")
    Optional<ProductionRecipe> findByIdWithItems(@Param("id") Long id);

    // Chỉ fetch steps
    @Query("SELECT DISTINCT r FROM ProductionRecipe r " +
            "LEFT JOIN FETCH r.steps s LEFT JOIN FETCH s.machine " +
            "WHERE r.id = :id")
    Optional<ProductionRecipe> findByIdWithSteps(@Param("id") Long id);

    boolean existsByFactoryProduct_IdAndNameIgnoreCase(Long factoryProductId, String name);

    boolean existsByFactoryProduct_IdAndNameIgnoreCaseAndIdNot(Long factoryProductId, String name, Long id);
}
