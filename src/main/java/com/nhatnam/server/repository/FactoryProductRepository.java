package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FactoryProductRepository extends JpaRepository<FactoryProduct, Long> {
    List<FactoryProduct> findByIsActiveTrueOrderByNameAsc();
    List<FactoryProduct> findAllByOrderByNameAsc();
}
