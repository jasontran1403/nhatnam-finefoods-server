package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialPriceEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialPriceEntryRepository extends JpaRepository<MaterialPriceEntry, Long> {

    /** Lấy tất cả entries theo tên nguyên liệu, sắp xếp theo thời gian tăng dần */
    List<MaterialPriceEntry> findByMaterialNameOrderByCreatedAtAsc(String materialName);

    /** Lấy danh sách tên nguyên liệu duy nhất */
    @Query("SELECT DISTINCT e.materialName FROM MaterialPriceEntry e ORDER BY e.materialName")
    List<String> findDistinctMaterialNames();
}
