// PATH: src/main/java/com/nhatnam/server/repository/MisaProductCatalogRepository.java
package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MisaProductCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MisaProductCatalogRepository extends JpaRepository<MisaProductCatalog, Long> {

    Optional<MisaProductCatalog> findByProductCode(String productCode);

    @Query("SELECT m FROM MisaProductCatalog m ORDER BY m.misaCategory, m.productName")
    List<MisaProductCatalog> findAllOrdered();

    List<MisaProductCatalog> findByParseNoteIsNotNull();

    boolean existsByProductCode(String productCode);
}