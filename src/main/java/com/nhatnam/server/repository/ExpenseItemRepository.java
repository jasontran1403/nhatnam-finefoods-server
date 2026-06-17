package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ExpenseItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseItemRepository extends JpaRepository<ExpenseItem, Long> {
    List<ExpenseItem> findByVoucherId(Long voucherId);
}
