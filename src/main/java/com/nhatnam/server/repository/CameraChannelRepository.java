package com.nhatnam.server.repository;

import com.nhatnam.server.entity.CameraChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CameraChannelRepository extends JpaRepository<CameraChannel, Long> {

    List<CameraChannel> findByDeviceIdOrderByChannelNoAsc(Long deviceId);

    long countByDeviceId(Long deviceId);
}
