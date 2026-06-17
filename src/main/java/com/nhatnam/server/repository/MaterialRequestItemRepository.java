package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialRequestItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialRequestItemRepository extends JpaRepository<MaterialRequestItem, Long> {
    List<MaterialRequestItem> findByMaterialRequest_Id(Long requestId);
}
