package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SupplyStockTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupplyStockTransactionRepository extends JpaRepository<SupplyStockTransaction, Long> {

    /** 2 tab lịch sử "Nhập" / "Rút", filter theo khoảng ngày. */
    @Query("""
        SELECT t FROM SupplyStockTransaction t
        WHERE t.warehouseId = :warehouseId
          AND (:type IS NULL OR CAST(t.type AS string) = :type)
          AND (:from IS NULL OR t.createdAt >= :from)
          AND (:to   IS NULL OR t.createdAt <= :to)
        ORDER BY t.createdAt DESC
    """)
    Page<SupplyStockTransaction> history(@Param("warehouseId") Long warehouseId,
                                         @Param("type") String type,
                                         @Param("from") Long from,
                                         @Param("to") Long to,
                                         Pageable pageable);

    List<SupplyStockTransaction> findBySupplyItemId(Long supplyItemId);
}
