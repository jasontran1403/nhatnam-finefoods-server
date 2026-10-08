// src/main/java/com/nhatnam/server/service/hr/PaymentTransactionBackfillService.java
package com.nhatnam.server.service.hr;

import com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.PaymentBackfillReportDto;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderLog;
import com.nhatnam.server.entity.PaymentTransaction;
import com.nhatnam.server.repository.OrderLogRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BACKFILL {@link PaymentTransaction} TỪ {@code order_log}.
 *
 * <h3>Vì sao cần</h3>
 * Trước đây mỗi lần thanh toán có ghi dòng {@code order_log} với note dạng
 * {@code "Thu: 200000 (CASH) | Tổng đã thu: ..."} NHƯNG không ghi
 * {@link PaymentTransaction} tương ứng (code đó mới được bổ sung sau).
 * Hậu quả: các query tính hoa hồng (query vào {@code payment_transaction}) bỏ
 * sót toàn bộ dữ liệu cũ.
 *
 * <h3>Hoạt động</h3>
 * <ol>
 *   <li>Scan {@code order_log} trong khoảng {@code [from, to)} với action là 1
 *       trong {@link #PAYMENT_ACTIONS}.</li>
 *   <li>Parse note theo regex {@link #THU_PATTERN} để lấy amount + method.</li>
 *   <li>DEDUP: nếu đã có PaymentTransaction cho đơn này với {@code createdAt}
 *       trong ±2s so với log → bỏ qua (coi như đã backfill hoặc code mới đã
 *       tạo rồi).</li>
 *   <li>Tạo PT mới với:
 *       <ul>
 *         <li>{@code createdAt} = {@code log.createdAt} (giữ nguyên epoch ms gốc).</li>
 *         <li>{@code collectedBy} = {@code log.actorName}.</li>
 *         <li>{@code source} = {@value #SOURCE_BACKFILL} để phân biệt với PT gốc.</li>
 *       </ul></li>
 * </ol>
 *
 * <h3>Múi giờ</h3>
 * Toàn bộ timestamp lưu dạng epoch ms UTC — <b>không</b> convert khi lưu. Khi
 * caller truyền {@code from}/{@code to} cho khoảng tháng, họ phải tự tính biên
 * tháng theo giờ VN ({@link #toEpochMsStartOfMonthVN} / {@link #toEpochMsStartOfNextMonthVN}).
 * Nhờ vậy đơn thu lúc 06:00 ngày 1/9 VN (= 23:00 ngày 31/8 UTC) vẫn được tính
 * cho tháng 9 vì biên tháng 9 bắt đầu từ 00:00 ngày 1/9 giờ VN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionBackfillService {

    public static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Nhãn {@link PaymentTransaction#getSource()} đánh dấu dòng do backfill tạo. */
    public static final String SOURCE_BACKFILL = "BACKFILL_LOG";

    /** Các action của order_log có chứa thông tin thanh toán. */
    private static final Set<String> PAYMENT_ACTIONS = Set.of(
            "PARTIAL_PAYMENT",
            "FULLY_PAID",
            "PREPAYMENT_PARTIAL",
            "PREPAYMENT_FULL"
            // "WAIVE_REMAINDER" KHÔNG đưa vào — note dạng khác, PT thực tế đã
            // được code cũ tạo ở hàm xác nhận bỏ số lẻ.
    );

    /**
     * Regex tách "Thu: 200000 (CASH)" HOẶC "Thu trước: 200000 (CASH)".
     * Group 1 = amount (có thể có dấu chấm thập phân), Group 2 = method.
     */
    private static final Pattern THU_PATTERN = Pattern.compile(
            "Thu(?:\\s*trước)?\\s*:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    /** Cửa sổ dedup — ±{@value}ms quanh timestamp của log. */
    private static final long DEDUP_WINDOW_MS = 2_000L;

    private final OrderLogRepository orderLogRepo;
    private final PaymentTransactionRepository ptRepo;
    private final OrderRepository orderRepo;

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Backfill cho toàn bộ dữ liệu (từ 2020-01-01 giờ VN đến bây giờ).
     * Chạy 1 lần sau khi deploy để tạo PT cho dữ liệu cũ.
     */
    @Transactional
    public PaymentBackfillReportDto backfillAll(boolean dryRun) {
        long from = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, VN).toInstant().toEpochMilli();
        long to   = System.currentTimeMillis();
        return backfillRange(from, to, dryRun);
    }

    /**
     * Backfill 1 tháng cụ thể theo giờ VN. {@code [1/M/Y 00:00 VN, 1/(M+1)/Y 00:00 VN)}.
     */
    @Transactional
    public PaymentBackfillReportDto backfillMonth(int month, int year, boolean dryRun) {
        long from = toEpochMsStartOfMonthVN(year, month);
        long to   = toEpochMsStartOfNextMonthVN(year, month);
        return backfillRange(from, to, dryRun);
    }

    /**
     * Backfill 1 khoảng epoch ms tùy ý.
     * Caller tự tính biên theo giờ VN để tránh lệch múi giờ.
     */
    @Transactional
    public PaymentBackfillReportDto backfillRange(long fromMs, long toMs, boolean dryRun) {
        if (toMs <= fromMs)
            throw new IllegalArgumentException("to phải > from");

        long scanned = 0, created = 0, skippedExisting = 0, skippedUnparseable = 0, skippedZero = 0;
        List<String> warnings = new ArrayList<>();

        // Tìm các order có log trong khoảng — lấy luôn log của nó rồi lọc action.
        // Không có query sẵn trong OrderLogRepository nên dùng cách: scan order theo
        // createdAt trong range mở rộng 1 tháng về trước (log thu có thể cách xa
        // thời điểm tạo đơn), rồi tải log của từng order.
        //
        // Vì backfill là tác vụ chạy tay 1 lần (không realtime), chấp nhận cost này.
        long widenedFrom = fromMs - 180L * 24 * 3600 * 1000L; // lùi 180 ngày
        List<Long> orderIds = orderRepo.findIdsCreatedInRange(widenedFrom, toMs);

        for (Long orderId : orderIds) {
            List<OrderLog> logs = orderLogRepo.findByOrderIdOrderByCreatedAtAsc(orderId);
            if (logs.isEmpty()) continue;

            Order orderRef = logs.get(0).getOrder(); // lazy proxy ok cho FK

            for (OrderLog lg : logs) {
                if (lg.getCreatedAt() == null) continue;
                if (lg.getCreatedAt() < fromMs || lg.getCreatedAt() >= toMs) continue;
                if (!PAYMENT_ACTIONS.contains(lg.getAction())) continue;

                scanned++;

                ParsedPayment parsed = parseNote(lg.getNote());
                if (parsed == null) {
                    skippedUnparseable++;
                    if (warnings.size() < 20)
                        warnings.add("Log #" + lg.getId() + " action=" + lg.getAction()
                                + " note không khớp regex: " + abbreviate(lg.getNote()));
                    continue;
                }
                if (parsed.amount.signum() <= 0) { skippedZero++; continue; }

                boolean exists = ptRepo.existsByOrderIdAndCreatedAtBetween(
                        orderId,
                        lg.getCreatedAt() - DEDUP_WINDOW_MS,
                        lg.getCreatedAt() + DEDUP_WINDOW_MS);
                if (exists) { skippedExisting++; continue; }

                if (!dryRun) {
                    PaymentTransaction pt = PaymentTransaction.builder()
                            .order(orderRef)
                            .amount(parsed.amount)
                            .paymentMethod(parsed.method)
                            .collectedBy(lg.getActorName())
                            .createdAt(lg.getCreatedAt())
                            .source(SOURCE_BACKFILL)
                            .note("Backfill from order_log#" + lg.getId()
                                    + " (" + lg.getAction() + ")")
                            .build();
                    ptRepo.save(pt);
                }
                created++;
            }
        }

        log.info("[PT-Backfill] dryRun={} range=[{},{}) scanned={} created={} skipped(existing={}, unparseable={}, zero={})",
                dryRun, fromMs, toMs, scanned, created, skippedExisting, skippedUnparseable, skippedZero);

        return PaymentBackfillReportDto.builder()
                .dryRun(dryRun)
                .scannedLogs(scanned)
                .createdTransactions(created)
                .skippedExisting(skippedExisting)
                .skippedUnparseable(skippedUnparseable)
                .skippedZeroAmount(skippedZero)
                .warnings(warnings)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /** Dữ liệu tách từ note. */
    private record ParsedPayment(BigDecimal amount, String method) {}

    /**
     * Tách amount + method từ note của order_log.
     * Trả null nếu không parse được.
     */
    static ParsedPayment parseNote(String note) {
        if (note == null || note.isBlank()) return null;
        Matcher m = THU_PATTERN.matcher(note);
        if (!m.find()) return null;
        try {
            String raw = m.group(1).replace(",", ".");
            BigDecimal amount = new BigDecimal(raw);
            String method = m.group(2).trim().toUpperCase();
            return new ParsedPayment(amount, method);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 00:00 ngày 1 tháng {@code month} năm {@code year} giờ VN → epoch ms. */
    public static long toEpochMsStartOfMonthVN(int year, int month) {
        return YearMonth.of(year, month)
                .atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
    }

    /** 00:00 ngày 1 THÁNG SAU giờ VN → epoch ms (biên trên exclusive). */
    public static long toEpochMsStartOfNextMonthVN(int year, int month) {
        return YearMonth.of(year, month).plusMonths(1)
                .atDay(1).atStartOfDay(VN).toInstant().toEpochMilli();
    }

    private static String abbreviate(String s) {
        if (s == null) return "<null>";
        return s.length() <= 120 ? s : s.substring(0, 117) + "…";
    }
}