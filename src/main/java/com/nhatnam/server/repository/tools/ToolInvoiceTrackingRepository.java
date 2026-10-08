package com.nhatnam.server.repository.tools;

import com.nhatnam.server.entity.tools.ToolInvoiceTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ToolInvoiceTrackingRepository extends JpaRepository<ToolInvoiceTracking, Long> {
    List<ToolInvoiceTracking> findAllByOrderByIdAsc();
    Optional<ToolInvoiceTracking> findFirstByInvoice(String invoice);
}