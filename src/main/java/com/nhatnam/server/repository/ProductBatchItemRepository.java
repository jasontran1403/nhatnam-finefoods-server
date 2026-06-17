package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductBatchItem;
import com.nhatnam.server.entity.ProductBatchItem.ItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductBatchItemRepository extends JpaRepository<ProductBatchItem, Long> {
    List<ProductBatchItem> findByBatchId(Long batchId);
    List<ProductBatchItem> findByBatchIdAndStatus(Long batchId, ItemStatus status);
    long countByBatchIdAndStatus(Long batchId, ItemStatus status);
}
