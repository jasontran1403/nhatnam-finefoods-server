package com.nhatnam.server.entity.tools;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "tool_config")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ToolConfig {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "config_key", unique = true, length = 50) private String configKey;
    @Column(name = "config_value", length = 500) private String configValue;
}
