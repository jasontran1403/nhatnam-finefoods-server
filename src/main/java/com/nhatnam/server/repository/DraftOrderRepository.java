package com.nhatnam.server.repository;

import com.nhatnam.server.entity.DraftOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DraftOrderRepository extends JpaRepository<DraftOrder, Long> {
    Optional<DraftOrder> findByIdAndUserId(Long id, Long userId);

    List<DraftOrder> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<DraftOrder> findByDraftCode(String draftCode);

    @Query("SELECT d FROM DraftOrder d WHERE d.user.id = :userId ORDER BY d.updatedAt DESC")
    Page<DraftOrder> findByUserIdPaged(@Param("userId") Long userId, Pageable pageable);

    boolean existsByDraftCode(String draftCode);
}
