package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SemiFinishedTransferNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SemiFinishedTransferNoteRepository extends JpaRepository<SemiFinishedTransferNote, Long> {

    long countByNoteCodeStartingWith(String prefix);

    @Query("SELECT t FROM SemiFinishedTransferNote t " +
            "WHERE (:status IS NULL OR t.status = :status) " +
            "ORDER BY t.createdAt DESC")
    Page<SemiFinishedTransferNote> search(@Param("status") SemiFinishedTransferNote.Status status, Pageable pageable);
}
