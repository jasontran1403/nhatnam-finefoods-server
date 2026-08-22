package com.nhatnam.server.service.admin;

import com.nhatnam.server.dto.dashboard.*;
import com.nhatnam.server.entity.ExpenseVoucher;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
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
    private final CategoryRepository categoryRepository;
    private final IngredientStockRepository ingredientStockRepository;
    private final IngredientExpiryRepository ingredientExpiryRepository;

    /** Tên category (không phân biệt hoa thường) cho từng loại tồn kho hiển thị ở dashboard Owner */
    private static final List<String> CREAM_CATEGORY_NAMES   = List.of("Non-Dairy Creams");
    private static final List<String> SPICE_CATEGORY_NAMES   = List.of("Herbs , Spices & Condiments");
    private static final List<String> SAUSAGE_CATEGORY_NAMES = List.of("Small Goods");

    private static final ZoneId VN_ZONE    = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_HOUR  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH");
    private static final DateTimeFormatter DATE_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final int MAX_POINTS = 12;

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────
    public DashboardStatsDto getStats(long from, long to) {

        // ── Doanh thu kỳ này / Đã thu / Chưa thu ──────────────────────────────────
        // Làm tròn TỪNG đơn (0 chữ số thập phân, HALF_UP: 0.5→1, 0.4→0) TRƯỚC khi cộng
        // dồn để tránh lệch vài đồng do cộng số lẻ.
        //
        //  • Doanh thu kỳ này  = Σ round(finalAmount) của đơn KHÔNG ở CANCELLED/PREPARING/FAILED.
        //
        //  Breakdown ĐẦY ĐỦ (phân hoạch TẤT CẢ đơn trong kỳ theo round(finalAmount)):
        //  • Đang xử lý (processing) = Σ round(final) của PREPARING + DELIVERING.
        //  • Đã thu    (collected)   = Σ round(paid) của PENDING_PAYMENT  +  Σ round(final) của COMPLETED
        //                              (đơn hoàn thành coi như đã thu đủ).
        //  • Chưa thu  (uncollected) = Σ (UNPAID→round(final), PARTIAL→round(final−paid)) của PENDING_PAYMENT.
        //  • Đã hủy    (cancelled)   = Σ round(final) của CANCELLED + FAILED.
        //  → Đang xử lý + Đã thu + Chưa thu + Đã hủy = Σ round(final) của toàn bộ đơn trong kỳ.
        List<com.nhatnam.server.entity.Order> periodOrders =
                orderRepository.findByCreatedAtBetween(from, to);
        BigDecimal revenuePeriod     = BigDecimal.ZERO;
        BigDecimal processingAmount  = BigDecimal.ZERO;
        BigDecimal totalPaidAmount   = BigDecimal.ZERO;
        BigDecimal totalUnpaidAmount = BigDecimal.ZERO;
        BigDecimal cancelledAmount   = BigDecimal.ZERO;
        for (com.nhatnam.server.entity.Order o : periodOrders) {
            if (!isRevenueExcluded(o)) {
                revenuePeriod = revenuePeriod.add(roundVnd(o.getFinalAmount()));
            }
//            revenuePeriod = revenuePeriod.add(roundVnd(o.getFinalAmount()));
            switch (o.getStatus()) {
                case PREPARING, DELIVERING ->
                        processingAmount = processingAmount.add(roundVnd(o.getFinalAmount()));
                case PENDING_PAYMENT -> {
                    totalPaidAmount = totalPaidAmount.add(roundVnd(o.getPaidAmount()));
                    PaymentStatus ps = o.getPaymentStatus();
                    if (ps == PaymentStatus.UNPAID || ps == PaymentStatus.PARTIAL) {
                        totalUnpaidAmount = totalUnpaidAmount.add(unpaidOfOrder(o));
                    }
                }
                case COMPLETED ->
                        totalPaidAmount = totalPaidAmount.add(roundVnd(o.getFinalAmount()));
                case CANCELLED, FAILED ->
                        cancelledAmount = cancelledAmount.add(roundVnd(o.getFinalAmount()));
                default -> { /* PENDING/CONFIRMED/READY (legacy): không đưa vào breakdown */ }
            }
        }

        // ── Cộng phần khách thanh toán dư (CHƯA hoàn lại) vào "Đã thu" ──────
        // Khi khách trả dư nhưng chưa lập phiếu chi hoàn (overpaidRefundVoucherCode = NULL),
        // số tiền dư đó vẫn đang nằm trong quỹ → cần phản ánh vào tổng đã thu.
        // Nếu đã lập phiếu chi hoàn → phần dư đã chi ra, không cộng nữa.
        BigDecimal unrefundedOverpaid = orZero(
                orderRepository.sumUnrefundedOverpaidBetween(from, to));
        totalPaidAmount = totalPaidAmount.add(roundVnd(unrefundedOverpaid));

        long ordersPeriod    = periodOrders.stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELLED).count();
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
        // Doanh thu kỳ trước — cùng định nghĩa mới (round từng đơn, loại CANCELLED/FAILED)
        // để badge % thay đổi so sánh cùng cơ sở.
        BigDecimal revenuePrev = BigDecimal.ZERO;
        for (com.nhatnam.server.entity.Order o : orderRepository.findByCreatedAtBetween(from - len, from)) {
            if (isRevenueExcluded(o)) continue;
            revenuePrev = revenuePrev.add(roundVnd(o.getFinalAmount()));
        }
        long ordersPrev = orderRepository.countByCreatedAtBetween(from - len, from);

        List<RevenuePointDto>      revenuePoints    = buildRevenuePoints(from, to);
        List<StatusCountDto>       ordersByStatus   = orderRepository.countOrdersByStatusAndRange(from, to);
        List<PaymentMethodStatDto> paymentBreakdown = orderRepository.revenueByPaymentMethod(from, to);

        BigDecimal totalExpenses   = sumExpensesInRange(from, to);

        StockTotals creamStock   = sumStockByCategoryNames(CREAM_CATEGORY_NAMES);
        StockTotals spiceStock   = sumStockByCategoryNames(SPICE_CATEGORY_NAMES);
        StockTotals sausageStock = sumStockByCategoryNames(SAUSAGE_CATEGORY_NAMES);

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
                .totalUnpaidAmount(totalUnpaidAmount)
                .processingAmount(processingAmount)
                .cancelledAmount(cancelledAmount)
                .creamStockQty(creamStock.qty)
                .creamStockValue(creamStock.value)
                .spiceStockQty(spiceStock.qty)
                .spiceStockValue(spiceStock.value)
                .sausageStockQty(sausageStock.qty)
                .sausageStockValue(sausageStock.value)
                .build();
    }

    /**
     * Tổng tiền chi (phiếu chi đã duyệt) quy theo KỲ CHI PHÍ (expensePeriod =
     * "YYYY-MM" — tháng mà khoản chi THUỘC VỀ), không theo ngày tạo/duyệt.
     *
     * <p>Quy tắc chọn kỳ theo khoảng thời gian [from, to] đang xem:
     * <ul>
     *   <li>Khoảng ≤ ~1 tháng (preset Hôm nay / Tuần này / Tháng này, hoặc nút
     *       chọn 1 tháng cố định) → lấy TRỌN tháng chứa ngày cuối {@code to}.
     *       Nhờ vậy phiếu chi nhập cho tháng quá khứ không lẫn vào tháng hiện tại,
     *       và ngược lại xem đúng tháng quá khứ sẽ thấy phiếu chi của tháng đó.</li>
     *   <li>Khoảng > 1 tháng (custom dài, hoặc preset Năm nay) → cộng tất cả các
     *       tháng bị phủ từ tháng {@code from} đến tháng {@code to}.</li>
     * </ul>
     */
    /**
     * Tổng tiền chi (phiếu chi đã duyệt) rơi vào khoảng [from, to] theo NGÀY TỔNG
     * HỢP của phiếu ({@code effectiveAt}) — tức ngày đã set (chế độ "Ngày") hoặc
     * đầu tháng của kỳ (chế độ "Kỳ"). Phiếu cũ chưa có {@code effectiveAt} thì
     * fallback về {@code createdAt}.
     *
     * <p>Nhờ vậy khi lọc dashboard theo 1 ngày cụ thể (VD 01/07), chỉ những phiếu
     * chi được set đúng ngày đó mới được cộng; chọn ngày khác (02/07, hôm nay...)
     * sẽ KHÔNG còn tính nhầm sang, thay cho cách gom trọn tháng trước đây.
     */
    private BigDecimal sumExpensesInRange(long from, long to) {
        return orZero(expenseVoucherRepository.sumApprovedInPeriod(
                from, to, ExpenseVoucher.VoucherStatus.APPROVED));
    }

    /** Kết quả tính tổng tồn kho (số lượng + giá trị) cho 1 nhóm category. */
    private static final class StockTotals {
        final BigDecimal qty;
        final BigDecimal value;
        StockTotals(BigDecimal qty, BigDecimal value) { this.qty = qty; this.value = value; }
    }

    /**
     * Chuẩn hoá tên category để so khớp không bị lệch bởi khoảng trắng thừa:
     * gộp mọi chuỗi khoảng trắng liên tiếp (kể cả quanh dấu phẩy) thành đúng
     * 1 khoảng trắng, và trim 2 đầu. VD: "Herbs , Spices  &  Condiments " →
     * "Herbs , Spices & Condiments" (đã gộp khoảng trắng kép) — sau đó còn
     * equalsIgnoreCase nên không phân biệt hoa/thường nữa.
     */
    private static String normalizeCategoryName(String name) {
        if (name == null) return "";
        return name.trim()
                .replaceAll("\\s+", " ")   // gộp nhiều khoảng trắng liên tiếp thành 1
                .replaceAll("\\s*,\\s*", ", "); // chuẩn hoá khoảng trắng quanh dấu phẩy: luôn "X, Y"
    }

    /**
     * Tổng tồn kho (số lượng + giá trị) của tất cả nguyên liệu thuộc 1 trong các
     * category cho trước, cộng dồn trên TẤT CẢ các kho. Giá trị tính theo lô
     * thực tế (costPrice từng lô FIFO) nếu có, nếu không thì fallback theo
     * IngredientStock.totalCostValue. Lô không có giá vốn → tính giá 0.
     */
    private StockTotals sumStockByCategoryNames(List<String> categoryNames) {
        // Lấy categoryId theo tên — so khớp linh hoạt: bỏ qua hoa/thường VÀ
        // chuẩn hoá khoảng trắng thừa (VD: tên thực tế trong DB có thể là
        // "Herbs , Spices & Condiments" — có dấu cách thừa trước dấu phẩy —
        // trong khi hằng số khai báo là "Herbs, Spices & Condiments". So khớp
        // tuyệt đối trước đây khiến không tìm thấy category nào, trả về 0kg/0đ
        // dù tồn kho thực tế > 0). normalize() gộp mọi khoảng trắng liên tiếp
        // (kể cả quanh dấu phẩy) thành đúng 1 khoảng trắng trước khi so sánh.
        List<com.nhatnam.server.entity.Category> allCategories = categoryRepository.findAll();
        Set<Long> matchedCategoryIds = allCategories.stream()
                .filter(c -> categoryNames.stream().anyMatch(name -> normalizeCategoryName(name).equalsIgnoreCase(normalizeCategoryName(c.getName()))))
                .map(com.nhatnam.server.entity.Category::getId)
                .collect(java.util.stream.Collectors.toSet());

        if (matchedCategoryIds.isEmpty()) return new StockTotals(BigDecimal.ZERO, BigDecimal.ZERO);

        List<com.nhatnam.server.entity.IngredientStock> stocks =
                ingredientStockRepository.findAllByIngredientCategoryIds(new ArrayList<>(matchedCategoryIds));
        if (stocks.isEmpty()) return new StockTotals(BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal totalQty = stocks.stream()
                .map(com.nhatnam.server.entity.IngredientStock::getStockQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Giá trị: ưu tiên tính theo lô thực tế (costPrice), fallback theo totalCostValue.
        // Lô/stock không có giá vốn → coi như giá 0 (không cộng thêm gì).
        Set<Long> ingredientIds = stocks.stream()
                .map(com.nhatnam.server.entity.IngredientStock::getIngredientId)
                .collect(java.util.stream.Collectors.toSet());
        List<com.nhatnam.server.entity.IngredientExpiry> lots = ingredientExpiryRepository
                .findAllByIngredientIdInAndQuantityPositive(new ArrayList<>(ingredientIds));
        Map<Long, BigDecimal> lotValueByIngredientId = new HashMap<>();
        for (com.nhatnam.server.entity.IngredientExpiry lot : lots) {
            if (lot.getCostPrice() == null) continue; // không có giá vốn → giá 0, không cộng
            BigDecimal lotValue = lot.getQuantity().multiply(lot.getCostPrice())
                    .setScale(2, RoundingMode.HALF_UP);
            lotValueByIngredientId.merge(lot.getIngredientId(), lotValue, BigDecimal::add);
        }

        BigDecimal totalValue = BigDecimal.ZERO;
        for (com.nhatnam.server.entity.IngredientStock s : stocks) {
            BigDecimal fromLots = lotValueByIngredientId.get(s.getIngredientId());
            if (fromLots != null && fromLots.compareTo(BigDecimal.ZERO) > 0) {
                totalValue = totalValue.add(fromLots);
            } else if (s.getTotalCostValue() != null) {
                totalValue = totalValue.add(s.getTotalCostValue());
            }
            // else: không có giá vốn ở cả lô và stock → giá trị 0, không cộng thêm gì
        }

        return new StockTotals(totalQty, totalValue);
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

    /** Làm tròn về đồng (0 chữ số thập phân, HALF_UP); null → 0. */
    private static BigDecimal roundVnd(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(0, RoundingMode.HALF_UP);
    }

    /** Đơn KHÔNG tính vào doanh thu: CANCELLED, PREPARING và FAILED. */
    private static boolean isRevenueExcluded(com.nhatnam.server.entity.Order o) {
        return o.getStatus() == OrderStatus.CANCELLED
                || o.getStatus() == OrderStatus.FAILED;
    }

    /**
     * Số tiền chưa thu của 1 đơn (đã làm tròn về đồng, HALF_UP, tối thiểu 0):
     * UNPAID → round(finalAmount); PARTIAL → round(finalAmount − paidAmount).
     */
    private static BigDecimal unpaidOfOrder(com.nhatnam.server.entity.Order o) {
        BigDecimal fin  = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
        BigDecimal paid = o.getPaidAmount()  != null ? o.getPaidAmount()  : BigDecimal.ZERO;
        BigDecimal base = (o.getPaymentStatus() == PaymentStatus.PARTIAL)
                ? fin.subtract(paid)
                : fin;
        if (base.signum() <= 0) return BigDecimal.ZERO;
        return base.setScale(0, RoundingMode.HALF_UP);
    }

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