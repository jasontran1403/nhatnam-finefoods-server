package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ManualLeaveUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ManualLeaveUsageRepository extends JpaRepository<ManualLeaveUsage, Long> {

    Optional<ManualLeaveUsage> findByUserIdAndYearAndMonth(Long userId, int year, int month);

    /**
     * Nạp toàn bộ dòng manual của một năm cho MỌI nhân viên — dùng khi build
     * bảng "Quản lý phép" để tránh N+1 query.
     */
    @Query("SELECT m FROM ManualLeaveUsage m WHERE m.year = :year")
    List<ManualLeaveUsage> findAllForYear(@Param("year") int year);

    void deleteByUserIdAndYearAndMonth(Long userId, int year, int month);
}