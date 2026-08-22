package com.nhatnam.server.repository;

import com.nhatnam.server.entity.CameraDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CameraDeviceRepository extends JpaRepository<CameraDevice, Long> {

    /** Nạp kèm channels để tránh N+1 khi render danh sách. */
    @Query("SELECT DISTINCT d FROM CameraDevice d LEFT JOIN FETCH d.channels ORDER BY d.id DESC")
    List<CameraDevice> findAllWithChannels();

    Optional<CameraDevice> findByHostAndPort(String host, Integer port);

    boolean existsByHostAndPort(String host, Integer port);
}
