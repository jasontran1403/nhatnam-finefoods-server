package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AnnualMPS;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AnnualMPSRepository extends JpaRepository<AnnualMPS, Long> {

    @Query("SELECT a FROM AnnualMPS a JOIN FETCH a.factoryProduct " +
            "WHERE a.year = :year " +
            "ORDER BY a.month ASC, a.factoryProduct.name ASC")
    List<AnnualMPS> findByYearWithProduct(@Param("year") int year);

    Optional<AnnualMPS> findByYearAndMonthAndFactoryProduct_Id(int year, int month, Long productId);

    @Query("SELECT a FROM AnnualMPS a JOIN FETCH a.factoryProduct " +
            "WHERE a.year = :year " +
            "AND (:productId IS NULL OR a.factoryProduct.id = :productId) " +
            "ORDER BY a.month ASC")
    List<AnnualMPS> findByYearAndProduct(
            @Param("year")      int year,
            @Param("productId") Long productId);
}