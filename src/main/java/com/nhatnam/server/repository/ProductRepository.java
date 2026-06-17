package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByIsActiveTrue();
    List<Product> findByCategoryAndIsActiveTrue(String category);
    Optional<Product> findByIdAndIsActiveTrue(Long id);
    long countByIsActiveTrueAndCreatedAtBetween(long from, long to);
}