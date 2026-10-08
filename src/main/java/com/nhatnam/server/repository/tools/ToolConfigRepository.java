package com.nhatnam.server.repository.tools;

import com.nhatnam.server.entity.tools.ToolConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ToolConfigRepository extends JpaRepository<ToolConfig, Long> {
    Optional<ToolConfig> findByConfigKey(String configKey);
}