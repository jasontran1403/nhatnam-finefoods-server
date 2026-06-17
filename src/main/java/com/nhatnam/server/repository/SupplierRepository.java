package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    List<Supplier> findByActiveTrueOrderByNameAsc();

    @Query("SELECT s FROM Supplier s WHERE s.active = true AND " +
           "(:q IS NULL OR LOWER(s.name) LIKE LOWER(CONCAT('%',:q,'%')) " +
           "OR LOWER(s.phone) LIKE LOWER(CONCAT('%',:q,'%')))")
    Page<Supplier> searchActive(@Param("q") String q, Pageable pageable);
}
