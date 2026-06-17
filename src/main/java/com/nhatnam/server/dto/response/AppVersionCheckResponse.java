package com.nhatnam.server.dto.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppVersionCheckResponse {

    private String version;

    private Long versionCode;

    private Boolean forceRefresh;

    private String message;
}