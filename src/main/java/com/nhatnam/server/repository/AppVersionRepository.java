package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppVersionRepository
        extends JpaRepository<AppVersion, Long> {
}