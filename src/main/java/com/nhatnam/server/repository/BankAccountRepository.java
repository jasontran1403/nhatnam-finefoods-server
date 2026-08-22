package com.nhatnam.server.repository;

import com.nhatnam.server.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {
    List<BankAccount> findByActiveTrueOrderBySortOrderAscIdAsc();
    Optional<BankAccount> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
}
