package com.nhatnam.server.repository.tools;

import com.nhatnam.server.entity.tools.ToolReceiptOutput;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ToolReceiptOutputRepository extends JpaRepository<ToolReceiptOutput, Long> {
    List<ToolReceiptOutput> findAllByOrderByRowIndexAsc();
    @Modifying @Query("DELETE FROM ToolReceiptOutput") void deleteAllRows();
}