package com.nhatnam.server.service;

import com.nhatnam.server.dto.analytics.AnalyticsDtos;
import com.nhatnam.server.dto.analytics.AnalyticsDtos.*;
import com.nhatnam.server.entity.ExpenseVoucher;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.entity.ProductIngredient;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BusinessAnalyticsService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

    private final OrderRepository              orderRepository;
    private final IncomeVoucherRepository      incomeVoucherRepository;
    private final ExpenseVoucherRepository     expenseVoucherRepository;
    private final ProductIngredientRepository  productIngredientRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    public BusinessAnalyticsResponse getAnalytics(AnalyticsPeriod period) {
        LocalDate today = LocalDate.now(VN);
        List<long[]> periods = buildPeriods(period, today, 8);

        List<DataPoint> historical = new ArrayList<>();
        for (long[] p : periods) {
            historical.add(buildDataPoint(period, p[0], p[1]));
        }

        DataPoint current  = historical.isEmpty() ? null : historical.get(historical.size() - 1);
        DataPoint previous = historical.size() >= 2 ? historical.get(historical.size() - 2) : null;

        ForecastDto forecast = buildForecast(period, today, historical);

        long[] nextPeriod = buildNextPeriod(period, today);
        List<IngredientForecastDto> ingredientForecast = buildIngredientForecast(periods);

        return BusinessAnalyticsResponse.builder()
                .period(period)
                .historical(historical)
                .forecast(forecast)
                .ingredientForecast(ingredientForecast)
                .currentRevenue(current != null ? current.getRevenue() : BigDecimal.ZERO)
                .currentIncome(current != null ? current.getIncome() : BigDecimal.ZERO)
                .currentExpense(current != null ? current.getExpense() : BigDecimal.ZERO)
                .currentProfit(current != null ? current.getProfit() : BigDecimal.ZERO)
                .currentOrderCount(current != null ? current.getOrderCount() : 0L)
                .revenuePctChange(calcPctChange(
                        previous != null ? previous.getRevenue() : null,
                        current  != null ? current.getRevenue()  : null))
                .profitPctChange(calcPctChange(
                        previous != null ? previous.getProfit() : null,
                        current  != null ? current.getProfit()  : null))
                .orderCountPctChange(calcPctChange(
                        previous != null ? BigDecimal.valueOf(previous.getOrderCount()) : null,
                        current  != null ? BigDecimal.valueOf(current.getOrderCount())  : null))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Period helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<long[]> buildPeriods(AnalyticsPeriod period, LocalDate today, int count) {
        List<long[]> result = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            result.add(periodRange(period, today, -i));
        }
        return result;
    }

    private long[] periodRange(AnalyticsPeriod period, LocalDate today, int offset) {
        return switch (period) {
            case WEEK -> {
                LocalDate monday = today.with(WeekFields.ISO.dayOfWeek(), 1).plusWeeks(offset);
                yield toRange(monday, monday.plusDays(6));
            }
            case MONTH -> {
                LocalDate first = today.withDayOfMonth(1).plusMonths(offset);
                yield toRange(first, first.withDayOfMonth(first.lengthOfMonth()));
            }
            case QUARTER -> {
                int q    = ((today.getMonthValue() - 1) / 3) + 1 + offset;
                int year = today.getYear();
                while (q < 1) { q += 4; year--; }
                while (q > 4) { q -= 4; year++; }
                LocalDate first = LocalDate.of(year, (q - 1) * 3 + 1, 1);
                yield toRange(first, first.plusMonths(3).minusDays(1));
            }
            case YEAR -> {
                LocalDate first = LocalDate.of(today.getYear() + offset, 1, 1);
                yield toRange(first, first.withDayOfYear(first.lengthOfYear()));
            }
        };
    }

    private long[] buildNextPeriod(AnalyticsPeriod period, LocalDate today) {
        return periodRange(period, today, +1);
    }

    private long[] toRange(LocalDate from, LocalDate to) {
        long start = from.atStartOfDay(VN).toInstant().toEpochMilli();
        long end   = to.atTime(23, 59, 59, 999_000_000).atZone(VN).toInstant().toEpochMilli();
        return new long[]{start, end};
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data fetching
    // ─────────────────────────────────────────────────────────────────────────

    private DataPoint buildDataPoint(AnalyticsPeriod period, long from, long to) {
        BigDecimal revenue    = orderRepository.sumPaidAmountByRange(from, to);
        BigDecimal income     = sumIncome(from, to);
        BigDecimal expense    = sumExpense(from, to);
        BigDecimal profit     = revenue.add(income).subtract(expense);
        long       orderCount = orderRepository.countByStatusAndCreatedAtBetween(
                OrderStatus.COMPLETED, from, to);

        return DataPoint.builder()
                .label(buildLabel(period, from))
                .fromMs(from).toMs(to)
                .revenue(revenue).income(income).expense(expense)
                .profit(profit).orderCount(orderCount)
                .build();
    }

    private BigDecimal sumIncome(long from, long to) {
        try {
            return incomeVoucherRepository
                    .findAllByOrderByCreatedAtDesc(org.springframework.data.domain.Pageable.unpaged())
                    .stream()
                    .filter(v -> v.getCreatedAt() >= from && v.getCreatedAt() <= to)
                    .flatMap(v -> v.getItems().stream())
                    .map(i -> i.getAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private BigDecimal sumExpense(long from, long to) {
        try {
            return expenseVoucherRepository.sumApprovedInPeriod(
                    from, to, ExpenseVoucher.VoucherStatus.APPROVED);
        } catch (Exception e) { return BigDecimal.ZERO; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Forecasting: Weighted Moving Average + Linear Trend
    // ─────────────────────────────────────────────────────────────────────────

    private ForecastDto buildForecast(AnalyticsPeriod period, LocalDate today, List<DataPoint> historical) {
        long[] next = buildNextPeriod(period, today);
        int n = historical.size();
        if (n == 0) return emptyForecast(period, next);

        BigDecimal forecastRevenue = wma(historical.stream().map(DataPoint::getRevenue).collect(Collectors.toList()));
        BigDecimal forecastIncome  = wma(historical.stream().map(DataPoint::getIncome).collect(Collectors.toList()));
        BigDecimal forecastExpense = wma(historical.stream().map(DataPoint::getExpense).collect(Collectors.toList()));
        long       forecastOrders  = wmaLong(historical.stream().map(DataPoint::getOrderCount).collect(Collectors.toList()));

        double trendFactor = calcTrendFactor(historical.stream()
                .map(p -> p.getRevenue().doubleValue()).collect(Collectors.toList()));

        forecastRevenue = forecastRevenue
                .multiply(BigDecimal.valueOf(1.0 + trendFactor), MC)
                .setScale(0, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO);
        BigDecimal forecastProfit = forecastRevenue.add(forecastIncome).subtract(forecastExpense);

        double confidence = Math.min(0.95, 0.5 + (n * 0.06));

        return ForecastDto.builder()
                .periodLabel(buildNextLabel(period))
                .fromMs(next[0]).toMs(next[1])
                .forecastRevenue(forecastRevenue)
                .forecastIncome(forecastIncome.max(BigDecimal.ZERO))
                .forecastExpense(forecastExpense.max(BigDecimal.ZERO))
                .forecastProfit(forecastProfit)
                .forecastOrderCount(Math.max(0, forecastOrders))
                .method("Weighted Moving Average + Linear Trend")
                .confidence(Math.round(confidence * 100.0) / 100.0)
                .build();
    }

    private BigDecimal wma(List<BigDecimal> values) {
        int n = values.size();
        if (n == 0) return BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO, total = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal w = BigDecimal.valueOf(i + 1);
            total     = total.add(values.get(i).multiply(w));
            weightSum = weightSum.add(w);
        }
        return total.divide(weightSum, 0, RoundingMode.HALF_UP);
    }

    private long wmaLong(List<Long> values) {
        return wma(values.stream().map(BigDecimal::valueOf).collect(Collectors.toList())).longValue();
    }

    private double calcTrendFactor(List<Double> values) {
        int n = values.size();
        if (n < 2) return 0.0;
        List<Double> recent = values.subList(Math.max(0, n - 4), n);
        int m = recent.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < m; i++) {
            sumX  += i;
            sumY  += recent.get(i);
            sumXY += i * recent.get(i);
            sumX2 += (double) i * i;
        }
        double denom = m * sumX2 - sumX * sumX;
        if (Math.abs(denom) < 1e-9 || sumY < 1) return 0.0;
        double slope  = (m * sumXY - sumX * sumY) / denom;
        double mean   = sumY / m;
        double factor = slope / mean;
        return Math.max(-0.30, Math.min(0.30, factor));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ingredient forecast
    // ─────────────────────────────────────────────────────────────────────────

    private List<IngredientForecastDto> buildIngredientForecast(List<long[]> periods) {
        Map<Long, List<BigDecimal>> ingredientHistory = new LinkedHashMap<>();
        Map<Long, String> ingredientNames = new LinkedHashMap<>();
        Map<Long, String> ingredientUnits = new LinkedHashMap<>();

        for (long[] p : periods) {
            calcIngredientQtyForPeriod(p[0], p[1]).forEach((ingId, qty) ->
                    ingredientHistory.computeIfAbsent(ingId, k -> new ArrayList<>()).add(qty));
        }

        productIngredientRepository.findAll().forEach(pi -> {
            if (pi.getIngredientId() != null) {
                ingredientNames.put(pi.getIngredientId(), pi.getIngredientNameSnapshot());
                ingredientUnits.put(pi.getIngredientId(), pi.getIngredientUnitSnapshot());
            }
        });

        List<IngredientForecastDto> result = new ArrayList<>();
        ingredientHistory.forEach((ingId, history) -> {
            BigDecimal avg   = wma(history);
            double trend = calcTrendFactor(history.stream().map(BigDecimal::doubleValue).collect(Collectors.toList()));
            BigDecimal forecast = avg.multiply(BigDecimal.valueOf(1.0 + trend), MC)
                    .setScale(3, RoundingMode.HALF_UP).max(BigDecimal.ZERO);

            result.add(IngredientForecastDto.builder()
                    .ingredientId(ingId)
                    .ingredientName(ingredientNames.getOrDefault(ingId, "Không rõ"))
                    .unit(ingredientUnits.getOrDefault(ingId, ""))
                    .forecastQty(forecast)
                    .avgQtyPerPeriod(avg)
                    .trendFactor(BigDecimal.valueOf(trend).setScale(4, RoundingMode.HALF_UP))
                    .build());
        });

        result.sort((a, b) -> b.getForecastQty().compareTo(a.getForecastQty()));
        return result;
    }

    private Map<Long, BigDecimal> calcIngredientQtyForPeriod(long from, long to) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        try {
            List<Order> orders = orderRepository.findByStatusAndCreatedAtBetween(
                    OrderStatus.COMPLETED, from, to);
            for (Order order : orders) {
                if (order.getOrderItems() == null) continue;
                for (OrderItem item : order.getOrderItems()) {
                    List<ProductIngredient> pis = productIngredientRepository.findByProductId(item.getProductId());
                    for (ProductIngredient pi : pis) {
                        if (pi.getIngredientId() == null) continue;
                        BigDecimal qty = pi.getQty().multiply(item.getQuantity(), MC);
                        result.merge(pi.getIngredientId(), qty, BigDecimal::add);
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Label helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildLabel(AnalyticsPeriod period, long fromMs) {
        LocalDate d = Instant.ofEpochMilli(fromMs).atZone(VN).toLocalDate();
        return switch (period) {
            case WEEK    -> "T" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case MONTH   -> "T" + d.getMonthValue() + "/" + d.getYear();
            case QUARTER -> "Q" + ((d.getMonthValue() - 1) / 3 + 1) + "/" + d.getYear();
            case YEAR    -> String.valueOf(d.getYear());
        };
    }

    private String buildNextLabel(AnalyticsPeriod period) {
        return switch (period) {
            case WEEK    -> "Tuần tới";
            case MONTH   -> "Tháng sau";
            case QUARTER -> "Quý sau";
            case YEAR    -> "Năm sau";
        };
    }

    private Double calcPctChange(BigDecimal prev, BigDecimal curr) {
        if (prev == null || curr == null) return null;
        if (prev.compareTo(BigDecimal.ZERO) == 0) return curr.compareTo(BigDecimal.ZERO) == 0 ? 0.0 : null;
        return curr.subtract(prev).divide(prev.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private ForecastDto emptyForecast(AnalyticsPeriod period, long[] next) {
        return ForecastDto.builder()
                .periodLabel(buildNextLabel(period))
                .fromMs(next[0]).toMs(next[1])
                .forecastRevenue(BigDecimal.ZERO).forecastIncome(BigDecimal.ZERO)
                .forecastExpense(BigDecimal.ZERO).forecastProfit(BigDecimal.ZERO)
                .forecastOrderCount(0L)
                .method("Không đủ dữ liệu").confidence(0.0)
                .build();
    }
}