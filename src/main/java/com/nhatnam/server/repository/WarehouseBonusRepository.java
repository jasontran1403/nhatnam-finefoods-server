package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WarehouseBonus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarehouseBonusRepository extends JpaRepository<WarehouseBonus, Long> {
    List<WarehouseBonus> findByMonthAndYear(Integer month, Integer year);
    Optional<WarehouseBonus> findByUser_IdAndMonthAndYear(Long userId, Integer month, Integer year);
}