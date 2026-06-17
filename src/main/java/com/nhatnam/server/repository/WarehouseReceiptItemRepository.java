package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WarehouseReceiptItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseReceiptItemRepository extends JpaRepository<WarehouseReceiptItem, Long> {
}
