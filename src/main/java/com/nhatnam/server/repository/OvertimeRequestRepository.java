package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OvertimeRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OvertimeRequestRepository extends JpaRepository<OvertimeRequest, Long> {

    /** Tất cả đơn OT trong khoảng ngày, phân trang */
    @Query("SELECT o FROM OvertimeRequest o WHERE " +
           "(:from IS NULL OR o.otDate >= :from) AND " +
           "(:to IS NULL OR o.otDate <= :to) " +
           "ORDER BY o.createdAt DESC")
    Page<OvertimeRequest> findAllInRange(@Param("from") Long from,
                                          @Param("to") Long to,
                                          Pageable pageable);

    /** OT của user trong tháng (để tính lương) */
    @Query("SELECT DISTINCT o FROM OvertimeRequest o JOIN o.employees e WHERE " +
           "e.user.id = :userId AND o.otDate >= :from AND o.otDate <= :to")
    List<OvertimeRequest> findByUserAndPeriod(@Param("userId") Long userId,
                                               @Param("from") Long from,
                                               @Param("to") Long to);
}
