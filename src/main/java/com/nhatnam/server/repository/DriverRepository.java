package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DriverRepository extends JpaRepository<Driver, Long> {
    List<Driver> findByActiveTrueOrderByNameAsc();
    List<Driver> findByNameContainingIgnoreCaseAndActiveTrue(String name);
    List<Driver> findByActiveTrueAndVehicleTypeInOrderByNameAsc(List<Driver.VehicleType> types);
    List<Driver> findByNameContainingIgnoreCaseAndActiveTrueAndVehicleTypeIn(String name, List<Driver.VehicleType> types);
}
