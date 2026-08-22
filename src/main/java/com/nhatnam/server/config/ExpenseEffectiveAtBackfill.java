package com.nhatnam.server.config;

import com.nhatnam.server.entity.ExpenseVoucher;
import com.nhatnam.server.repository.ExpenseVoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Backfill {@code effectiveAt} (mốc tổng hợp báo cáo) cho các phiếu chi cũ được
 * tạo TRƯỚC khi có tính năng chọn Ngày/Kỳ (những phiếu này có effectiveAt = null).
 *
 * <p>Chạy 1 lần lúc khởi động, idempotent (chỉ đụng phiếu có effectiveAt null):
 * <ul>
 *   <li>Có {@code expenseDate} → effectiveAt = expenseDate.</li>
 *   <li>Không có ngày nhưng có {@code expensePeriod} ("YYYY-MM") → effectiveAt =
 *       00:00 ngày 1 của tháng đó (giờ VN) — khớp ý nghĩa "kỳ chi phí".</li>
 *   <li>Không có cả hai → effectiveAt = {@code createdAt} (giữ nguyên hành vi cũ).</li>
 * </ul>
 *
 * <p>Sau khi tất cả phiếu đã có effectiveAt, có thể xoá class này an toàn.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpenseEffectiveAtBackfill implements ApplicationRunner {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ExpenseVoucherRepository expenseVoucherRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<ExpenseVoucher> pending = expenseVoucherRepository.findByEffectiveAtIsNull();
        if (pending.isEmpty()) return;

        int updated = 0;
        for (ExpenseVoucher v : pending) {
            Long effectiveAt = resolveEffectiveAt(v);
            if (effectiveAt != null) {
                v.setEffectiveAt(effectiveAt);
                updated++;
            }
        }
        expenseVoucherRepository.saveAll(pending);
        log.info("[ExpenseEffectiveAtBackfill] Đã backfill effectiveAt cho {} phiếu chi cũ.", updated);
    }

    private Long resolveEffectiveAt(ExpenseVoucher v) {
        if (v.getExpenseDate() != null) return v.getExpenseDate();
        String period = v.getExpensePeriod();
        if (period != null && !period.isBlank()) {
            try {
                return YearMonth.parse(period)
                        .atDay(1).atStartOfDay(VN_ZONE)
                        .toInstant().toEpochMilli();
            } catch (Exception ignore) { /* rơi xuống dùng createdAt */ }
        }
        return v.getCreatedAt();
    }
}