package com.nhatnam.server.repository;

import com.nhatnam.server.entity.CustomerCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerCategoryRepository extends JpaRepository<CustomerCategory, Long> {

    /** Tất cả phân loại, sắp xếp theo sort_order rồi name */
    List<CustomerCategory> findAllByOrderBySortOrderAscNameAsc();

    /** Tìm theo tên (case-insensitive) để check trùng */
    Optional<CustomerCategory> findByNameIgnoreCase(String name);

    /** Tìm kiếm gợi ý (contains, case-insensitive) */
    List<CustomerCategory> findByNameContainingIgnoreCaseOrderBySortOrderAscNameAsc(String keyword);
}