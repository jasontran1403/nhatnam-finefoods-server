package com.nhatnam.server.repository;

import com.nhatnam.server.entity.PricingCostLabel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PricingCostLabelRepository extends JpaRepository<PricingCostLabel, Long> {
    List<PricingCostLabel> findByIsActiveTrueOrderByNameAsc();
    Optional<PricingCostLabel> findByNameIgnoreCase(String name);
}