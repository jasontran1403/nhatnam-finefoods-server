package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductionPlanProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductionPlanProductRepository extends JpaRepository<ProductionPlanProduct, Long> {

    List<ProductionPlanProduct> findByProductionPlan_IdOrderBySortOrderAsc(Long planId);

    void deleteByProductionPlan_Id(Long planId);
}