package com.nhatnam.server.restcontroller.driver;

import com.nhatnam.server.dto.driver.DriverOdometerReportDto;
import com.nhatnam.server.dto.driver.DriverOrderDetailDto;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.DriverOdometerReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * BÁO CÁO ODO TÀI XẾ (OWNER / ADMIN).
 *
 * <pre>
 *  GET /api/admin/driver-odometer?from=2026-07-01&to=2026-07-15
 *      → mỗi tài xế: odo đầu kỳ, odo cuối kỳ, tổng km, số đơn đã giao
 *
 *  GET /api/admin/driver-odometer/{driverId}/orders?from=…&to=…
 *      → chi tiết các đơn tài xế đã giao (địa chỉ giao, khách, ngày)
 * </pre>
 *
 * <p>Số liệu lấy từ điểm danh ODO mà kho đã nhập hằng ngày — màn này chỉ tổng hợp,
 * không nhập liệu, nên chỉ có GET.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/driver-odometer")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
public class DriverOdometerReportController {

    private final DriverOdometerReportService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DriverOdometerReportDto>>> report(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    service.report(from, to, includeInactive), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[DRIVER_ODO] report error from={} to={}", from, to, e);
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.INTERNAL_SERVER_ERROR, "Không tải được báo cáo ODO"));
        }
    }

    @GetMapping("/{driverId}/orders")
    public ResponseEntity<ApiResponse<List<DriverOrderDetailDto>>> orders(
            @PathVariable Long driverId,
            @RequestParam String from,
            @RequestParam String to) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    service.orders(driverId, from, to), "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[DRIVER_ODO] orders error driverId={}", driverId, e);
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.INTERNAL_SERVER_ERROR, "Không tải được danh sách đơn"));
        }
    }
}