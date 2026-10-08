package com.nhatnam.server.repository.tools;

import com.nhatnam.server.entity.tools.ToolSalesRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ToolSalesRecordRepository extends JpaRepository<ToolSalesRecord, Long> {
    List<ToolSalesRecord> findAllByOrderByIdAsc();
    Optional<ToolSalesRecord> findFirstBySoHoaDon(String soHoaDon);
    Optional<ToolSalesRecord> findFirstBySoChungTu(String soChungTu);
}