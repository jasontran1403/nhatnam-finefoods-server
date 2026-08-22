package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.cashflow.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Tính toán dòng tiền (Mục 3):
 * <ul>
 *   <li><b>Đầu kỳ</b> = vị thế tiền ngay TRƯỚC mốc bắt đầu.</li>
 *   <li><b>Trong kỳ</b> = phiếu thu (mọi trạng thái ≠ REJECTED) + phiếu chi ĐÃ DUYỆT
 *       trong [from,to].</li>
 *   <li><b>Cuối kỳ</b> = vị thế tiền tính tới hết mốc kết thúc.</li>
 * </ul>
 * Baseline (đầu kỳ gốc) = 0; mỗi lần xác nhận LỆCH đặt baseline mới từ thời điểm đó.
 * Phiếu thu tính theo createdAt; phiếu chi tính theo COALESCE(effectiveAt,createdAt).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CashflowService {

    private final IncomeVoucherRepository incomeRepo;
    private final ExpenseVoucherRepository expenseRepo;
    private final BankAccountRepository bankRepo;
    private final CashflowConfirmationRepository confirmRepo;
    private final ObjectMapper objectMapper;

    // ── Ngân hàng ─────────────────────────────────────────────────────────────
    @Transactional
    public List<BankAccountDto> ensureSeedAndList() {
        List<BankAccount> banks = bankRepo.findByActiveTrueOrderBySortOrderAscIdAsc();
        if (banks.isEmpty()) {
            bankRepo.save(BankAccount.builder().name("Vietcombank").sortOrder(1).active(true).build());
            bankRepo.save(BankAccount.builder().name("VietinBank").sortOrder(2).active(true).build());
            banks = bankRepo.findByActiveTrueOrderBySortOrderAscIdAsc();
        }
        return banks.stream().map(this::toBankDto).toList();
    }

    @Transactional
    public BankAccountDto addBank(String name, String accountNumber) {
        String n = name == null ? "" : name.trim();
        if (n.isBlank()) throw new BusinessException("Tên ngân hàng là bắt buộc");
        if (bankRepo.existsByNameIgnoreCase(n)) throw new BusinessException("Ngân hàng đã tồn tại");
        int nextOrder = bankRepo.findByActiveTrueOrderBySortOrderAscIdAsc().size() + 1;
        BankAccount b = bankRepo.save(BankAccount.builder()
                .name(n).accountNumber(accountNumber != null ? accountNumber.trim() : null)
                .sortOrder(nextOrder).active(true).build());
        return toBankDto(b);
    }

    private BankAccountDto toBankDto(BankAccount b) {
        return BankAccountDto.builder().id(b.getId()).name(b.getName())
                .accountNumber(b.getAccountNumber()).build();
    }

    private List<String> bankNames() {
        return bankRepo.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(BankAccount::getName).toList();
    }

    // ── Vị thế tiền ───────────────────────────────────────────────────────────
    /** Nội bộ: tiền mặt + map(tên NH → số dư). */
    private static class Position {
        BigDecimal cash = BigDecimal.ZERO;
        Map<String, BigDecimal> banks = new LinkedHashMap<>();
        void addBank(String name, BigDecimal amt) {
            if (name == null || name.isBlank()) name = "(Không rõ)";
            banks.merge(name, amt, BigDecimal::add);
        }
    }

    /** Vị thế tiền xét CÁC dòng có mốc thời gian &lt; boundaryExclusive. */
    private Position positionAt(long boundaryExclusive) {
        Position p = new Position();
        CashflowConfirmation baseline = confirmRepo.findBaselineBefore(boundaryExclusive);
        long fromExclusive;
        if (baseline != null) {
            p.cash = nz(baseline.getCashCounted());
            parseBankMap(baseline.getBankBalancesJson()).forEach(p::addBank);
            fromExclusive = baseline.getConfirmedAt();
        } else {
            fromExclusive = Long.MIN_VALUE;
        }
        applyFlows(p, fromExclusive + 1, boundaryExclusive - 1);
        return p;
    }

    /** Cộng/trừ các dòng thu-chi có mốc trong [from,to] (inclusive) vào vị thế p. */
    private void applyFlows(Position p, long from, long to) {
        if (from > to) return;
        for (IncomeVoucher iv : incomeRepo.findCountedBetween(IncomeVoucher.VoucherStatus.REJECTED, from, to)) {
            BigDecimal amt = incomeTotal(iv);
            if (isBank(iv.getPaymentType() != null ? iv.getPaymentType().name() : "CASH")) p.addBank(iv.getBankName(), amt);
            else p.cash = p.cash.add(amt);
        }
        for (ExpenseVoucher ev : expenseRepo.findApprovedByEffectiveBetween(ExpenseVoucher.VoucherStatus.APPROVED, from, to)) {
            BigDecimal amt = expenseTotal(ev);
            if (isBank(ev.getPaymentType() != null ? ev.getPaymentType().name() : "CASH")) p.addBank(ev.getBankName(), amt.negate());
            else p.cash = p.cash.subtract(amt);
        }
    }

    private CashPositionDto toPositionDto(Position p) {
        List<String> order = bankNames();
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        for (String n : order) merged.put(n, p.banks.getOrDefault(n, BigDecimal.ZERO));
        // các ngân hàng xuất hiện trong dữ liệu nhưng chưa có trong danh sách TK
        p.banks.forEach((k, v) -> merged.putIfAbsent(k, v));
        List<BankBalanceDto> banks = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : merged.entrySet()) {
            banks.add(BankBalanceDto.builder().name(e.getKey()).balance(e.getValue()).build());
            total = total.add(e.getValue());
        }
        return CashPositionDto.builder().cash(p.cash).bankTotal(total).banks(banks).build();
    }

    // ── Tổng hợp dòng tiền ────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public CashflowSummaryDto summary(long from, long to) {
        long end = Math.min(to, System.currentTimeMillis());
        if (end < from) end = from;

        Position openingPos = positionAt(from);
        Position closingPos = positionAt(end + 1);

        List<CashflowFlowDto> incomes = new ArrayList<>();
        List<CashflowFlowDto> expenses = new ArrayList<>();
        BigDecimal incCash = BigDecimal.ZERO, incBank = BigDecimal.ZERO;
        BigDecimal expCash = BigDecimal.ZERO, expBank = BigDecimal.ZERO;

        for (IncomeVoucher iv : incomeRepo.findCountedBetween(IncomeVoucher.VoucherStatus.REJECTED, from, end)) {
            BigDecimal amt = incomeTotal(iv);
            boolean bank = isBank(iv.getPaymentType() != null ? iv.getPaymentType().name() : "CASH");
            if (bank) incBank = incBank.add(amt); else incCash = incCash.add(amt);
            incomes.add(CashflowFlowDto.builder()
                    .id(iv.getId()).kind("INCOME")
                    .number(iv.getReceiptNumber()).voucherCode(iv.getVoucherCode())
                    .amount(amt).reason(iv.getReason())
                    .createdByName(iv.getCreatedByName())
                    .paymentType(bank ? "BANK_TRANSFER" : "CASH").bankName(iv.getBankName())
                    .at(iv.getCreatedAt()).build());
        }
        for (ExpenseVoucher ev : expenseRepo.findApprovedByEffectiveBetween(ExpenseVoucher.VoucherStatus.APPROVED, from, end)) {
            BigDecimal amt = expenseTotal(ev);
            boolean bank = isBank(ev.getPaymentType() != null ? ev.getPaymentType().name() : "CASH");
            if (bank) expBank = expBank.add(amt); else expCash = expCash.add(amt);
            expenses.add(CashflowFlowDto.builder()
                    .id(ev.getId()).kind("EXPENSE")
                    .number(ev.getPaymentNumber()).voucherCode(ev.getVoucherCode())
                    .amount(amt).reason(ev.getReason())
                    .createdByName(ev.getCreatedByName()).approvedByName(ev.getApprovedByName())
                    .paymentType(bank ? "BANK_TRANSFER" : "CASH").bankName(ev.getBankName())
                    .at(ev.getEffectiveAt() != null ? ev.getEffectiveAt() : ev.getCreatedAt()).build());
        }

        List<CashflowConfirmationDto> confs = confirmRepo.findBetween(from, end).stream()
                .map(this::toConfDto).toList();

        return CashflowSummaryDto.builder()
                .from(from).to(end)
                .opening(toPositionDto(openingPos))
                .incomes(incomes).expenses(expenses)
                .incomeCashTotal(incCash).incomeBankTotal(incBank)
                .expenseCashTotal(expCash).expenseBankTotal(expBank)
                .closing(toPositionDto(closingPos))
                .confirmations(confs)
                .build();
    }

    // ── Xác nhận (chốt) dòng tiền ─────────────────────────────────────────────
    @Transactional
    public ConfirmResultDto confirm(User user, ConfirmCashflowRequest req) {
        long now = System.currentTimeMillis();
        Position expectedPos = positionAt(now + 1);            // số hệ thống tới hiện tại
        CashPositionDto expected = toPositionDto(expectedPos);

        // ── Tiền mặt: ưu tiên kiểm đếm theo MỆNH GIÁ ────────────────────────
        // Nếu FE gửi cashDenominations → server TỰ TÍNH LẠI tổng = Σ(mệnh giá × SL),
        // bỏ qua cashCounted client gửi (chống lệch do làm tròn/sửa tay ở FE).
        // Nếu không gửi → giữ luồng cũ (nhập tổng trực tiếp).
        Map<Long, Integer> denomCounts = normalizeDenominations(req.getCashDenominations());
        BigDecimal cashCounted = denomCounts.isEmpty()
                ? nz(req.getCashCounted())
                : sumDenominations(denomCounts);

        Map<String, BigDecimal> counted = new LinkedHashMap<>();
        if (req.getBankBalances() != null) req.getBankBalances().forEach((k, v) -> counted.put(k, nz(v)));

        boolean matched = eq(cashCounted, expectedPos.cash);
        // đối chiếu từng ngân hàng
        Set<String> allBanks = new LinkedHashSet<>();
        allBanks.addAll(bankNames());
        allBanks.addAll(expectedPos.banks.keySet());
        allBanks.addAll(counted.keySet());
        for (String b : allBanks) {
            BigDecimal exp = expectedPos.banks.getOrDefault(b, BigDecimal.ZERO);
            BigDecimal cnt = counted.getOrDefault(b, BigDecimal.ZERO);
            if (!eq(exp, cnt)) matched = false;
        }

        if (!matched && (req.getReason() == null || req.getReason().isBlank()))
            throw new BusinessException("Có sai lệch — vui lòng nhập lý do");

        String roleName = user.getRole() != null ? user.getRole().name() : "ADMIN";
        String name = user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName() : user.getUsername();

        CashflowConfirmation c = CashflowConfirmation.builder()
                .confirmedAt(now)
                .confirmedByName(name).confirmedByRole(roleName)
                .matched(matched)
                .reason(matched ? null : req.getReason().trim())
                .cashCounted(cashCounted)
                .cashDenominationsJson(denomCounts.isEmpty() ? null : writeDenomMap(denomCounts))
                .bankBalancesJson(writeBankMap(counted))
                .expectedCash(expectedPos.cash)
                .expectedBankJson(writeBankMap(expectedPos.banks))
                .build();
        c = confirmRepo.save(c);

        Position countedPos = new Position();
        countedPos.cash = cashCounted;
        counted.forEach(countedPos::addBank);

        return ConfirmResultDto.builder()
                .matched(matched)
                .expected(expected)
                .counted(toPositionDto(countedPos))
                .confirmation(toConfDto(c))
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private boolean isBank(String paymentType) { return "BANK_TRANSFER".equalsIgnoreCase(paymentType); }

    private BigDecimal incomeTotal(IncomeVoucher iv) {
        if (iv.getItems() == null || iv.getItems().isEmpty()) return BigDecimal.ZERO;
        return iv.getItems().stream().map(IncomeItem::getAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private BigDecimal expenseTotal(ExpenseVoucher ev) {
        if (ev.getItems() == null || ev.getItems().isEmpty()) return BigDecimal.ZERO;
        return ev.getItems().stream().map(ExpenseItem::getAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private boolean eq(BigDecimal a, BigDecimal b) {
        return nz(a).setScale(0, RoundingMode.HALF_UP)
                .compareTo(nz(b).setScale(0, RoundingMode.HALF_UP)) == 0;
    }

    private Map<String, BigDecimal> parseBankMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, BigDecimal>>() {}); }
        catch (Exception e) { return Map.of(); }
    }
    private String writeBankMap(Map<String, BigDecimal> m) {
        try { return objectMapper.writeValueAsString(m); } catch (Exception e) { return "{}"; }
    }

    private List<BankBalanceDto> bankMapToList(String json) {
        Map<String, BigDecimal> m = parseBankMap(json);
        List<BankBalanceDto> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(BankBalanceDto.builder().name(k).balance(v).build()));
        return out;
    }

    // ── Mệnh giá tiền mặt ─────────────────────────────────────────────────────

    /**
     * Validate + gom các dòng mệnh giá FE gửi lên thành map {mệnh giá → số lượng}.
     * Bỏ qua dòng có SL = 0/null (FE render đủ 11 mệnh giá, đa số để trống).
     * Trả về map RỖNG nếu không có dòng nào hợp lệ → caller fallback về luồng nhập tổng.
     */
    private Map<Long, Integer> normalizeDenominations(List<CashDenominationDto> rows) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) return out;

        for (CashDenominationDto r : rows) {
            if (r == null) continue;
            Long d = r.getDenomination();
            if (!CashDenominations.isValid(d))
                throw new BusinessException("Mệnh giá không hợp lệ: " + d);

            int qty = r.getQuantity() == null ? 0 : r.getQuantity();
            if (qty < 0)
                throw new BusinessException("Số lượng mệnh giá " + d + " không được âm");
            if (qty == 0) continue;

            // FE gửi trùng mệnh giá → cộng dồn thay vì ghi đè (an toàn hơn)
            out.merge(d, qty, Integer::sum);
        }

        // Giữ đúng thứ tự giảm dần của bộ mệnh giá chuẩn
        Map<Long, Integer> ordered = new LinkedHashMap<>();
        for (Long d : CashDenominations.ALLOWED) {
            if (out.containsKey(d)) ordered.put(d, out.get(d));
        }
        return ordered;
    }

    private BigDecimal sumDenominations(Map<Long, Integer> counts) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            total = total.add(BigDecimal.valueOf(e.getKey()).multiply(BigDecimal.valueOf(e.getValue())));
        }
        return total;
    }

    private String writeDenomMap(Map<Long, Integer> m) {
        try { return objectMapper.writeValueAsString(m); } catch (Exception e) { return "{}"; }
    }

    private Map<Long, Integer> parseDenomMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<LinkedHashMap<Long, Integer>>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    /** Map JSON → list DTO (kèm thành tiền từng mệnh giá) cho FE hiển thị lại lịch sử. */
    private List<CashDenominationDto> denomMapToList(String json) {
        Map<Long, Integer> m = parseDenomMap(json);
        List<CashDenominationDto> out = new ArrayList<>();
        for (Long d : CashDenominations.ALLOWED) {
            Integer qty = m.get(d);
            if (qty == null || qty <= 0) continue;
            out.add(CashDenominationDto.builder()
                    .denomination(d)
                    .quantity(qty)
                    .amount(BigDecimal.valueOf(d).multiply(BigDecimal.valueOf(qty)))
                    .build());
        }
        return out;
    }

    private CashflowConfirmationDto toConfDto(CashflowConfirmation c) {
        return CashflowConfirmationDto.builder()
                .id(c.getId()).confirmedAt(c.getConfirmedAt())
                .confirmedByName(c.getConfirmedByName()).confirmedByRole(c.getConfirmedByRole())
                .matched(c.getMatched()).reason(c.getReason())
                .cashCounted(c.getCashCounted())
                .cashDenominations(denomMapToList(c.getCashDenominationsJson()))
                .banks(bankMapToList(c.getBankBalancesJson()))
                .expectedCash(c.getExpectedCash()).expectedBanks(bankMapToList(c.getExpectedBankJson()))
                .build();
    }
}
