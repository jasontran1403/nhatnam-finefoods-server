package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialVendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialVendorRepository extends JpaRepository<MaterialVendor, Long> {
    List<MaterialVendor> findByActiveTrueOrderByNameAsc();
    List<MaterialVendor> findByNameContainingIgnoreCaseAndActiveTrue(String name);
    List<MaterialVendor> findByVendorTypeInAndActiveTrueOrderByNameAsc(List<MaterialVendor.VendorType> types);
    List<MaterialVendor> findByNameContainingIgnoreCaseAndVendorTypeInAndActiveTrue(String name, List<MaterialVendor.VendorType> types);

    /**
     * Lọc NCC theo loại + đang hoạt động, COI vendorType = NULL NHƯ MATERIAL.
     * Cần thiết vì các NCC tạo trước khi thêm cột vendor_type có giá trị NULL trong DB
     * (ddl-auto=update thêm cột nhưng không backfill), sẽ bị bỏ sót nếu chỉ dùng IN(...).
     */
    @Query("""
        SELECT v FROM MaterialVendor v
        WHERE v.active = true
          AND (v.vendorType IN :types
               OR (v.vendorType IS NULL AND :includeNull = true))
        ORDER BY v.name ASC
        """)
    List<MaterialVendor> findActiveByTypesOrNull(
            @Param("types") List<MaterialVendor.VendorType> types,
            @Param("includeNull") boolean includeNull);

    @Query("""
        SELECT v FROM MaterialVendor v
        WHERE v.active = true
          AND LOWER(v.name) LIKE LOWER(CONCAT('%', :name, '%'))
          AND (v.vendorType IN :types
               OR (v.vendorType IS NULL AND :includeNull = true))
        ORDER BY v.name ASC
        """)
    List<MaterialVendor> findActiveByNameAndTypesOrNull(
            @Param("name") String name,
            @Param("types") List<MaterialVendor.VendorType> types,
            @Param("includeNull") boolean includeNull);

    /** Kiểm tra trùng tên NCC (case-insensitive). */
    java.util.Optional<MaterialVendor> findByNameIgnoreCase(String name);
}