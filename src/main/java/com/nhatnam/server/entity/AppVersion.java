package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_version")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String version;

    @Column(nullable = false)
    private Long versionCode;

    @Column(nullable = false)
    private Boolean forceRefresh;

    @Column(length = 255)
    private String message;

    private LocalDateTime updatedAt;
}