package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * THIẾT BỊ CAMERA — 1 đầu ghi (NVR/DVR) hoặc 1 camera IP độc lập, xác định bằng
 * IP/domain + tài khoản đăng nhập.
 *
 * <p>Một thiết bị có thể chứa NHIỀU kênh (camera con) — xem {@link CameraChannel}.
 *
 * <p>GIAI ĐOẠN HIỆN TẠI: chỉ LƯU thông tin kết nối và dựng sẵn danh sách kênh.
 * Việc kết nối thật (ONVIF/RTSP/HTTP API của hãng) sẽ bổ sung sau khi camera
 * được lắp đặt — xem {@code CameraService.discoverChannels()}.
 */
@Entity
@Table(name = "camera_device",
        uniqueConstraints = @UniqueConstraint(columnNames = {"host", "port"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraDevice {

    public enum ConnectionStatus { UNKNOWN, ONLINE, OFFLINE, AUTH_FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên gợi nhớ do người dùng đặt (VD: "Đầu ghi kho A"). */
    @Column(nullable = false, length = 255)
    private String name;

    /** Địa chỉ IP hoặc domain (VD: 192.168.1.64 hoặc camera.congty.vn). */
    @Column(nullable = false, length = 255)
    private String host;

    @Builder.Default
    @Column(nullable = false)
    private Integer port = 80;

    @Column(nullable = false, length = 255)
    private String username;

    /**
     * Mật khẩu đăng nhập thiết bị. KHÔNG BAO GIỜ trả về trong response
     * (xem {@code CameraService.toDto}).
     */
    @Column(nullable = false, length = 500)
    private String password;

    /** HTTP | HTTPS | RTSP — dùng khi dựng URL kết nối. */
    @Builder.Default
    @Column(length = 20)
    private String protocol = "HTTP";

    /** Hãng thiết bị (Hikvision/Dahua/...) — để chọn đúng driver khi tích hợp thật. */
    @Column(length = 100)
    private String vendor;

    /** Vị trí lắp đặt, ghi chú thêm. */
    @Column(length = 500)
    private String location;

    @Column(length = 1000)
    private String note;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", length = 20)
    private ConnectionStatus connectionStatus = ConnectionStatus.UNKNOWN;

    /** Số kênh khai báo lúc tạo — dùng để dựng sẵn danh sách kênh. */
    @Builder.Default
    @Column(name = "channel_count")
    private Integer channelCount = 1;

    @Column(name = "last_synced_at")
    private Long lastSyncedAt;

    @Column(name = "created_by_name", length = 255)
    private String createdByName;

    private Long createdAt;
    private Long updatedAt;

    @Builder.Default
    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("channelNo ASC")
    private List<CameraChannel> channels = new ArrayList<>();
}
