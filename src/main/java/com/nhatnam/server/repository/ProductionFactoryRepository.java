package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductionFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ProductionFactoryRepository extends JpaRepository<ProductionFactory, Long> {

    List<ProductionFactory> findByStatusOrderByNameAsc(ProductionFactory.FactoryStatus status);

    List<ProductionFactory> findAllByOrderByNameAsc();

    /** Các xưởng mà user này là manager */
    @Query("SELECT f FROM ProductionFactory f JOIN f.managers m WHERE m.id = :userId " +
            "AND f.status = 'ACTIVE' ORDER BY f.name ASC")
    List<ProductionFactory> findByManagerId(@Param("userId") Long userId);
}