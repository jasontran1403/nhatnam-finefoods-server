package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AllowanceLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AllowanceLabelRepository extends JpaRepository<AllowanceLabel, Long> {
    Optional<AllowanceLabel> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
    List<AllowanceLabel> findAllByOrderBySystemDescNameAsc();
}