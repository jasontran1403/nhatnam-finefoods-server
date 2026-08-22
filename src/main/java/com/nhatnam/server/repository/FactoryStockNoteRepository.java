package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryStockNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FactoryStockNoteRepository extends JpaRepository<FactoryStockNote, Long> {

    List<FactoryStockNote> findByFactory_IdOrderByCreatedAtDesc(Long factoryId);

    @Query("SELECT n FROM FactoryStockNote n WHERE n.factory.id = :factoryId " +
            "AND (:type IS NULL OR n.type = :type) ORDER BY n.createdAt DESC")
    List<FactoryStockNote> findByFactoryAndType(@Param("factoryId") Long factoryId,
                                                @Param("type") FactoryStockNote.NoteType type);

    long countByNoteCodeStartingWith(String prefix);
}
