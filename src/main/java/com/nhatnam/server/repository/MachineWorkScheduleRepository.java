package com.nhatnam.server.repository;
import com.nhatnam.server.entity.MachineWorkSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MachineWorkScheduleRepository extends JpaRepository<MachineWorkSchedule, Long> {
    Optional<MachineWorkSchedule> findByMachine_Id(Long machineId);
}
