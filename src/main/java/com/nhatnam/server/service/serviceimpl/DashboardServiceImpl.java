package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.ChartPointResponse;
import com.nhatnam.server.dto.DashboardSummaryResponse;
import com.nhatnam.server.dto.TopProductResponse;
import com.nhatnam.server.dto.dashboard.TopCustomerDto;
import com.nhatnam.server.dto.dashboard.TopSellerDto;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.repository.OrderItemRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final OrderRepository     orderRepository;
    private final OrderItemRepository orderItemRepository;

    private static final ZoneId VN_ZONE    = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int    MAX_POINTS = 12;

    /** Các trạng thái được tính vào dashboard — loại trừ CANCELLED */
    private static boolean isActive(Order o) {
        return o.getStatus() == OrderStatus.PREPARING
                || o.getStatus() == OrderStatus.DELIVERING
                || o.getStatus() == OrderStatus.PENDING_PAYMENT
                || o.getStatus() == OrderStatus.COMPLETED;
    }

    private static boolean isCompleted(Order o) {
        return o.getStatus() == OrderStatus.COMPLETED;
    }

    private static boolean isPending(Order o) {
        return o.getStatus() == OrderStatus.PREPARING
                || o.getStatus() == OrderStatus.DELIVERING
                || o.getStatus() == OrderStatus.PENDING_PAYMENT;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Summary — 4 card mới giống SellerDashboard
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public DashboardSummaryResponse getSummary(long from, long to) {
        List<Order> orders = orderRepository.findByCreatedAtBetween(from, to);

        // Card 1: đếm theo trạng thái (không tính CANCELLED)
        long totalOrders          = orders.stream().filter(DashboardServiceImpl::isActive).count();
        long preparingOrders      = orders.stream().filter(o -> o.getStatus() == OrderStatus.PREPARING).count();
        long deliveringOrders     = orders.stream().filter(o -> o.getStatus() == OrderStatus.DELIVERING).count();
        long pendingPaymentOrders = orders.stream().filter(o -> o.getStatus() == OrderStatus.PENDING_PAYMENT).count();
        long completedOrders      = orders.stream().filter(DashboardServiceImpl::isCompleted).count();

        // Card 2: doanh thu
        BigDecimal totalRevenue = orders.stream()
                .filter(DashboardServiceImpl::isActive)
                .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal collectedRevenue = orders.stream()
                .filter(DashboardServiceImpl::isCompleted)
                .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal uncollectedRevenue = orders.stream()
                .filter(DashboardServiceImpl::isPending)
                .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Card 3 & 4: công nợ — placeholder, xử lý sau
        BigDecimal nearingDeadlineAmount = new BigDecimal("0");
        BigDecimal overdueAmount         = new BigDecimal("0");

        return DashboardSummaryResponse.builder()
                // card 1
                .totalOrders(totalOrders)
                .preparingOrders(preparingOrders)
                .deliveringOrders(deliveringOrders)
                .pendingPaymentOrders(pendingPaymentOrders)
                .completedOrders(completedOrders)
                // card 2
                .totalRevenue(totalRevenue)
                .collectedRevenue(collectedRevenue)
                .uncollectedRevenue(uncollectedRevenue)
                // card 3 & 4
                .nearingDeadlineAmount(nearingDeadlineAmount)
                .overdueAmount(overdueAmount)
                // giữ lại các field cũ để không break các chỗ khác dùng
                .successOrders(completedOrders)
                .totalPaid(collectedRevenue)
                .totalDebt(uncollectedRevenue)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chart — chỉ tính đơn active (không CANCELLED)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public List<ChartPointResponse> getChart(long from, long to, String groupBy) {
        List<Order> orders = orderRepository.findByCreatedAtBetween(from, to)
                .stream().filter(DashboardServiceImpl::isActive).toList();

        long diffMillis = to - from;
        long diffHours  = diffMillis / 3_600_000L;
        long diffDays   = diffMillis / 86_400_000L;

        if (diffHours <= 24)       return buildHourlyChart(orders, from, to, diffHours);
        else if (diffDays <= 366)  return buildDailyChart(orders, from, to, diffDays);
        else                       return buildMonthlyChart(orders, from, to);
    }

    private List<ChartPointResponse> buildHourlyChart(
            List<Order> orders, long from, long to, long diffHours) {
        int totalHours     = (int) Math.max(1, diffHours + 1);
        int hoursPerBucket = (int) Math.max(1, Math.ceil((double) totalHours / MAX_POINTS));

        LocalDateTime start = Instant.ofEpochMilli(from).atZone(VN_ZONE).toLocalDateTime()
                .withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end   = Instant.ofEpochMilli(to).atZone(VN_ZONE).toLocalDateTime();

        List<LocalDateTime> hours = new ArrayList<>();
        LocalDateTime cur = start;
        while (!cur.isAfter(end)) { hours.add(cur); cur = cur.plusHours(1); }

        return groupOrdersIntoBuckets(orders, hours, hoursPerBucket,
                dt -> dt.atZone(VN_ZONE).toInstant().toEpochMilli(),
                (first, last) -> first.toLocalDate().equals(last.toLocalDate())
                        ? first.getHour() + "h-" + last.getHour() + "h"
                        : first.format(DateTimeFormatter.ofPattern("dd/MM")) + " " + first.getHour() + "h",
                hoursPerBucket * 3_600_000L);
    }

    private List<ChartPointResponse> buildDailyChart(
            List<Order> orders, long from, long to, long diffDays) {
        int totalDays     = (int) Math.max(1, diffDays + 1);
        int daysPerBucket = (int) Math.max(1, Math.ceil((double) totalDays / MAX_POINTS));

        LocalDate startDate = Instant.ofEpochMilli(from).atZone(VN_ZONE).toLocalDate();
        LocalDate endDate   = Instant.ofEpochMilli(to).atZone(VN_ZONE).toLocalDate();

        List<LocalDate> days = new ArrayList<>();
        LocalDate d = startDate;
        while (!d.isAfter(endDate)) { days.add(d); d = d.plusDays(1); }

        return groupOrdersIntoBuckets(orders, days, daysPerBucket,
                date -> date.atStartOfDay(VN_ZONE).toInstant().toEpochMilli(),
                (first, last) -> {
                    if (first.equals(last)) return first.format(DateTimeFormatter.ofPattern("dd/MM"));
                    if (first.getMonth() == last.getMonth())
                        return first.getDayOfMonth() + "-" + last.format(DateTimeFormatter.ofPattern("dd/MM"));
                    return first.format(DateTimeFormatter.ofPattern("dd/MM")) + "-"
                            + last.format(DateTimeFormatter.ofPattern("dd/MM"));
                },
                (long) daysPerBucket * 86_400_000L);
    }

    private List<ChartPointResponse> buildMonthlyChart(List<Order> orders, long from, long to) {
        YearMonth startMonth = YearMonth.from(Instant.ofEpochMilli(from).atZone(VN_ZONE).toLocalDate());
        YearMonth endMonth   = YearMonth.from(Instant.ofEpochMilli(to).atZone(VN_ZONE).toLocalDate());

        List<YearMonth> months = new ArrayList<>();
        YearMonth m = startMonth;
        while (!m.isAfter(endMonth)) { months.add(m); m = m.plusMonths(1); }

        int totalMonths     = months.size();
        int monthsPerBucket = (int) Math.max(1, Math.ceil((double) totalMonths / MAX_POINTS));

        return groupOrdersIntoBuckets(orders, months, monthsPerBucket,
                ym -> ym.atDay(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli(),
                (first, last) -> first.equals(last)
                        ? first.format(DateTimeFormatter.ofPattern("MM/yyyy"))
                        : first.format(DateTimeFormatter.ofPattern("MM/yy")) + "-"
                        + last.format(DateTimeFormatter.ofPattern("MM/yy")),
                0L);
    }

    private <T> List<ChartPointResponse> groupOrdersIntoBuckets(
            List<Order> orders, List<T> items, int perBucket,
            java.util.function.Function<T, Long> startMsFn,
            java.util.function.BiFunction<T, T, String> labelFn,
            long bucketDurationMs) {

        List<ChartPointResponse> result = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            int end    = Math.min(i + perBucket, items.size());
            List<T> bucket = items.subList(i, end);

            long bucketFrom = startMsFn.apply(bucket.get(0));
            long bucketTo;
            if (bucketDurationMs > 0) {
                bucketTo = startMsFn.apply(bucket.get(bucket.size() - 1)) + bucketDurationMs;
            } else {
                T last = bucket.get(bucket.size() - 1);
                bucketTo = Instant.ofEpochMilli(startMsFn.apply(last)).atZone(VN_ZONE)
                        .toLocalDate().plusMonths(1).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
            }

            final long bFrom = bucketFrom, bTo = bucketTo;
            List<Order> bo = orders.stream()
                    .filter(o -> o.getCreatedAt() >= bFrom && o.getCreatedAt() < bTo).toList();

            long pendingCount = bo.stream().filter(DashboardServiceImpl::isPending).count();
            long successCount = bo.stream().filter(DashboardServiceImpl::isCompleted).count();

            BigDecimal pendingRevenue = bo.stream().filter(DashboardServiceImpl::isPending)
                    .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal successRevenue = bo.stream().filter(DashboardServiceImpl::isCompleted)
                    .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(ChartPointResponse.builder()
                    .label(labelFn.apply(bucket.get(0), bucket.get(bucket.size() - 1)))
                    .bucketFrom(bFrom).bucketTo(bTo)
                    .pendingCount(pendingCount).successCount(successCount)
                    .pendingRevenue(pendingRevenue).successRevenue(successRevenue)
                    .build());
            i = end;
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top products — chỉ tính đơn active
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public List<TopProductResponse> getTopProducts(long from, long to, int limit) {
        List<Order> orders = orderRepository.findByCreatedAtBetween(from, to);
        Map<Long, TopProductResponse> map = new LinkedHashMap<>();

        for (Order order : orders) {
            if (!isActive(order)) continue;           // ← bỏ CANCELLED
            if (order.getOrderItems() == null) continue;
            for (OrderItem item : order.getOrderItems()) {
                map.compute(item.getProductId(), (k, v) -> {
                    if (v == null) v = TopProductResponse.builder()
                            .productId(item.getProductId())
                            .productName(item.getProductName())
                            .imageUrl(item.getProductImageUrl())
                            .build();
                    BigDecimal qty = item.getQuantity();
                    if ("BOX".equals(item.getSaleType()) && item.getUnitsPerBox() != null && item.getUnitsPerBox() > 0) {
                        qty = qty.multiply(BigDecimal.valueOf(item.getUnitsPerBox()));
                    }
                    v.setTotalQty(v.getTotalQty().add(qty));
                    v.setTotalRevenue(v.getTotalRevenue().add(
                            item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO));
                    return v;
                });
            }
        }

        return map.values().stream()
                .sorted(Comparator.comparing(TopProductResponse::getTotalRevenue).reversed())
                .limit(limit).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top sellers & customers — native query (lọc CANCELLED ở DB)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public List<TopSellerDto> getTopSellers(int limit, long from, long to, String sortBy) {
        List<Object[]> rows = "orders".equalsIgnoreCase(sortBy)
                ? orderRepository.findTopSellersByOrdersNative(from, to, limit)
                : orderRepository.findTopSellersNative(from, to, limit);
        return rows.stream().map(r -> TopSellerDto.builder()
                .userId(toLong(r[0])).username((String) r[1]).fullName((String) r[2])
                .totalCompletedOrders(toLong(r[3])).totalRevenue(toBigDecimal(r[4]))
                .build()).toList();
    }

    @Override
    public List<TopCustomerDto> getTopCustomers(int limit, long from, long to) {
        List<Object[]> rows = orderRepository.findTopCustomersNative(from, to, limit);
        return rows.stream().map(r -> TopCustomerDto.builder()
                .customerId(toLong(r[0])).customerName((String) r[1])
                .totalOrders(toLong(r[2])).completedOrders(toLong(r[3]))
                .totalSpent(toBigDecimal(r[4]))
                .build()).toList();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────
    private static Long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(o.toString());
    }
}
