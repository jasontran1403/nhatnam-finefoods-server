package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("SELECT n FROM Notification n WHERE n.targetRole = :role ORDER BY n.createdAt DESC")
    Page<Notification> findByTargetRole(@Param("role") String role, Pageable pageable);

    /**
     * Lấy thông báo cho user đang đăng nhập với role cụ thể:
     * - targetUser = user này VÀ targetRole = role đang active, HOẶC
     * - targetRole = role đang active (broadcast cho role)
     */
    @Query("SELECT n FROM Notification n WHERE " +
           "(n.targetUser IS NOT NULL AND n.targetUser.id = :userId AND n.targetRole = :role) " +
           "OR (n.targetUser IS NULL AND n.targetRole = :role) " +
           "ORDER BY n.createdAt DESC")
    Page<Notification> findForUser(@Param("role") String role, @Param("userId") Long userId, Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE " +
           "((n.targetUser IS NOT NULL AND n.targetUser.id = :userId AND n.targetRole = :role) " +
           "OR (n.targetUser IS NULL AND n.targetRole = :role)) " +
           "AND n.isRead = false")
    long countUnread(@Param("role") String role, @Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.id = :id")
    void markAsRead(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.targetRole = :role AND n.isRead = false")
    void markAllReadByRole(@Param("role") String role);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE " +
           "((n.targetUser IS NOT NULL AND n.targetUser.id = :userId AND n.targetRole = :role) " +
           "OR (n.targetUser IS NULL AND n.targetRole = :role)) " +
           "AND n.isRead = false")
    void markAllReadForUser(@Param("role") String role, @Param("userId") Long userId);
}
