package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SupplyItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupplyItemRepository extends JpaRepository<SupplyItem, Long> {

    /** Tra cứu theo BỘ BA đã chuẩn hoá — nền tảng của quy tắc gộp tồn kho. */
    Optional<SupplyItem> findByNameNormalizedAndSpecNormalizedAndUnitNormalizedAndDeletedAtIsNull(
            String nameNormalized, String specNormalized, String unitNormalized);

    List<SupplyItem> findByDeletedAtIsNullOrderByNameAsc();

    /**
     * Autocomplete — BIỆN PHÁP CHÍNH chống nhập sai gây phân mảnh tồn kho.
     * Owner gõ tên → gợi ý các SupplyItem đã có; chọn một cái thì tên + quy cách
     * + ĐVT tự điền và KHOÁ LẠI ở FE.
     */
    @Query("""
        SELECT s FROM SupplyItem s
        WHERE s.deletedAt IS NULL
          AND (:q IS NULL OR :q = ''
               OR LOWER(s.name) LIKE LOWER(CONCAT('%',:q,'%'))
               OR LOWER(s.specification) LIKE LOWER(CONCAT('%',:q,'%')))
        ORDER BY s.name ASC, s.specification ASC
    """)
    List<SupplyItem> suggest(@Param("q") String q);
}
