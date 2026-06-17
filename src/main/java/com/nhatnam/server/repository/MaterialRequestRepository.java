package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialRequestRepository extends JpaRepository<MaterialRequest, Long> {

    long countByRequestCodeStartingWith(String prefix);

    @Query("""
        SELECT r FROM MaterialRequest r
        WHERE r.createdBy.id = :userId
          AND (:status IS NULL OR CAST(r.status AS string) = :status)
          AND (:dateFrom IS NULL OR r.createdAt >= :dateFrom)
          AND (:dateTo IS NULL OR r.createdAt <= :dateTo)
          AND (:search IS NULL OR :search = ''
               OR LOWER(r.requestCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(r.createdByName) LIKE LOWER(CONCAT('%',:search,'%')))
        ORDER BY r.createdAt DESC
    """)
    Page<MaterialRequest> findByCreatedBy_IdAndFilters(
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("dateFrom") Long dateFrom,
            @Param("dateTo") Long dateTo,
            @Param("search") String search,
            Pageable pageable);

    @Query("""
        SELECT r FROM MaterialRequest r
        WHERE (:status IS NULL OR CAST(r.status AS string) = :status)
          AND (:dateFrom IS NULL OR r.createdAt >= :dateFrom)
          AND (:dateTo IS NULL OR r.createdAt <= :dateTo)
          AND (:search IS NULL OR :search = ''
               OR LOWER(r.requestCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(r.createdByName) LIKE LOWER(CONCAT('%',:search,'%')))
        ORDER BY r.createdAt DESC
    """)
    Page<MaterialRequest> findByFilters(
            @Param("status") String status,
            @Param("dateFrom") Long dateFrom,
            @Param("dateTo") Long dateTo,
            @Param("search") String search,
            Pageable pageable);
}
