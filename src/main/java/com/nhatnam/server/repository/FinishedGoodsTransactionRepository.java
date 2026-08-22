package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FinishedGoodsTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinishedGoodsTransactionRepository extends JpaRepository<FinishedGoodsTransaction, Long> {
    Page<FinishedGoodsTransaction> findByProductNameOrderByCreatedAtDesc(String productName, Pageable pageable);
    Page<FinishedGoodsTransaction> findAllByOrderByCreatedAtDesc(Pageable pageable);
}