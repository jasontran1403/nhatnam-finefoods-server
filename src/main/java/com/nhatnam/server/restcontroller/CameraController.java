package com.nhatnam.server.restcontroller;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.camera.CameraDtos.*;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.CameraService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API QUẢN LÝ CAMERA — chỉ OWNER/ADMIN.
 *
 * <p>Lưu thông tin kết nối thiết bị (IP/domain, tài khoản) và danh sách kênh.
 * Kết nối/streaming thật sẽ bổ sung sau khi camera được lắp đặt.
 */
@RestController
@RequestMapping("/api/camera")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
public class CameraController {

    private final CameraService cameraService;

    @GetMapping("/devices")
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> list() {
        try {
            return ResponseEntity.ok(ApiResponse.success(cameraService.listAll(), "OK"));
        } catch (Exception e) {
            log.error("camera list error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/devices/{id}")
    public ResponseEntity<ApiResponse<DeviceResponse>> detail(@PathVariable Long id) {
        return handle(() -> cameraService.getOne(id), "OK");
    }

    @PostMapping("/devices")
    public ResponseEntity<ApiResponse<DeviceResponse>> create(
            @RequestBody CreateDeviceRequest req, Authentication auth) {
        String actor = resolveActorName(auth);
        return handle(() -> cameraService.create(req, actor), "Đã thêm thiết bị camera");
    }

    @PutMapping("/devices/{id}")
    public ResponseEntity<ApiResponse<DeviceResponse>> update(
            @PathVariable Long id, @RequestBody UpdateDeviceRequest req) {
        return handle(() -> cameraService.update(id, req), "Đã cập nhật thiết bị");
    }

    @PatchMapping("/devices/{id}/toggle")
    public ResponseEntity<ApiResponse<DeviceResponse>> toggle(@PathVariable Long id) {
        return handle(() -> cameraService.toggleActive(id), "Đã cập nhật trạng thái");
    }

    /** Nạp lại danh sách kênh của thiết bị (kết nối thật sẽ bổ sung sau). */
    @PostMapping("/devices/{id}/refresh")
    public ResponseEntity<ApiResponse<DeviceResponse>> refresh(@PathVariable Long id) {
        return handle(() -> cameraService.refresh(id), "Đã nạp lại danh sách camera");
    }

    @PutMapping("/devices/{deviceId}/channels/{channelId}")
    public ResponseEntity<ApiResponse<DeviceResponse>> updateChannel(
            @PathVariable Long deviceId, @PathVariable Long channelId,
            @RequestBody UpdateChannelRequest req) {
        return handle(() -> cameraService.updateChannel(deviceId, channelId, req), "Đã cập nhật camera");
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<ApiResponse<String>> delete(@PathVariable Long id) {
        return handle(() -> {
            cameraService.delete(id);
            return "deleted";
        }, "Đã xoá thiết bị camera");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> ResponseEntity<ApiResponse<T>> handle(java.util.function.Supplier<T> action, String message) {
        try {
            return ResponseEntity.ok(ApiResponse.success(action.get(), message));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("camera api error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private String resolveActorName(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof User u)
            return u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
        return null;
    }
}
