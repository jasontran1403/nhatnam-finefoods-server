package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SellerKpi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SellerKpiRepository extends JpaRepository<SellerKpi, Long> {
    Optional<SellerKpi> findBySellerIsNullAndPeriodKey(String periodKey);

    Optional<SellerKpi> findBySellerIdAndPeriodKey(Long sellerId, String periodKey);

    List<SellerKpi> findByPeriodKeyOrderByTotalRevenueDesc(String periodKey);

    @Query("SELECT k FROM SellerKpi k WHERE k.seller.id = :sellerId ORDER BY k.periodKey DESC")
    List<SellerKpi> findBySellerIdOrderByPeriodKeyDesc(@Param("sellerId") Long sellerId);
}
