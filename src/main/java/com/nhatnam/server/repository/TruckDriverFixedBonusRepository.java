package com.nhatnam.server.repository;

import com.nhatnam.server.entity.TruckDriverFixedBonus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TruckDriverFixedBonusRepository extends JpaRepository<TruckDriverFixedBonus, Long> {
    Optional<TruckDriverFixedBonus> findByUser_IdAndMonthAndYear(Long userId, Integer month, Integer year);
    List<TruckDriverFixedBonus> findByMonthAndYear(Integer month, Integer year);
}