package com.nhatnam.server.repository;

import com.nhatnam.server.entity.LandingpageProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LandingpageProductRepository extends JpaRepository<LandingpageProduct, Long> {
    Page<LandingpageProduct> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<LandingpageProduct> findByCategoryIdOrderByCreatedAtDesc(Long categoryId, Pageable pageable);
}