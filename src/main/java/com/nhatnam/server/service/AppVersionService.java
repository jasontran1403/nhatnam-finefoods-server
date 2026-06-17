package com.nhatnam.server.service;

import com.nhatnam.server.dto.response.AppVersionCheckResponse;
import com.nhatnam.server.entity.AppVersion;
import com.nhatnam.server.repository.AppVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppVersionService {

    private final AppVersionRepository appVersionRepository;

    public AppVersionCheckResponse getCurrentVersion() {

        AppVersion version = appVersionRepository
                .findById(1L)
                .orElseThrow(() ->
                        new RuntimeException("AppVersion not found"));

        return AppVersionCheckResponse.builder()
                .version(version.getVersion())
                .versionCode(version.getVersionCode())
                .forceRefresh(version.getForceRefresh())
                .message(version.getMessage())
                .build();
    }
}