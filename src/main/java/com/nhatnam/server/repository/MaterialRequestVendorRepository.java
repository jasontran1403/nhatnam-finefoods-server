package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialRequestVendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialRequestVendorRepository extends JpaRepository<MaterialRequestVendor, Long> {
    List<MaterialRequestVendor> findByMaterialRequest_Id(Long requestId);
}
