package com.nhatnam.server.dto.camera;

import lombok.Data;

import java.util.List;

/** Request/Response DTO cho module Quản lý camera. */
public class CameraDtos {

    // ── Request ──────────────────────────────────────────────────────────────

    @Data
    public static class CreateDeviceRequest {
        private String name;
        /** IP hoặc domain — bắt buộc. */
        private String host;
        private Integer port;
        private String username;
        private String password;
        /** HTTP | HTTPS | RTSP */
        private String protocol;
        private String vendor;
        private String location;
        private String note;
        /**
         * Số kênh trên thiết bị. Khi tích hợp thật, trường này chỉ là gợi ý —
         * hệ thống sẽ dò số kênh trực tiếp từ thiết bị.
         */
        private Integer channelCount;
    }

    @Data
    public static class UpdateDeviceRequest {
        private String name;
        private String host;
        private Integer port;
        private String username;
        /** Bỏ trống = giữ nguyên mật khẩu cũ. */
        private String password;
        private String protocol;
        private String vendor;
        private String location;
        private String note;
        private Boolean active;
        private Integer channelCount;
    }

    @Data
    public static class UpdateChannelRequest {
        private String name;
        private String streamUrl;
        private String snapshotUrl;
        private String resolution;
        private Boolean enabled;
    }

    // ── Response ─────────────────────────────────────────────────────────────

    @Data
    public static class ChannelResponse {
        private Long id;
        private Integer channelNo;
        private String name;
        private String streamUrl;
        private String snapshotUrl;
        private String resolution;
        private Boolean enabled;
        private Boolean online;
    }

    @Data
    public static class DeviceResponse {
        private Long id;
        private String name;
        private String host;
        private Integer port;
        private String username;
        private String protocol;
        private String vendor;
        private String location;
        private String note;
        private Boolean active;
        private String connectionStatus;
        private Integer channelCount;
        private Long lastSyncedAt;
        private String createdByName;
        private Long createdAt;
        private Long updatedAt;
        private List<ChannelResponse> channels;
        // Mật khẩu KHÔNG BAO GIỜ được trả về — chỉ báo đã có hay chưa.
        private Boolean hasPassword;
    }
}
