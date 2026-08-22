package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * MỘT KÊNH CAMERA thuộc về 1 {@link CameraDevice}.
 *
 * <p>Với camera IP đơn lẻ thì thiết bị chỉ có 1 kênh; với đầu ghi thì mỗi cổng
 * cắm camera là 1 kênh.
 *
 * <p>{@code streamUrl}/{@code snapshotUrl} hiện được dựng sẵn theo mẫu RTSP phổ
 * biến; khi tích hợp thật sẽ được ghi đè bằng URL do thiết bị trả về.
 */
@Entity
@Table(name = "camera_channel",
        uniqueConstraints = @UniqueConstraint(columnNames = {"device_id", "channel_no"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private CameraDevice device;

    /** Số thứ tự kênh trên thiết bị (1, 2, 3...). */
    @Column(name = "channel_no", nullable = false)
    private Integer channelNo;

    @Column(nullable = false, length = 255)
    private String name;

    /** URL luồng video (RTSP/HLS) — điền sẵn theo mẫu, cập nhật khi kết nối thật. */
    @Column(name = "stream_url", length = 1000)
    private String streamUrl;

    /** URL ảnh chụp tức thời — dùng làm thumbnail trên UI. */
    @Column(name = "snapshot_url", length = 1000)
    private String snapshotUrl;

    @Column(length = 50)
    private String resolution;

    /** Cho phép hiển thị kênh này trên màn hình giám sát. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    /** Trạng thái kênh lần kiểm tra gần nhất — chưa kết nối thật thì để false. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean online = false;

    private Long createdAt;
    private Long updatedAt;
}
