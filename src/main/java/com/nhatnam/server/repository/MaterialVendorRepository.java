package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialVendor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialVendorRepository extends JpaRepository<MaterialVendor, Long> {
    List<MaterialVendor> findByActiveTrueOrderByNameAsc();
    List<MaterialVendor> findByNameContainingIgnoreCaseAndActiveTrue(String name);
    List<MaterialVendor> findByVendorTypeInAndActiveTrueOrderByNameAsc(List<MaterialVendor.VendorType> types);
    List<MaterialVendor> findByNameContainingIgnoreCaseAndVendorTypeInAndActiveTrue(String name, List<MaterialVendor.VendorType> types);
}
