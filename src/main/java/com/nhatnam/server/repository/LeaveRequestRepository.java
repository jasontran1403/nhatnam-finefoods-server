package com.nhatnam.server.repository;

import com.nhatnam.server.entity.LeaveRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    /** Phiếu nghỉ của user trong khoảng thời gian */
    @Query("SELECT l FROM LeaveRequest l WHERE l.user.id = :userId " +
           "AND l.leaveDate >= :from AND l.leaveDate <= :to")
    List<LeaveRequest> findByUserAndPeriod(@Param("userId") Long userId,
                                            @Param("from") Long from,
                                            @Param("to") Long to);

    /** Phiếu nghỉ không lương của user trong tháng (để tính lương) */
    @Query("SELECT l FROM LeaveRequest l WHERE l.user.id = :userId " +
           "AND l.leaveType = 'UNPAID' " +
           "AND l.leaveDate >= :from AND l.leaveDate <= :to")
    List<LeaveRequest> findUnpaidByUserAndPeriod(@Param("userId") Long userId,
                                                  @Param("from") Long from,
                                                  @Param("to") Long to);

    /** Tất cả phiếu nghỉ trong khoảng ngày, phân trang */
    @Query("SELECT l FROM LeaveRequest l WHERE " +
           "(:from IS NULL OR l.leaveDate >= :from) AND " +
           "(:to IS NULL OR l.leaveDate <= :to) " +
           "ORDER BY l.createdAt DESC")
    Page<LeaveRequest> findAllInRange(@Param("from") Long from,
                                       @Param("to") Long to,
                                       Pageable pageable);

    /** Tất cả phiếu nghỉ của user */
    List<LeaveRequest> findByUserIdOrderByCreatedAtDesc(Long userId);
}
