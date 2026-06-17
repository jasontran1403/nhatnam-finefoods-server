package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Machine;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MachineRepository extends JpaRepository<Machine, Long> {
    List<Machine> findByStatusOrderByNameAsc(Machine.MachineStatus status);
    List<Machine> findAllByOrderByNameAsc();
}
