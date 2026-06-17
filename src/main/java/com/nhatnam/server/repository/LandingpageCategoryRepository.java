package com.nhatnam.server.repository;

import com.nhatnam.server.entity.LandingpageCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LandingpageCategoryRepository extends JpaRepository<LandingpageCategory, Long> {
    List<LandingpageCategory> findAllByOrderBySortOrderAscNameAsc();
    boolean existsByName(String name);
}