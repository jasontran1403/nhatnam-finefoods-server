package com.nhatnam.server.service;

import com.nhatnam.server.dto.response.SellerDashboardDTO;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class SellerDashboardService {

    private final OrderRepository orderRepository;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Các trạng thái được tính vào dashboard (loại trừ CANCELLED).
     */
    private static boolean isActive(com.nhatnam.server.entity.Order o) {
        return o.getStatus() != OrderStatus.CANCELLED;
    }

    private static boolean isCompleted(com.nhatnam.server.entity.Order o) {
        return o.getStatus() == OrderStatus.COMPLETED;
    }

    private static boolean isPending(com.nhatnam.server.entity.Order o) {
        return o.getStatus() == OrderStatus.PREPARING
                || o.getStatus() == OrderStatus.DELIVERING
                || o.getStatus() == OrderStatus.PENDING_PAYMENT;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getCustomerStats(Long sellerId, long from, long to) {
        List<Object[]> rows = orderRepository.getCustomerStats(sellerId, from, to);
        Object[] row = (rows != null && !rows.isEmpty()) ? rows.get(0) : new Object[]{0, 0, 0};
        long total     = row[0] != null ? ((Number) row[0]).longValue() : 0L;
        long newC      = row[1] != null ? ((Number) row[1]).longValue() : 0L;
        long returning = row[2] != null ? ((Number) row[2]).longValue() : 0L;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCustomers",     total);
        result.put("newCustomers",       newC);
        result.put("returningCustomers", returning);
        return result;
    }

    public SellerDashboardDTO.DashboardResponse getDashboard(
            Long sellerId, long from, long to, String groupBy, int topN) {

        // 1 lần query toàn bộ đơn của seller trong kỳ — JOIN FETCH tránh N+1
        var orders = orderRepository.findByUserIdAndCreatedAtBetween(sellerId, from, to);

        return SellerDashboardDTO.DashboardResponse.builder()
                .summary(buildSummary(orders))
                .chart(buildChart(orders, from, to, groupBy))
                .topProducts(buildTopProducts(orders, topN))
                .topCustomers(buildTopCustomers(orders, topN))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SUMMARY — 4 cards
    // ─────────────────────────────────────────────────────────────────────────
    private SellerDashboardDTO.Summary buildSummary(List<com.nhatnam.server.entity.Order> orders) {

        // ── Card 1: Tổng đơn (không tính CANCELLED) ───────────────────────
        long totalOrders          = orders.stream().filter(SellerDashboardService::isActive).count();
        long preparingOrders      = orders.stream().filter(o -> o.getStatus() == OrderStatus.PREPARING).count();
        long deliveringOrders     = orders.stream().filter(o -> o.getStatus() == OrderStatus.DELIVERING).count();
        long pendingPaymentOrders = orders.stream().filter(o -> o.getStatus() == OrderStatus.PENDING_PAYMENT).count();
        long completedOrders      = orders.stream().filter(SellerDashboardService::isCompleted).count();

        // ── Card 2: Doanh thu ─────────────────────────────────────────────
        // Tổng doanh thu = finalAmount TẤT CẢ đơn active (kể cả PREPARING/DELIVERING/PENDING_PAYMENT)
        BigDecimal totalRevenue = orders.stream()
                .filter(SellerDashboardService::isActive)
                .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Doanh thu đã thu = finalAmount đơn COMPLETED
        BigDecimal collectedRevenue = orders.stream()
                .filter(SellerDashboardService::isCompleted)
                .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Doanh thu chưa thu = PREPARING + DELIVERING + PENDING_PAYMENT
        BigDecimal uncollectedRevenue = orders.stream()
                .filter(SellerDashboardService::isPending)
                .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Card 3 & 4: Placeholder — xử lý sau ──────────────────────────
        BigDecimal nearingDeadlineAmount = new BigDecimal("0");
        BigDecimal overdueAmount         = new BigDecimal("0");

        return SellerDashboardDTO.Summary.builder()
                .totalOrders(totalOrders)
                .preparingOrders(preparingOrders)
                .deliveringOrders(deliveringOrders)
                .pendingPaymentOrders(pendingPaymentOrders)
                .completedOrders(completedOrders)
                .totalRevenue(totalRevenue)
                .collectedRevenue(collectedRevenue)
                .uncollectedRevenue(uncollectedRevenue)
                .nearingDeadlineAmount(nearingDeadlineAmount)
                .overdueAmount(overdueAmount)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHART — chỉ tính đơn active (không CANCELLED)
    // ─────────────────────────────────────────────────────────────────────────
    private List<SellerDashboardDTO.ChartPoint> buildChart(
            List<com.nhatnam.server.entity.Order> orders,
            long from, long to, String groupBy) {

        List<String> buckets = generateBuckets(from, to, groupBy);

        Map<String, List<com.nhatnam.server.entity.Order>> grouped = new LinkedHashMap<>();
        buckets.forEach(b -> grouped.put(b, new ArrayList<>()));

        for (var order : orders) {
            if (!isActive(order)) continue; // bỏ CANCELLED
            if (order.getCreatedAt() == null) continue;
            String key = toBucketKey(order.getCreatedAt(), groupBy);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(order);
        }

        return buckets.stream().map(bucket -> {
            var list = grouped.getOrDefault(bucket, List.of());

            long pendingCount = list.stream().filter(SellerDashboardService::isPending).count();
            long successCount = list.stream().filter(SellerDashboardService::isCompleted).count();

            BigDecimal pendingRevenue = list.stream()
                    .filter(SellerDashboardService::isPending)
                    .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal successRevenue = list.stream()
                    .filter(SellerDashboardService::isCompleted)
                    .map(o -> o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return SellerDashboardDTO.ChartPoint.builder()
                    .label(toBucketLabel(bucket, groupBy))
                    .pendingCount(pendingCount)
                    .successCount(successCount)
                    .pendingRevenue(pendingRevenue)
                    .successRevenue(successRevenue)
                    .build();
        }).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOP PRODUCTS — chỉ tính đơn active (không CANCELLED)
    // ─────────────────────────────────────────────────────────────────────────
    private List<SellerDashboardDTO.TopProduct> buildTopProducts(
            List<com.nhatnam.server.entity.Order> orders, int topN) {

        Map<Long, String[]>   nameAndImg = new LinkedHashMap<>();
        Map<Long, BigDecimal> totalQty   = new LinkedHashMap<>();
        Map<Long, BigDecimal> totalRev   = new LinkedHashMap<>();

        for (var order : orders) {
            if (!isActive(order)) continue; // bỏ CANCELLED
            if (order.getOrderItems() == null) continue;
            for (var item : order.getOrderItems()) {
                Long pid = item.getProductId();
                if (pid == null) continue;
                nameAndImg.putIfAbsent(pid, new String[]{item.getProductName(), item.getProductImageUrl()});
                totalQty.merge(pid,
                        item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO, BigDecimal::add);
                totalRev.merge(pid,
                        item.getSubtotal()  != null ? item.getSubtotal()  : BigDecimal.ZERO, BigDecimal::add);
            }
        }

        return totalRev.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .limit(topN)
                .map(e -> {
                    Long pid      = e.getKey();
                    String[] meta = nameAndImg.getOrDefault(pid, new String[]{"?", null});
                    return SellerDashboardDTO.TopProduct.builder()
                            .productId(pid)
                            .productName(meta[0])
                            .imageUrl(meta[1])
                            .totalQty(totalQty.getOrDefault(pid, BigDecimal.ZERO))
                            .totalRevenue(e.getValue())
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOP CUSTOMERS — chỉ tính đơn active (không CANCELLED)
    // ─────────────────────────────────────────────────────────────────────────
    private List<SellerDashboardDTO.TopCustomer> buildTopCustomers(
            List<com.nhatnam.server.entity.Order> orders, int topN) {

        // counts[0] = tổng đơn active, counts[1] = đơn COMPLETED, counts[2] = đơn đã thu đủ
        Map<String, long[]>     counts    = new LinkedHashMap<>();
        Map<String, BigDecimal> spent     = new LinkedHashMap<>();
        Map<String, BigDecimal> collected = new LinkedHashMap<>();
        Map<String, String[]>   meta      = new LinkedHashMap<>(); // [name, phone, customerId]

        for (var order : orders) {
            if (!isActive(order)) continue; // bỏ CANCELLED

            String key = order.getCustomer() != null
                    ? "C_" + order.getCustomer().getId()
                    : "N_" + (order.getCustomerName() != null ? order.getCustomerName() : "Khách lẻ");

            meta.putIfAbsent(key, new String[]{
                    order.getCustomerName() != null ? order.getCustomerName() : "Khách lẻ",
                    order.getCustomerPhone(),
                    order.getCustomer() != null ? order.getCustomer().getId().toString() : null
            });

            counts.computeIfAbsent(key, k -> new long[3]);
            counts.get(key)[0]++; // total active
            if (isCompleted(order)) counts.get(key)[1]++; // completed
            if (order.getPaymentStatus() == com.nhatnam.server.enumtype.PaymentStatus.PAID)
                counts.get(key)[2]++; // đã thanh toán đủ

            // Chi tiêu = finalAmount tất cả đơn active (không chỉ COMPLETED)
            spent.merge(key,
                    order.getFinalAmount() != null ? order.getFinalAmount() : BigDecimal.ZERO,
                    BigDecimal::add);

            // Đã thu = paidAmount thực tế. Đơn thu một phần cộng đúng phần đã thu;
            // suy từ trạng thái (PAID → cộng đủ, còn lại → 0) sẽ làm mất các
            // khoản thu dở dang, vốn chiếm phần lớn ở khách mua công nợ.
            collected.merge(key,
                    order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO,
                    BigDecimal::add);
        }

        return spent.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(topN)
                .map(e -> {
                    String   key = e.getKey();
                    String[] m   = meta.getOrDefault(key, new String[]{"?", null, null});
                    long[]   cnt = counts.getOrDefault(key, new long[3]);
                    Long     cid = m[2] != null ? Long.parseLong(m[2]) : null;
                    return SellerDashboardDTO.TopCustomer.builder()
                            .customerId(cid)
                            .customerName(m[0])
                            .customerPhone(m[1])
                            .totalOrders(cnt[0])
                            .completedOrders(cnt[1])
                            .paidOrders(cnt[2])
                            .totalSpent(e.getValue())
                            .collectedAmount(collected.getOrDefault(key, BigDecimal.ZERO))
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BUCKET HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private String toBucketKey(long epochMs, String groupBy) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(VN);
        return switch (groupBy.toUpperCase()) {
            case "HOUR"  -> zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH"));
            case "MONTH" -> zdt.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            default      -> zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        };
    }

    private String toBucketLabel(String bucketKey, String groupBy) {
        return switch (groupBy.toUpperCase()) {
            case "HOUR" -> {
                String[] p = bucketKey.split(" ");
                yield p.length > 1 ? p[1] + ":00" : bucketKey;
            }
            case "MONTH" -> {
                String[] p = bucketKey.split("-");
                yield p.length > 1 ? "T" + p[1] + "/" + p[0] : bucketKey;
            }
            default -> {
                String[] p = bucketKey.split("-");
                yield p.length == 3 ? p[2] + "/" + p[1] : bucketKey;
            }
        };
    }

    private List<String> generateBuckets(long from, long to, String groupBy) {
        List<String> buckets = new ArrayList<>();
        ZonedDateTime cur = Instant.ofEpochMilli(from).atZone(VN);
        ZonedDateTime end = Instant.ofEpochMilli(to).atZone(VN);

        switch (groupBy.toUpperCase()) {
            case "HOUR" -> {
                cur = cur.withMinute(0).withSecond(0).withNano(0);
                while (!cur.isAfter(end)) {
                    buckets.add(cur.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH")));
                    cur = cur.plusHours(1);
                }
            }
            case "MONTH" -> {
                cur = cur.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
                while (!cur.isAfter(end)) {
                    buckets.add(cur.format(DateTimeFormatter.ofPattern("yyyy-MM")));
                    cur = cur.plusMonths(1);
                }
            }
            default -> {
                cur = cur.withHour(0).withMinute(0).withSecond(0).withNano(0);
                while (!cur.isAfter(end)) {
                    buckets.add(cur.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                    cur = cur.plusDays(1);
                }
            }
        }
        return buckets;
    }
}