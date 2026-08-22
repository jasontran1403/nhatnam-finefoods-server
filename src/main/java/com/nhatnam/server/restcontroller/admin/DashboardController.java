package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.dashboard.*;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.service.admin.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService  dashboardService;
    private final com.nhatnam.server.service.DebtStatsService debtStatsService;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @GetMapping("/stats")
    public ApiResponse<DashboardStatsDto> getStats(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        long[] range = resolveRange(from, to);
        return ApiResponse.ok(dashboardService.getStats(range[0], range[1]));
    }

    @GetMapping("/top-products")
    public ApiResponse<List<TopProductDto>> getTopProducts(
            @RequestParam(defaultValue = "10")      int    limit,
            @RequestParam(required = false)          Long   from,
            @RequestParam(required = false)          Long   to,
            @RequestParam(defaultValue = "revenue")  String sortBy) {
        long[] range = resolveRange(from, to);
        return ApiResponse.ok(dashboardService.getTopProducts(limit, range[0], range[1], sortBy));
    }

    @GetMapping("/top-sellers")
    public ApiResponse<List<TopSellerDto>> getTopSellers(
            @RequestParam(defaultValue = "10")      int    limit,
            @RequestParam(required = false)          Long   from,
            @RequestParam(required = false)          Long   to,
            @RequestParam(defaultValue = "revenue")  String sortBy) {
        long[] range = resolveRange(from, to);
        return ApiResponse.ok(dashboardService.getTopSellers(limit, range[0], range[1], sortBy));
    }

    @GetMapping("/top-customers")
    public ApiResponse<List<TopCustomerDto>> getTopCustomers(
            @RequestParam(defaultValue = "10") int  limit,
            @RequestParam(required = false)    Long from,
            @RequestParam(required = false)    Long to) {
        long[] range = resolveRange(from, to);
        return ApiResponse.ok(dashboardService.getTopCustomers(limit, range[0], range[1]));
    }

    /**
     * GET /api/admin/dashboard/debt-stats
     * Dùng chung cho ADMIN và OWNER — logic giống AccountantController.getDebtStats()
     * nhưng nằm ở endpoint /api/admin/ nên không bị chặn bởi role ACCOUNTANT.
     */
    @GetMapping("/debt-stats")
    public ApiResponse<Map<String, Object>> getDebtStats() {
        try {
            var s = debtStatsService.compute();
            Map<String, Object> result = new LinkedHashMap<>();
            // Số lượng đơn (giữ lại để tương thích ngược)
            result.put("nearingDeadline",       s.nearingCount());
            result.put("overdueCount",          s.overdueCount());
            result.put("totalDebtOrders",       s.totalDebtOrders());
            // Tổng TIỀN chưa thu (đã trừ paidAmount, làm tròn từng đơn) — dùng để hiển thị
            result.put("nearingDeadlineAmount", s.nearingAmount());
            result.put("overdueAmount",         s.overdueAmount());
            result.put("totalDebtAmount",       s.totalDebtAmount());
            // Phân tuổi nợ (aging) theo ngày kể từ khi tạo đơn
            result.put("aging0to30",            s.aging0to30());
            result.put("aging31to60",           s.aging31to60());
            result.put("aging61to90",           s.aging61to90());
            result.put("aging90plus",           s.aging90plus());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            Map<String, Object> zero = new LinkedHashMap<>();
            zero.put("nearingDeadline", 0L);
            zero.put("overdueCount", 0L);
            zero.put("totalDebtOrders", 0L);
            zero.put("nearingDeadlineAmount", java.math.BigDecimal.ZERO);
            zero.put("overdueAmount", java.math.BigDecimal.ZERO);
            zero.put("totalDebtAmount", java.math.BigDecimal.ZERO);
            zero.put("aging0to30", java.math.BigDecimal.ZERO);
            zero.put("aging31to60", java.math.BigDecimal.ZERO);
            zero.put("aging61to90", java.math.BigDecimal.ZERO);
            zero.put("aging90plus", java.math.BigDecimal.ZERO);
            return ApiResponse.ok(zero);
        }
    }

    // ── DEBUG — XÓA SAU KHI FIX XONG ────────────────────────────────────────
    @GetMapping("/debug-chart")
    public ApiResponse<Map<String, Object>> debugChart(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        long[] range = resolveRange(from, to);
        DashboardStatsDto stats = dashboardService.getStats(range[0], range[1]);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("from_millis", range[0]);
        debug.put("to_millis",   range[1]);
        debug.put("from_date",   Instant.ofEpochMilli(range[0]).atZone(VN).toLocalDateTime().toString());
        debug.put("to_date",     Instant.ofEpochMilli(range[1]).atZone(VN).toLocalDateTime().toString());
        debug.put("revenueToday",  stats.getRevenueToday());
        debug.put("point_count",   stats.getRevenueLast30Days() != null
                ? stats.getRevenueLast30Days().size() : 0);
        debug.put("points",        stats.getRevenueLast30Days());
        return ApiResponse.ok(debug);
    }

    private long[] resolveRange(Long from, Long to) {
        if (from != null && to != null) return new long[]{from, to};
        LocalDate today = LocalDate.now(VN);
        long start = today.atStartOfDay(VN).toInstant().toEpochMilli();
        long end   = today.plusDays(1).atStartOfDay(VN).toInstant().toEpochMilli() - 1;
        return new long[]{start, end};
    }
}