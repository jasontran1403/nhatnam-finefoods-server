package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SupplyOrderGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplyOrderGroupRepository extends JpaRepository<SupplyOrderGroup, Long> {
    List<SupplyOrderGroup> findByMaterialRequest_IdOrderByIdAsc(Long materialRequestId);
    Optional<SupplyOrderGroup> findByMaterialRequest_IdAndSupplierId(Long materialRequestId, Long supplierId);
    List<SupplyOrderGroup> findBySupplierId(Long supplierId);
}
