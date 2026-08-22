package com.nhatnam.server.restcontroller.driver;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.Driver;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.DriverPortalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API cho TÀI XẾ (role {@code DRIVER}).
 *
 * <pre>
 *  GET  /api/driver/me                    → thông tin tài xế đang đăng nhập
 *  GET  /api/driver/orders                → danh sách đơn đang giao của tôi
 *  GET  /api/driver/orders/{id}           → chi tiết đơn (thông tin cần giao)
 *  POST /api/driver/orders/{id}/complete  → xác nhận ĐÃ GIAO XONG
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/driver")
@RequiredArgsConstructor
public class DriverPortalController {

    private final DriverPortalService driverPortalService;

    /** Thông tin tài xế gắn với tài khoản đang đăng nhập. */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@AuthenticationPrincipal User user) {
        try {
            Driver d = driverPortalService.driverOf(user);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", user.getId());
            m.put("fullName", user.getFullName());
            m.put("linked", d != null);
            if (d != null) {
                m.put("driverId", d.getId());
                m.put("driverName", d.getName());
                m.put("vehicleType", d.getVehicleType() != null ? d.getVehicleType().name() : "BOTH");
            }
            return ResponseEntity.ok(ApiResponse.success(m, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Danh sách đơn ĐANG GIAO được gán cho tài xế này. */
    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myOrders(
            @AuthenticationPrincipal User user) {
        try {
            return ResponseEntity.ok(ApiResponse.success(driverPortalService.myActiveOrders(user), "OK"));
        } catch (Exception e) {
            log.error("[Driver] Lỗi lấy danh sách đơn", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Chi tiết 1 đơn — thông tin cần để giao hàng. */
    @GetMapping("/orders/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> orderDetail(
            @AuthenticationPrincipal User user, @PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    driverPortalService.myOrderDetail(user, id), "OK"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[Driver] Lỗi lấy chi tiết đơn {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Nút "Hoàn thành" — xác nhận đã giao xong đơn hàng. */
    @PostMapping("/orders/{id}/complete")
    public ResponseEntity<ApiResponse<Map<String, Object>>> complete(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String receiver = body != null ? body.get("receiverName") : null;
            String note = body != null ? body.get("note") : null;
            return ResponseEntity.ok(ApiResponse.success(
                    driverPortalService.completeDelivery(user, id, receiver, note),
                    "Đã xác nhận giao hàng thành công"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[Driver] Lỗi xác nhận giao đơn {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}
