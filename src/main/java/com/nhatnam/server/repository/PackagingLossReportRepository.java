package com.nhatnam.server.repository;

import com.nhatnam.server.entity.PackagingLossReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PackagingLossReportRepository extends JpaRepository<PackagingLossReport, Long> {

    long countByReportCodeStartingWith(String prefix);

    /** Tìm biên bản hao hụt theo dòng phiếu chuyển kho — dùng để FE biết ID để in trực tiếp */
    java.util.Optional<PackagingLossReport> findByTransferLine_Id(Long transferLineId);

    @Query("SELECT r FROM PackagingLossReport r " +
            "WHERE (:productName IS NULL OR LOWER(r.productName) LIKE LOWER(CONCAT('%', :productName, '%'))) " +
            "ORDER BY r.createdAt DESC")
    Page<PackagingLossReport> search(@Param("productName") String productName, Pageable pageable);
}
