package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Category;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByIsActiveTrueOrderByNameAsc();

    Optional<Category> findByIdAndIsActiveTrue(Long id);

    boolean existsByNameIgnoreCaseAndIsActiveTrue(String name);

    Optional<Category> findByName(String name);

    List<Category> findByIsActiveTrue(Pageable pageable);

    /**
     * Chỉ lấy category ROOT — không có parent_id (parent_id IS NULL).
     * Dùng để loại trừ các row cũ như "Beef" (parent_id=1) ra khỏi danh sách root.
     * Chú ý: Category entity hiện tại KHÔNG có field parentId nên dùng native query.
     */
    @Query(value = "SELECT * FROM categories WHERE is_active = 1 AND (parent_id IS NULL) ORDER BY name ASC",
            nativeQuery = true)
    List<Category> findRootCategoriesActive();
}