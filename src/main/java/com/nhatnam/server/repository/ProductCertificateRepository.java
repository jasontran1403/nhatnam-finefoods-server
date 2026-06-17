package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductCertificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductCertificateRepository extends JpaRepository<ProductCertificate, Long> {

    List<ProductCertificate> findByProductIdOrderByIssuedAtDesc(Long productId);

    @Query("SELECT c.productId, COUNT(c) FROM ProductCertificate c WHERE c.productId IS NOT NULL GROUP BY c.productId")
    List<Object[]> countByProduct();

    @Query("SELECT DISTINCT c.productId FROM ProductCertificate c WHERE c.productId IS NOT NULL")
    List<Long> findDistinctProductIds();

    @Query("SELECT c FROM ProductCertificate c LEFT JOIN FETCH c.files WHERE c.productId = :productId ORDER BY c.issuedAt DESC")
    List<ProductCertificate> findByProductIdWithFiles(@Param("productId") Long productId);

    List<ProductCertificate> findByProductId(Long productId);
}
