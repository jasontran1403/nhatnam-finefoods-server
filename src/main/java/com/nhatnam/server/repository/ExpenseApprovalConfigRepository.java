package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ExpenseApprovalConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExpenseApprovalConfigRepository extends JpaRepository<ExpenseApprovalConfig, Long> {
}
