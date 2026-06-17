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
    private final OrderRepository   orderRepository;   // ← THÊM
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
            LocalDate today    = LocalDate.now(VN);
            long todayMs       = today.atStartOfDay(VN).toInstant().toEpochMilli();
            long in7DaysMs     = today.plusDays(7).atStartOfDay(VN).toInstant().toEpochMilli();

            List<Order> debtOrders = orderRepository
                    .findByStatusOrderByCreatedAtDesc(OrderStatus.PENDING_PAYMENT)
                    .stream()
                    .filter(o -> "DEBT".equalsIgnoreCase(
                            o.getPaymentMethod() != null ? o.getPaymentMethod() : ""))
                    .toList();

            long nearingDeadline = 0, overdueCount = 0;
            for (Order o : debtOrders) {
                if (o.getPendingPaymentAt() == null || o.getPendingPaymentAt() <= 0) continue;
                if (o.getDebtDays() <= 0) continue;
                LocalDate pendingDate = Instant.ofEpochMilli(o.getPendingPaymentAt())
                        .atZone(VN).toLocalDate();
                LocalDate deadline    = pendingDate.plusDays(1).plusDays(o.getDebtDays());
                long deadlineMs       = deadline.atStartOfDay(VN).toInstant().toEpochMilli();
                if (deadlineMs < todayMs)         overdueCount++;
                else if (deadlineMs <= in7DaysMs) nearingDeadline++;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nearingDeadline", nearingDeadline);
            result.put("overdueCount",    overdueCount);
            result.put("totalDebtOrders", debtOrders.size());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.ok(Map.of(
                    "nearingDeadline", 0L,
                    "overdueCount",    0L,
                    "totalDebtOrders", 0L));
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