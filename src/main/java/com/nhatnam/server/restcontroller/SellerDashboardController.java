package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.dto.response.SellerDashboardDTO;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.SellerDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Đặt trong SellerController hoặc tạo class riêng.
 * Endpoint: GET /api/seller/dashboard
 */
@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/seller/dashboard")
public class SellerDashboardController {

    private final SellerDashboardService sellerDashboardService;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Dashboard tổng hợp — trả về 1 payload duy nhất.
     *
     * @param from    Epoch-ms bắt đầu (mặc định: đầu ngày hôm nay theo VN)
     * @param to      Epoch-ms kết thúc (mặc định: cuối ngày hôm nay theo VN)
     * @param groupBy HOUR | DAY | MONTH (mặc định tự suy theo khoảng ngày)
     * @param topN    Số lượng top sản phẩm / khách hàng (mặc định 10)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<SellerDashboardDTO.DashboardResponse>> getDashboard(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(defaultValue = "10") int topN,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));

            User user = (User) authentication.getPrincipal();

            // Defaults: hôm nay theo VN timezone
            LocalDate today = LocalDate.now(VN);
            long fromMs = from != null ? from :
                    today.atStartOfDay(VN).toInstant().toEpochMilli();
            long toMs = to != null ? to :
                    today.atTime(23, 59, 59, 999_000_000).atZone(VN).toInstant().toEpochMilli();

            // Auto groupBy
            String gb = groupBy;
            if (gb == null || gb.isBlank()) {
                long diff = toMs - fromMs;
                if (diff <= 86_400_000L)      gb = "HOUR";
                else if (diff <= 32L * 86_400_000) gb = "DAY";
                else                           gb = "MONTH";
            }

            SellerDashboardDTO.DashboardResponse data =
                    sellerDashboardService.getDashboard(user.getId(), fromMs, toMs, gb, topN);

            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            log.error("[SELLER] getDashboard error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Thống kê khách mới / khách cũ trong kỳ.
     * Khách MỚI = có đơn trong kỳ VÀ không có đơn nào trước kỳ này.
     * Khách CŨ  = có đơn trong kỳ VÀ đã mua ít nhất 1 lần trước đó.
     */
    @GetMapping("/customer-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCustomerStats(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));

            User user = (User) authentication.getPrincipal();
            LocalDate today = LocalDate.now(VN);
            long fromMs = from != null ? from : today.atStartOfDay(VN).toInstant().toEpochMilli();
            long toMs   = to   != null ? to   : today.atTime(23,59,59,999_000_000).atZone(VN).toInstant().toEpochMilli();

            Map<String, Object> stats = sellerDashboardService.getCustomerStats(user.getId(), fromMs, toMs);
            return ResponseEntity.ok(ApiResponse.success(stats, "OK"));
        } catch (Exception e) {
            log.error("[SELLER] getCustomerStats error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}