package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.camera.CameraDtos.*;
import com.nhatnam.server.entity.CameraChannel;
import com.nhatnam.server.entity.CameraDevice;
import com.nhatnam.server.entity.CameraDevice.ConnectionStatus;
import com.nhatnam.server.repository.CameraChannelRepository;
import com.nhatnam.server.repository.CameraDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * QUẢN LÝ CAMERA — lưu thông tin kết nối (IP/domain + tài khoản) và danh sách kênh.
 *
 * <p><b>PHẠM VI HIỆN TẠI:</b> camera chưa được lắp đặt nên service này mới chỉ
 * quản lý DỮ LIỆU. Việc kết nối thật (dò kênh qua ONVIF, lấy RTSP URL, kiểm tra
 * online/offline, chụp snapshot) được gom vào đúng 2 chỗ để sau này thay thế:
 * <ul>
 *   <li>{@link #discoverChannels(CameraDevice)} — dò danh sách kênh của thiết bị</li>
 *   <li>{@link #probeConnection(CameraDevice)} — kiểm tra kết nối/đăng nhập</li>
 * </ul>
 * Chỉ cần thay thân 2 hàm này bằng lời gọi ONVIF/SDK là toàn bộ luồng còn lại
 * (API, UI) hoạt động y nguyên.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CameraService {

    private final CameraDeviceRepository deviceRepository;
    private final CameraChannelRepository channelRepository;

    // ════════════════════════════════════════════════════════════════════════
    // QUERIES
    // ════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<DeviceResponse> listAll() {
        return deviceRepository.findAllWithChannels().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse getOne(Long id) {
        return toDto(loadDevice(id));
    }

    // ════════════════════════════════════════════════════════════════════════
    // COMMANDS
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public DeviceResponse create(CreateDeviceRequest req, String actorName) {
        String host = requireText(req.getHost(), "Vui lòng nhập địa chỉ IP hoặc domain");
        String username = requireText(req.getUsername(), "Vui lòng nhập tên đăng nhập");
        String password = requireText(req.getPassword(), "Vui lòng nhập mật khẩu");
        int port = req.getPort() != null && req.getPort() > 0 ? req.getPort() : defaultPort(req.getProtocol());

        if (deviceRepository.existsByHostAndPort(host, port))
            throw new BusinessException("Thiết bị " + host + ":" + port + " đã được thêm trước đó");

        long now = System.currentTimeMillis();
        CameraDevice device = CameraDevice.builder()
                .name(req.getName() != null && !req.getName().isBlank() ? req.getName().trim() : host)
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .protocol(normalizeProtocol(req.getProtocol()))
                .vendor(trimOrNull(req.getVendor()))
                .location(trimOrNull(req.getLocation()))
                .note(trimOrNull(req.getNote()))
                .active(true)
                .channelCount(req.getChannelCount() != null && req.getChannelCount() > 0
                        ? Math.min(req.getChannelCount(), 64) : 1)
                .connectionStatus(ConnectionStatus.UNKNOWN)
                .createdByName(actorName)
                .createdAt(now).updatedAt(now)
                .channels(new ArrayList<>())
                .build();

        device.setConnectionStatus(probeConnection(device));
        device.getChannels().addAll(discoverChannels(device));
        device.setLastSyncedAt(now);

        return toDto(deviceRepository.save(device));
    }

    @Transactional
    public DeviceResponse update(Long id, UpdateDeviceRequest req) {
        CameraDevice device = loadDevice(id);
        long now = System.currentTimeMillis();

        if (req.getName() != null && !req.getName().isBlank()) device.setName(req.getName().trim());
        if (req.getHost() != null && !req.getHost().isBlank()) device.setHost(req.getHost().trim());
        if (req.getPort() != null && req.getPort() > 0) device.setPort(req.getPort());
        if (req.getUsername() != null && !req.getUsername().isBlank()) device.setUsername(req.getUsername().trim());
        // Mật khẩu bỏ trống ⇒ giữ nguyên giá trị cũ
        if (req.getPassword() != null && !req.getPassword().isBlank()) device.setPassword(req.getPassword());
        if (req.getProtocol() != null) device.setProtocol(normalizeProtocol(req.getProtocol()));
        if (req.getVendor() != null) device.setVendor(trimOrNull(req.getVendor()));
        if (req.getLocation() != null) device.setLocation(trimOrNull(req.getLocation()));
        if (req.getNote() != null) device.setNote(trimOrNull(req.getNote()));
        if (req.getActive() != null) device.setActive(req.getActive());

        // Đổi số kênh ⇒ thêm/bớt kênh cho khớp
        if (req.getChannelCount() != null && req.getChannelCount() > 0) {
            device.setChannelCount(Math.min(req.getChannelCount(), 64));
            syncChannelCount(device, now);
        }

        device.setUpdatedAt(now);
        return toDto(deviceRepository.save(device));
    }

    @Transactional
    public void delete(Long id) {
        deviceRepository.delete(loadDevice(id));
    }

    @Transactional
    public DeviceResponse toggleActive(Long id) {
        CameraDevice device = loadDevice(id);
        device.setActive(!Boolean.TRUE.equals(device.getActive()));
        device.setUpdatedAt(System.currentTimeMillis());
        return toDto(deviceRepository.save(device));
    }

    /**
     * Nạp lại danh sách kênh từ thiết bị. Hiện tại chỉ dựng lại theo
     * {@code channelCount}; khi tích hợp thật sẽ dò trực tiếp qua ONVIF.
     */
    @Transactional
    public DeviceResponse refresh(Long id) {
        CameraDevice device = loadDevice(id);
        long now = System.currentTimeMillis();

        device.setConnectionStatus(probeConnection(device));
        syncChannelCount(device, now);
        // Cập nhật lại URL theo thông tin kết nối hiện tại (host/port có thể đã đổi)
        device.getChannels().forEach(ch -> {
            ch.setStreamUrl(buildStreamUrl(device, ch.getChannelNo()));
            ch.setSnapshotUrl(buildSnapshotUrl(device, ch.getChannelNo()));
            ch.setUpdatedAt(now);
        });
        device.setLastSyncedAt(now);
        device.setUpdatedAt(now);
        return toDto(deviceRepository.save(device));
    }

    @Transactional
    public DeviceResponse updateChannel(Long deviceId, Long channelId, UpdateChannelRequest req) {
        CameraDevice device = loadDevice(deviceId);
        CameraChannel channel = device.getChannels().stream()
                .filter(c -> c.getId().equals(channelId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("Không tìm thấy kênh camera #" + channelId));

        if (req.getName() != null && !req.getName().isBlank()) channel.setName(req.getName().trim());
        if (req.getStreamUrl() != null) channel.setStreamUrl(trimOrNull(req.getStreamUrl()));
        if (req.getSnapshotUrl() != null) channel.setSnapshotUrl(trimOrNull(req.getSnapshotUrl()));
        if (req.getResolution() != null) channel.setResolution(trimOrNull(req.getResolution()));
        if (req.getEnabled() != null) channel.setEnabled(req.getEnabled());
        channel.setUpdatedAt(System.currentTimeMillis());

        channelRepository.save(channel);
        return toDto(device);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ĐIỂM TÍCH HỢP THẬT — thay thân 2 hàm dưới khi camera đã lắp đặt
    // ════════════════════════════════════════════════════════════════════════

    /**
     * TODO(tích hợp): gọi ONVIF {@code GetProfiles} (hoặc API riêng của hãng)
     * bằng {@code host/port/username/password} để lấy danh sách kênh thật.
     *
     * <p>Hiện tại: dựng sẵn {@code channelCount} kênh với URL theo mẫu RTSP phổ biến.
     */
    private List<CameraChannel> discoverChannels(CameraDevice device) {
        long now = System.currentTimeMillis();
        List<CameraChannel> channels = new ArrayList<>();
        int total = device.getChannelCount() != null ? device.getChannelCount() : 1;
        for (int i = 1; i <= total; i++) {
            channels.add(CameraChannel.builder()
                    .device(device)
                    .channelNo(i)
                    .name("Camera " + i)
                    .streamUrl(buildStreamUrl(device, i))
                    .snapshotUrl(buildSnapshotUrl(device, i))
                    .enabled(true)
                    .online(false)
                    .createdAt(now).updatedAt(now)
                    .build());
        }
        return channels;
    }

    /**
     * TODO(tích hợp): mở socket tới {@code host:port} và thử đăng nhập để phân biệt
     * ONLINE / OFFLINE / AUTH_FAILED.
     *
     * <p>Hiện tại: luôn trả về UNKNOWN vì camera chưa được lắp đặt.
     */
    private ConnectionStatus probeConnection(CameraDevice device) {
        return ConnectionStatus.UNKNOWN;
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /** Thêm/bớt kênh cho khớp {@code channelCount}, giữ nguyên các kênh đã có. */
    private void syncChannelCount(CameraDevice device, long now) {
        int target = device.getChannelCount() != null ? device.getChannelCount() : 1;
        List<CameraChannel> channels = device.getChannels();
        channels.sort(Comparator.comparing(CameraChannel::getChannelNo));

        // Bớt kênh dư (xóa từ cuối)
        while (channels.size() > target) channels.remove(channels.size() - 1);

        // Thêm kênh thiếu
        for (int i = channels.size() + 1; i <= target; i++) {
            channels.add(CameraChannel.builder()
                    .device(device)
                    .channelNo(i)
                    .name("Camera " + i)
                    .streamUrl(buildStreamUrl(device, i))
                    .snapshotUrl(buildSnapshotUrl(device, i))
                    .enabled(true)
                    .online(false)
                    .createdAt(now).updatedAt(now)
                    .build());
        }
    }

    /** Mẫu RTSP phổ biến — sẽ được ghi đè bằng URL thật khi tích hợp. */
    private String buildStreamUrl(CameraDevice device, int channelNo) {
        return String.format("rtsp://%s:%d/Streaming/Channels/%d01",
                device.getHost(), rtspPort(device), channelNo);
    }

    private String buildSnapshotUrl(CameraDevice device, int channelNo) {
        String scheme = "HTTPS".equalsIgnoreCase(device.getProtocol()) ? "https" : "http";
        return String.format("%s://%s:%d/ISAPI/Streaming/channels/%d01/picture",
                scheme, device.getHost(), device.getPort(), channelNo);
    }

    private int rtspPort(CameraDevice device) {
        return "RTSP".equalsIgnoreCase(device.getProtocol()) ? device.getPort() : 554;
    }

    private int defaultPort(String protocol) {
        if ("HTTPS".equalsIgnoreCase(protocol)) return 443;
        if ("RTSP".equalsIgnoreCase(protocol)) return 554;
        return 80;
    }

    private String normalizeProtocol(String p) {
        if (p == null || p.isBlank()) return "HTTP";
        String up = p.trim().toUpperCase();
        return switch (up) {
            case "HTTP", "HTTPS", "RTSP" -> up;
            default -> "HTTP";
        };
    }

    private String requireText(String v, String message) {
        if (v == null || v.isBlank()) throw new BusinessException(message);
        return v.trim();
    }

    private String trimOrNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private CameraDevice loadDevice(Long id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thiết bị camera #" + id));
    }

    // ── Mapper ───────────────────────────────────────────────────────────────

    private DeviceResponse toDto(CameraDevice d) {
        DeviceResponse r = new DeviceResponse();
        r.setId(d.getId());
        r.setName(d.getName());
        r.setHost(d.getHost());
        r.setPort(d.getPort());
        r.setUsername(d.getUsername());
        r.setProtocol(d.getProtocol());
        r.setVendor(d.getVendor());
        r.setLocation(d.getLocation());
        r.setNote(d.getNote());
        r.setActive(d.getActive());
        r.setConnectionStatus(d.getConnectionStatus() != null ? d.getConnectionStatus().name() : "UNKNOWN");
        r.setChannelCount(d.getChannelCount());
        r.setLastSyncedAt(d.getLastSyncedAt());
        r.setCreatedByName(d.getCreatedByName());
        r.setCreatedAt(d.getCreatedAt());
        r.setUpdatedAt(d.getUpdatedAt());
        r.setHasPassword(d.getPassword() != null && !d.getPassword().isBlank());
        r.setChannels(d.getChannels().stream()
                .sorted(Comparator.comparing(CameraChannel::getChannelNo))
                .map(c -> {
                    ChannelResponse cr = new ChannelResponse();
                    cr.setId(c.getId());
                    cr.setChannelNo(c.getChannelNo());
                    cr.setName(c.getName());
                    cr.setStreamUrl(c.getStreamUrl());
                    cr.setSnapshotUrl(c.getSnapshotUrl());
                    cr.setResolution(c.getResolution());
                    cr.setEnabled(c.getEnabled());
                    cr.setOnline(c.getOnline());
                    return cr;
                }).toList());
        return r;
    }
}
