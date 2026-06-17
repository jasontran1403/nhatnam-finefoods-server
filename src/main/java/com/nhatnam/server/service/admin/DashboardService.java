package com.nhatnam.server.service.admin;

import com.nhatnam.server.dto.dashboard.*;
import com.nhatnam.server.entity.ExpenseVoucher;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.repository.ExpenseVoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final OrderRepository    orderRepository;
    private final ProductRepository  productRepository;
    private final UserRepository     userRepository;
    private final CustomerRepository customerRepository;
    private final ExpenseVoucherRepository expenseVoucherRepository;

    private static final ZoneId VN_ZONE    = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_HOUR  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH");
    private static final DateTimeFormatter DATE_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final int MAX_POINTS = 12;

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────
    public DashboardStatsDto getStats(long from, long to) {

        BigDecimal revenuePeriod = orZero(
                orderRepository.sumFinalAmountByStatusAndRange(OrderStatus.COMPLETED, from, to));
        long ordersPeriod    = orderRepository.countByCreatedAtBetween(from, to);
        long completedPeriod = orderRepository.countByStatusAndCreatedAtBetween(
                OrderStatus.COMPLETED, from, to);
        long newCustomers    = customerRepository.countByCreatedAtBetween(from, to);
        long activeOrders    = orderRepository.countByStatusNotInAndCreatedAtBetween(
                List.of(OrderStatus.CANCELLED, OrderStatus.FAILED, OrderStatus.COMPLETED), from, to);

        long totalProducts   = productRepository.countByIsActiveTrueAndCreatedAtBetween(from, to);
        long totalCustomers  = customerRepository.countByCreatedAtBetween(from, to);
        long cancelledPeriod = orderRepository.countByStatusAndCreatedAtBetween(
                OrderStatus.CANCELLED, from, to);

        long len = to - from;
        BigDecimal revenuePrev = orZero(
                orderRepository.sumFinalAmountByStatusAndRange(OrderStatus.COMPLETED, from - len, from));
        long ordersPrev = orderRepository.countByCreatedAtBetween(from - len, from);

        List<RevenuePointDto>      revenuePoints    = buildRevenuePoints(from, to);
        List<StatusCountDto>       ordersByStatus   = orderRepository.countOrdersByStatusAndRange(from, to);
        List<PaymentMethodStatDto> paymentBreakdown = orderRepository.revenueByPaymentMethod(from, to);

        BigDecimal totalExpenses   = expenseVoucherRepository.sumApprovedExpenses(from, to, ExpenseVoucher.VoucherStatus.APPROVED);
        BigDecimal totalPaidAmount = orZero(orderRepository.sumPaidAmountByRange(from, to)); // ← THÊM

        return DashboardStatsDto.builder()
                .revenueToday(revenuePeriod)
                .ordersToday(ordersPeriod)
                .newCustomersToday(newCustomers)
                .totalActiveOrders(activeOrders)
                .totalProducts(totalProducts)
                .totalCustomers(totalCustomers)
                .completedOrdersAllTime(completedPeriod)
                .cancelledOrdersAllTime(cancelledPeriod)
                .revenueYesterday(revenuePrev)
                .revenueChangePercent(percentChange(revenuePrev, revenuePeriod))
                .ordersYesterday(ordersPrev)
                .ordersChangePercent(percentChange(
                        BigDecimal.valueOf(ordersPrev), BigDecimal.valueOf(ordersPeriod)))
                .revenueLast30Days(revenuePoints)
                .ordersByStatus(ordersByStatus)
                .revenueByPaymentMethod(paymentBreakdown)
                .totalExpenses(totalExpenses)
                .totalPaidAmount(totalPaidAmount)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Revenue chart — tối đa MAX_POINTS (12) điểm
    //
    // Logic chọn granularity:
    //   diffHours <= 24  → nhóm theo N giờ  (24h / 12 = mỗi 2h)
    //   diffDays  <= 366 → nhóm theo N ngày (ceil(days / 12))
    //   còn lại          → nhóm theo N tháng
    //
    // Sau khi lấy raw data (theo giờ/ngày/tháng), gom lại thành đúng 12 bucket.
    // ─────────────────────────────────────────────────────────────────────────
    private List<RevenuePointDto> buildRevenuePoints(long from, long to) {
        long diffMillis = to - from;
        long diffHours  = diffMillis / 3_600_000L;
        long diffDays   = diffMillis / 86_400_000L;

        if (diffHours <= 24) {
            return buildHourlyPoints(from, to, diffHours);
        } else if (diffDays <= 366) {
            return buildDailyPoints(from, to, diffDays);
        } else {
            return buildMonthlyPoints(from, to);
        }
    }

    // ── Theo giờ → gom thành MAX_POINTS bucket ───────────────────────────────
    private List<RevenuePointDto> buildHourlyPoints(long from, long to, long diffHours) {
        // Lấy toàn bộ raw data theo từng giờ
        List<Object[]> rows = orderRepository.findRevenueByHourNative(from, to);

        // Tổng số giờ trong khoảng (tối thiểu 1)
        int totalHours = (int) Math.max(1, diffHours + 1);
        // Số giờ mỗi bucket: ceil(totalHours / MAX_POINTS), tối thiểu 1
        int hoursPerBucket = (int) Math.max(1, Math.ceil((double) totalHours / MAX_POINTS));

        // Map raw data: key = "yyyy-MM-dd HH" → revenue/count
        Map<String, long[]> rawMap = new LinkedHashMap<>(); // long[]{revenue_cents, count}
        for (Object[] r : rows) {
            String key = (String) r[0]; // "2026-04-24 15"
            long rev   = toBigDecimal(r[1]).multiply(BigDecimal.valueOf(100)).longValue();
            long cnt   = toLong(r[2]);
            rawMap.put(key, new long[]{rev, cnt});
        }

        // Tạo danh sách giờ từ from → to
        LocalDateTime start = Instant.ofEpochMilli(from)
                .atZone(VN_ZONE).toLocalDateTime().withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end   = Instant.ofEpochMilli(to).atZone(VN_ZONE).toLocalDateTime();

        // Thu thập tất cả các giờ
        List<LocalDateTime> hours = new ArrayList<>();
        LocalDateTime cur = start;
        while (!cur.isAfter(end)) {
            hours.add(cur);
            cur = cur.plusHours(1);
        }

        // Gom thành bucket
        return groupIntoBuckets(hours, hoursPerBucket, rawMap,
                dt -> dt.format(DATE_HOUR),
                (first, last) -> {
                    if (first.toLocalDate().equals(last.toLocalDate())) {
                        // Cùng ngày: "10h-12h"
                        return first.getHour() + "h-" + last.getHour() + "h";
                    } else {
                        // Qua ngày: "24/04 10h"
                        return first.format(DateTimeFormatter.ofPattern("dd/MM")) + " " + first.getHour() + "h";
                    }
                });
    }

    // ── Theo ngày → gom thành MAX_POINTS bucket ──────────────────────────────
    private List<RevenuePointDto> buildDailyPoints(long from, long to, long diffDays) {
        List<Object[]> rows = orderRepository.findRevenueByDayNative(from, to);

        int totalDays      = (int) Math.max(1, diffDays + 1);
        int daysPerBucket  = (int) Math.max(1, Math.ceil((double) totalDays / MAX_POINTS));

        Map<String, long[]> rawMap = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = (String) r[0]; // "2026-04-24"
            long rev   = toBigDecimal(r[1]).multiply(BigDecimal.valueOf(100)).longValue();
            long cnt   = toLong(r[2]);
            rawMap.put(key, new long[]{rev, cnt});
        }

        LocalDate startDate = Instant.ofEpochMilli(from).atZone(VN_ZONE).toLocalDate();
        LocalDate endDate   = Instant.ofEpochMilli(to).atZone(VN_ZONE).toLocalDate();

        List<LocalDate> days = new ArrayList<>();
        LocalDate d = startDate;
        while (!d.isAfter(endDate)) {
            days.add(d);
            d = d.plusDays(1);
        }

        return groupIntoBuckets(days, daysPerBucket, rawMap,
                date -> date.format(DATE_FMT),
                (first, last) -> {
                    if (first.equals(last)) {
                        // 1 ngày: "24/04"
                        return first.format(DateTimeFormatter.ofPattern("dd/MM"));
                    } else if (first.getMonth() == last.getMonth()) {
                        // Cùng tháng: "20-24/04"
                        return first.getDayOfMonth() + "-" + last.format(DateTimeFormatter.ofPattern("dd/MM"));
                    } else {
                        // Khác tháng: "28/04-03/05"
                        return first.format(DateTimeFormatter.ofPattern("dd/MM")) + "-"
                                + last.format(DateTimeFormatter.ofPattern("dd/MM"));
                    }
                });
    }

    // ── Theo tháng → gom thành MAX_POINTS bucket ─────────────────────────────
    private List<RevenuePointDto> buildMonthlyPoints(long from, long to) {
        List<Object[]> rows = orderRepository.findRevenueByMonthNative(from, to);

        Map<String, long[]> rawMap = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = (String) r[0]; // "2026-04"
            long rev   = toBigDecimal(r[1]).multiply(BigDecimal.valueOf(100)).longValue();
            long cnt   = toLong(r[2]);
            rawMap.put(key, new long[]{rev, cnt});
        }

        YearMonth startMonth = YearMonth.from(Instant.ofEpochMilli(from).atZone(VN_ZONE).toLocalDate());
        YearMonth endMonth   = YearMonth.from(Instant.ofEpochMilli(to).atZone(VN_ZONE).toLocalDate());

        List<YearMonth> months = new ArrayList<>();
        YearMonth m = startMonth;
        while (!m.isAfter(endMonth)) {
            months.add(m);
            m = m.plusMonths(1);
        }

        int totalMonths       = months.size();
        int monthsPerBucket   = (int) Math.max(1, Math.ceil((double) totalMonths / MAX_POINTS));

        return groupIntoBuckets(months, monthsPerBucket, rawMap,
                ym -> ym.format(DATE_MONTH),
                (first, last) -> {
                    if (first.equals(last)) {
                        return first.format(DateTimeFormatter.ofPattern("MM/yyyy"));
                    } else {
                        return first.format(DateTimeFormatter.ofPattern("MM/yy")) + "-"
                                + last.format(DateTimeFormatter.ofPattern("MM/yy"));
                    }
                });
    }

    // ── Generic bucket grouper ────────────────────────────────────────────────
    // T = LocalDateTime | LocalDate | YearMonth
    // keyFn: T → DB key string để lookup rawMap
    // labelFn: (first T, last T) → label hiển thị trên chart
    private <T> List<RevenuePointDto> groupIntoBuckets(
            List<T> items,
            int perBucket,
            Map<String, long[]> rawMap,
            java.util.function.Function<T, String> keyFn,
            java.util.function.BiFunction<T, T, String> labelFn) {

        List<RevenuePointDto> result = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            int end = Math.min(i + perBucket, items.size());
            List<T> bucket = items.subList(i, end);

            BigDecimal bucketRevenue = BigDecimal.ZERO;
            long bucketCount = 0;

            for (T item : bucket) {
                String key = keyFn.apply(item);
                long[] raw = rawMap.get(key);
                if (raw != null) {
                    bucketRevenue = bucketRevenue.add(
                            BigDecimal.valueOf(raw[0]).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                    bucketCount += raw[1];
                }
            }

            String label = labelFn.apply(bucket.get(0), bucket.get(bucket.size() - 1));
            result.add(RevenuePointDto.builder()
                    .date(label)
                    .revenue(bucketRevenue)
                    .orderCount(bucketCount)
                    .build());
            i = end;
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top products / sellers / customers
    // ─────────────────────────────────────────────────────────────────────────
    public List<TopProductDto> getTopProducts(int limit, long from, long to, String sortBy) {
        List<Object[]> rows = switch (sortBy.toLowerCase()) {
            case "quantity" -> orderRepository.findTopProductsByQuantityNative(from, to, limit);
            case "orders"   -> orderRepository.findTopProductsByOrdersNative(from, to, limit);
            default         -> orderRepository.findTopProductsNative(from, to, limit);
        };
        return rows.stream().map(r -> TopProductDto.builder()
                .productId(toLong(r[0])).productName((String) r[1])
                .productImageUrl((String) r[2]).unit((String) r[3])
                .totalQuantitySold(toBigDecimal(r[4])).totalOrders(toLong(r[5]))
                .totalRevenue(toBigDecimal(r[6])).build()).toList();
    }

    public List<TopSellerDto> getTopSellers(int limit, long from, long to, String sortBy) {
        List<Object[]> rows = "orders".equalsIgnoreCase(sortBy)
                ? orderRepository.findTopSellersByOrdersNative(from, to, limit)
                : orderRepository.findTopSellersNative(from, to, limit);
        return rows.stream().map(r -> TopSellerDto.builder()
                .userId(toLong(r[0])).username((String) r[1]).fullName((String) r[2])
                .totalCompletedOrders(toLong(r[3])).totalRevenue(toBigDecimal(r[4])).build()).toList();
    }

    public List<TopCustomerDto> getTopCustomers(int limit, long from, long to) {
        List<Object[]> rows = orderRepository.findTopCustomersNative(from, to, limit);
        return rows.stream().map(r -> TopCustomerDto.builder()
                .customerId(toLong(r[0])).customerName((String) r[1])
                .totalOrders(toLong(r[2])).completedOrders(toLong(r[3]))
                .totalSpent(toBigDecimal(r[4])).build()).toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private Double percentChange(BigDecimal prev, BigDecimal curr) {
        if (prev == null || prev.compareTo(BigDecimal.ZERO) == 0)
            return curr != null && curr.compareTo(BigDecimal.ZERO) > 0 ? 100.0 : 0.0;
        return curr.subtract(prev).divide(prev, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal orZero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

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