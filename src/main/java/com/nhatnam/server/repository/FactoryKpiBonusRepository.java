package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryKpiBonus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FactoryKpiBonusRepository extends JpaRepository<FactoryKpiBonus, Long> {
    Optional<FactoryKpiBonus> findByMonthAndYear(Integer month, Integer year);
}
