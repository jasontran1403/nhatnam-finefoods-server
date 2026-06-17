package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * KPI Phòng Sale — dành cho ADMIN và OWNER.
 * GET /api/admin/sale-kpi?from=<ms>&to=<ms>
 *
 * Quy tắc kpiUserId trên Order:
 *   -1  = không tính KPI cho ai  → bỏ qua hoàn toàn
 *    0  = tính cho phòng, không tính riêng cá nhân
 *   >0  = tính riêng cho user đó VÀ tính cho phòng
 */
@RestController
@RequestMapping("/api/admin/sale-kpi")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('ADMIN','OWNER')")
public class SaleKpiController {

    private final OrderRepository          orderRepository;
    private final CustomerRepository       customerRepository;
    private final UserRepository           userRepository;
    private final ExpenseVoucherRepository expenseVoucherRepository;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKpi(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        try {
            // ── Default: tháng hiện tại ──────────────────────────────────
            if (from == null || to == null) {
                YearMonth ym = YearMonth.now(VN);
                from = ym.atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
                to   = ym.atEndOfMonth().atTime(23, 59, 59).atZone(VN).toInstant().toEpochMilli();
            }
            final long startMs = from;
            final long endMs   = to;

            var allOrders = orderRepository.findByCreatedAtBetween(startMs, endMs);

            // ═══════════════════════════════════════════════════════════════
            // CARDS — thống kê tổng hợp toàn phòng
            // Bao gồm tất cả đơn có kpiUserId != -1
            // ═══════════════════════════════════════════════════════════════

            // Đơn được tính vào phòng (kpiUserId != -1)
            var kpiOrders = allOrders.stream()
                    .filter(o -> o.getKpiUserId() != null && o.getKpiUserId() != -1L)
                    .collect(Collectors.toList());

            long totalOrders = kpiOrders.size();

            BigDecimal revenue = kpiOrders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
                    .map(o -> nvl(o.getFinalAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal profit = kpiOrders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
                    .flatMap(o -> o.getOrderItems().stream())
                    .map(item -> nvl(item.getSubtotal()).subtract(nvl(item.getCostPrice())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(0, RoundingMode.HALF_UP);

            BigDecimal expenses = expenseVoucherRepository.sumApprovedInPeriod(
                    startMs, endMs,
                    com.nhatnam.server.entity.ExpenseVoucher.VoucherStatus.APPROVED);
            if (expenses == null) expenses = BigDecimal.ZERO;

            BigDecimal collected = kpiOrders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.COMPLETED
                            && o.getPaymentStatus() == PaymentStatus.PAID)
                    .map(o -> nvl(o.getFinalAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal debt = kpiOrders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.COMPLETED
                            && (o.getPaymentStatus() == PaymentStatus.UNPAID
                            || o.getPaymentStatus() == PaymentStatus.PARTIAL))
                    .map(o -> nvl(o.getFinalAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal processing = kpiOrders.stream()
                    .filter(o -> o.getStatus() != OrderStatus.COMPLETED
                            && o.getStatus() != OrderStatus.CANCELLED
                            && o.getStatus() != OrderStatus.FAILED
                            && o.getPaymentStatus() == PaymentStatus.UNPAID)
                    .map(o -> nvl(o.getFinalAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Khách mới / khách cũ (tính trên toàn bộ kpiOrders)
            Set<Long> customerIds = kpiOrders.stream()
                    .filter(o -> o.getCustomer() != null)
                    .map(o -> o.getCustomer().getId())
                    .collect(Collectors.toSet());

            long newCustomers = 0, returnCustomers = 0;
            for (Long cid : customerIds) {
                Long first = orderRepository.findFirstOrderTimeByCustomerId(cid);
                if (first != null && first >= startMs && first <= endMs) newCustomers++;
                else if (first != null && first < startMs) returnCustomers++;
            }

            // ═══════════════════════════════════════════════════════════════
            // SELLERS TABLE
            //
            // Chỉ lấy đơn có kpiUserId > 0 (tính riêng về 1 user).
            // Đơn kpiUserId = 0 tính vào phòng (cards) nhưng không vào bảng sellers.
            // Đơn kpiUserId = -1 bỏ qua hoàn toàn.
            //
            // Group theo kpiUserId, tra tên user từ DB.
            // ═══════════════════════════════════════════════════════════════

            // Lấy trước tên các user có kpiUserId > 0 để tránh N+1
            Set<Long> sellerIds = allOrders.stream()
                    .filter(o -> o.getKpiUserId() != null && o.getKpiUserId() > 0L)
                    .map(o -> o.getKpiUserId())
                    .collect(Collectors.toSet());

            Map<Long, String> sellerNameMap = new HashMap<>();
            if (!sellerIds.isEmpty()) {
                userRepository.findAllById(sellerIds).forEach(u -> {
                    String name = (u.getFullName() != null && !u.getFullName().isBlank())
                            ? u.getFullName() : u.getUsername();
                    sellerNameMap.put(u.getId(), name);
                });
            }

            Map<Long, Map<String, Object>> sellerMap = new LinkedHashMap<>();

            for (var order : allOrders) {
                Long kpiUid = order.getKpiUserId();

                // Chỉ xử lý đơn tính riêng cá nhân (kpiUserId > 0)
                if (kpiUid == null || kpiUid <= 0L) continue;

                String uname = sellerNameMap.getOrDefault(kpiUid, "User #" + kpiUid);

                Map<String, Object> row = sellerMap.computeIfAbsent(kpiUid, k -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("sellerId",    kpiUid);
                    r.put("sellerName",  uname);
                    r.put("revenue",     BigDecimal.ZERO);
                    r.put("collected",   BigDecimal.ZERO);
                    r.put("debt",        BigDecimal.ZERO);
                    r.put("inProgress",  BigDecimal.ZERO);
                    r.put("uncollected", BigDecimal.ZERO);
                    return r;
                });

                BigDecimal amount = nvl(order.getFinalAmount());
                OrderStatus   st  = order.getStatus();
                PaymentStatus ps  = order.getPaymentStatus();

                if (st == OrderStatus.COMPLETED) {
                    row.put("revenue",   add(row, "revenue",   amount));
                    if (ps == PaymentStatus.PAID) {
                        row.put("collected", add(row, "collected", amount));
                    } else if (ps == PaymentStatus.UNPAID || ps == PaymentStatus.PARTIAL) {
                        row.put("debt",      add(row, "debt",      amount));
                    }
                } else if (st != OrderStatus.CANCELLED && st != OrderStatus.FAILED) {
                    row.put("inProgress",  add(row, "inProgress",  amount));
                    if (ps == PaymentStatus.UNPAID) {
                        row.put("uncollected", add(row, "uncollected", amount));
                    }
                }
            }

            List<Map<String, Object>> sellers = sellerMap.values().stream()
                    .peek(row -> {
                        for (String k : List.of("revenue", "collected", "debt", "inProgress", "uncollected"))
                            row.put(k, ((BigDecimal) row.get(k)).setScale(0, RoundingMode.HALF_UP));
                    })
                    .sorted(Comparator.comparing(
                            (Map<String, Object> r) -> (BigDecimal) r.get("revenue")
                    ).reversed())
                    .collect(Collectors.toList());

            // ── Response ──────────────────────────────────────────────────
            Map<String, Object> result = new LinkedHashMap<>();

            Map<String, Object> cards = new LinkedHashMap<>();
            cards.put("totalOrders",     totalOrders);
            cards.put("revenue",         revenue.setScale(0, RoundingMode.HALF_UP));
            cards.put("profit",          profit.setScale(0, RoundingMode.HALF_UP));
            cards.put("expenses",        expenses.setScale(0, RoundingMode.HALF_UP));
            cards.put("newCustomers",    newCustomers);
            cards.put("returnCustomers", returnCustomers);
            cards.put("collected",       collected.setScale(0, RoundingMode.HALF_UP));
            cards.put("debt",            debt.setScale(0, RoundingMode.HALF_UP));
            cards.put("processing",      processing.setScale(0, RoundingMode.HALF_UP));
            result.put("cards",   cards);
            result.put("sellers", sellers);

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));

        } catch (Exception e) {
            log.error("getKpi error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private BigDecimal nvl(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    @SuppressWarnings("unchecked")
    private BigDecimal add(Map<String, Object> row, String key, BigDecimal val) {
        return ((BigDecimal) row.get(key)).add(val);
    }
}