package com.nhatnam.server.repository.tools;

import com.nhatnam.server.entity.tools.ToolInvoiceDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ToolInvoiceDetailRepository extends JpaRepository<ToolInvoiceDetail, Long> {
    List<ToolInvoiceDetail> findAllByOrderBySttAsc();
    Optional<ToolInvoiceDetail> findByOrderNumber(String orderNumber);
}