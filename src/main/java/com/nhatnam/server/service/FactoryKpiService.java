package com.nhatnam.server.service;

import com.nhatnam.server.entity.AttendanceEntry;
import com.nhatnam.server.entity.FactoryKpiBonus;
import com.nhatnam.server.entity.FactoryKpiBonusItem;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.AttendanceEntryRepository;
import com.nhatnam.server.repository.AttendanceSheetRepository;
import com.nhatnam.server.repository.FactoryKpiBonusItemRepository;
import com.nhatnam.server.repository.FactoryKpiBonusRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.repository.WorkOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * TÍNH THƯỞNG KPI CHO PHÒNG SẢN XUẤT theo tháng.
 *
 * <h3>1. Quỹ thưởng</h3>
 * <pre>
 *   totalOutputKg  = Σ accumulatedQty của WorkOrder có status = COMPLETED
 *                    và actualEndDate NẰM TRONG THÁNG.
 *
 *   totalOutputTon = totalOutputKg / 1000
 *   bonusPool      = làm tròn(totalOutputTon × RATE_PER_TON) về hàng NGHÌN
 * </pre>
 *
 * <h3>2. Phân bổ — MÔ HÌNH "ĐƠN GIÁ CHUẨN"</h3>
 * <pre>
 *   B1. đơn giá chuẩn = làm tròn XUỐNG trăm nghìn( quỹ chia / tổng hệ số )
 *   B2. tiền mỗi người = làm tròn LÊN trăm nghìn( đơn giá chuẩn × hệ số hiệu dụng )
 *   B1b. TRẦN QUỸ: hạ đơn giá chuẩn xuống từng bậc 100.000 cho tới khi vừa quỹ.
 *   Phần lẻ → carryOverOut
 * </pre>
 *
 * <h3>3. Ngưỡng ngày / buổi công (thang 2026)</h3>
 * <pre>
 *   công < 15         → 0%
 *   15 ≤ công < 19    → 80%
 *   19 ≤ công ≤ 22    → 90%
 *   công > 22         → 100%
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryKpiService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    // ══════════════════════════════════════════════════════════════════════════
    // THAM SỐ CẤU HÌNH
    // ══════════════════════════════════════════════════════════════════════════

    /** Đơn giá thưởng trên 1 TẤN sản lượng (VNĐ). */
    public static final long RATE_PER_TON = 1_400_000L;

    /** Mức thưởng CỐ ĐỊNH cho bảo vệ xưởng (VNĐ/người/tháng). */
    public static final long SECURITY_FIXED_BONUS = 300_000L;

    /** Bước làm tròn quỹ thưởng cả phòng: hàng NGHÌN. */
    public static final long POOL_ROUNDING_STEP = 1_000L;

    /** Cách làm tròn quỹ. FLOOR = làm tròn xuống (khớp phiếu thưởng thực tế). */
    public static final RoundingMode POOL_ROUNDING = RoundingMode.FLOOR;

    /** Bước làm tròn hàng TRĂM NGHÌN. */
    public static final long EMPLOYEE_ROUNDING_STEP = 100_000L;

    /** Làm tròn XUỐNG khi tính ĐƠN GIÁ CHUẨN. */
    public static final RoundingMode BASE_UNIT_ROUNDING = RoundingMode.FLOOR;

    /** Làm tròn LÊN khi quy tiền cho từng nhân viên. */
    public static final RoundingMode EMPLOYEE_ROUNDING = RoundingMode.CEILING;

    /** Số kg trong 1 tấn. */
    private static final BigDecimal KG_PER_TON = BigDecimal.valueOf(1000);

    // ══════════════════════════════════════════════════════════════════════════
    // NGOẠI LỆ ĐẶC BIỆT — nhân viên luôn full công / nghỉ thai sản / override đóng gói
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Danh sách nhân viên LUÔN được tính FULL CÔNG (hệ số ngày công = 1.0),
     * bỏ qua toàn bộ dữ liệu chấm công.
     */
    private static final Set<String> ALWAYS_FULL_ATTENDANCE_NAMES = Set.of(
            "ngô thị mỹ hạnh"
    );

    /**
     * Danh sách nhân viên NGHỈ THAI SẢN THEO LUẬT — không đủ ngày công nhưng
     * KHÔNG hiển thị lý do "dưới 15 ngày công" trên bảng thưởng.
     */
    private static final Set<String> MATERNITY_LEAVE_NAMES = Set.of(
            "nguyễn thị tuyết"
    );

    /**
     * Danh sách nhân viên LUÔN được tính như CÔNG NHÂN ĐÓNG GÓI
     * ({@link Role#FACTORY_PACKAGING_WORKER}, hệ số 1.2), BẤT KỂ giá trị
     * {@code payroll_role} trong DB là gì.
     *
     * <p>Lý do: một số nhân viên đóng gói bị set nhầm {@code payroll_role}
     * thành {@code FACTORY_PRODUCTION_WORKER} → hệ thống tính hệ số 1.0 (bằng
     * CNSX) và xếp vào nhóm CNSX trên bảng thưởng. Danh sách này override lại:
     *   • Tính đúng hệ số 1.2 (ngang Trợ lý / Kế toán xưởng).
     *   • Sắp xếp đúng vị trí (sau Kế toán xưởng, trước CNSX).
     *
     * <p>So khớp theo HỌ TÊN đã chuẩn hoá (trim + lowercase).
     */
    private static final Set<String> ALWAYS_PACKAGING_NAMES = Set.of(
            "ngô thị mỹ hạnh"
    );

    /** TRUE nếu nhân viên thuộc danh sách luôn được tính full công. */
    public static boolean isAlwaysFullAttendance(User u) {
        if (u == null || u.getFullName() == null) return false;
        String normalized = u.getFullName().trim().toLowerCase(Locale.ROOT);
        return ALWAYS_FULL_ATTENDANCE_NAMES.contains(normalized);
    }

    /** TRUE nếu nhân viên thuộc danh sách nghỉ thai sản theo luật. */
    public static boolean isMaternityLeave(User u) {
        if (u == null || u.getFullName() == null) return false;
        String normalized = u.getFullName().trim().toLowerCase(Locale.ROOT);
        return MATERNITY_LEAVE_NAMES.contains(normalized);
    }

    /** TRUE nếu nhân viên thuộc danh sách luôn được tính là Công nhân đóng gói. */
    public static boolean isAlwaysPackaging(User u) {
        if (u == null || u.getFullName() == null) return false;
        String normalized = u.getFullName().trim().toLowerCase(Locale.ROOT);
        return ALWAYS_PACKAGING_NAMES.contains(normalized);
    }

    /**
     * OVERRIDE role tính KPI theo TÊN nhân viên.
     *
     * <p>Nếu tên nằm trong {@link #ALWAYS_PACKAGING_NAMES} → LUÔN trả về
     * {@link Role#FACTORY_PACKAGING_WORKER} (bất kể {@code role} truyền vào là gì).
     * Ngược lại giữ nguyên {@code role}.
     *
     * <p>Dùng CHUNG cho cả {@link #kpiRoleOf(User)} lẫn
     * {@code SalaryExportService.roleRank()} — đảm bảo 2 nơi luôn nhất quán.
     */
    public static Role overridePackagingRole(User u, Role role) {
        return isAlwaysPackaging(u) ? Role.FACTORY_PACKAGING_WORKER : role;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SỔ QUỸ DƯ
    // ══════════════════════════════════════════════════════════════════════════

    /** Một khoản quỹ dư còn lại, kèm tháng phát sinh. */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CarryOverEntry {
        private int month;
        private int year;
        private long amount;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private List<CarryOverEntry> readLedger(FactoryKpiBonus b) {
        if (b == null || b.getCarryOverDetail() == null || b.getCarryOverDetail().isBlank()) {
            if (b != null && b.getCarryOverOut() != null && b.getCarryOverOut() > 0) {
                return new ArrayList<>(List.of(
                        new CarryOverEntry(b.getMonth(), b.getYear(), b.getCarryOverOut())));
            }
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(JSON.readValue(b.getCarryOverDetail(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<CarryOverEntry>>() {}));
        } catch (Exception e) {
            log.warn("[KPI] Sổ quỹ dư tháng {}/{} hỏng, bỏ qua: {}",
                    b.getMonth(), b.getYear(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private String writeLedger(List<CarryOverEntry> ledger) {
        try {
            return JSON.writeValueAsString(ledger);
        } catch (Exception e) {
            log.error("[KPI] Không ghi được sổ quỹ dư", e);
            return "[]";
        }
    }

    private List<CarryOverEntry> consumeFifo(List<CarryOverEntry> ledger, long spend) {
        List<CarryOverEntry> out = new ArrayList<>();
        long left = spend;

        for (CarryOverEntry e : ledger) {
            if (left <= 0) { out.add(e); continue; }

            long take = Math.min(left, e.getAmount());
            left -= take;
            long rest = e.getAmount() - take;
            if (rest > 0) out.add(new CarryOverEntry(e.getMonth(), e.getYear(), rest));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HỆ SỐ CHIA THƯỞNG THEO VỊ TRÍ
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * HỆ SỐ CHIA THƯỞNG THEO VỊ TRÍ — bội số của mức CHUẨN (base).
     *
     * <pre>
     *   Trưởng xưởng                  = 1,5 → base + 50%      (bước 150.000đ)
     *   Trợ lý / Quản lý / Kế toán
     *   / Đóng gói / NV xưởng         = 1,2 → base + 20%      (bước 120.000đ)
     *   Công nhân sản xuất            = 1,0 → đúng bằng base  (bước 100.000đ)
     * </pre>
     *
     * <p>Thứ tự khai báo trong map = THỨ TỰ XUẤT HIỆN trên bảng thưởng
     * (xem {@link #usersEligibleForWeight()} — duyệt theo {@code keySet()}).
     * Dùng {@link LinkedHashMap} để giữ đúng thứ tự.
     */
    public static final Map<Role, Double> ROLE_WEIGHTS = createRoleWeights();

    private static Map<Role, Double> createRoleWeights() {
        Map<Role, Double> m = new LinkedHashMap<>();
        // Thứ tự khai báo = thứ tự xuất hiện trên bảng thưởng
        m.put(Role.SUPER_FACTORY_WORKER,      1.5);  // Trưởng xưởng       — base + 50%
        m.put(Role.FACTORY_STAFF,             1.2);  // Trợ lý xưởng       — base + 20%
        m.put(Role.FACTORY_MANAGER,           1.2);  // Quản lý xưởng      — base + 20%
        m.put(Role.FACTORY_ACCOUNTANT,        1.2);  // Kế toán xưởng      — base + 20%
        m.put(Role.FACTORY_PACKAGING_WORKER,  1.2);  // Công nhân đóng gói — base + 20%
        m.put(Role.FACTORY_PRODUCTION_WORKER, 1.0);  // Công nhân sản xuất — MỐC CHUẨN (base)
        m.put(Role.FACTORY_WORKER,            1.2);  // Nhân viên xưởng    — base + 20%
        // FACTORY_SECURITY không có ở đây — hưởng mức CỐ ĐỊNH (SECURITY_FIXED_BONUS)
        return Collections.unmodifiableMap(m);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NGƯỠNG NGÀY / BUỔI CÔNG
    // ══════════════════════════════════════════════════════════════════════════

    /** Số công / buổi tối thiểu để được nhận thưởng KPI. */
    public static final double MIN_DAYS_FOR_BONUS = 15.0;

    /** Ngưỡng vào bậc 90%. */
    public static final double TIER_90_THRESHOLD = 19.0;

    /** Ngưỡng vào bậc 100% (STRICTLY greater). */
    public static final double TIER_100_THRESHOLD = 22.0;

    /** Hệ số cho nhân viên parttime — nhân vào trọng số vị trí (base). 60% = 0.6. */
    public static final double PARTTIME_WEIGHT_FACTOR = 0.7;

    /** @deprecated Giữ lại để không break code cũ nếu có nơi tham chiếu. */
    @Deprecated public static final double FULL_BONUS_DAYS_THRESHOLD = TIER_90_THRESHOLD;
    /** @deprecated Không dùng nữa trong thang mới. */
    @Deprecated public static final double PARTIAL_BONUS_FACTOR = 0.5;
    /** @deprecated Không dùng nữa trong thang mới. */
    @Deprecated public static final double MAX_ATTENDANCE_RATIO = 1.0;

    /** Nhãn hiển thị vị trí trên phiếu lương. */
    public static final Map<Role, String> ROLE_LABELS = Map.of(
            Role.FACTORY_PRODUCTION_WORKER, "Công nhân sản xuất",
            Role.FACTORY_PACKAGING_WORKER,  "Công nhân đóng gói",
            Role.FACTORY_WORKER,            "Nhân viên xưởng",
            Role.FACTORY_STAFF,             "Trợ lý xưởng",
            Role.FACTORY_ACCOUNTANT,        "Kế toán xưởng",
            Role.FACTORY_MANAGER,           "Quản lý xưởng",
            Role.SUPER_FACTORY_WORKER,      "Trưởng xưởng",
            Role.FACTORY_SECURITY,          "Bảo vệ xưởng"
    );

    private final WorkOrderRepository workOrderRepo;
    private final UserRepository userRepo;
    private final FactoryKpiBonusRepository kpiBonusRepo;
    private final FactoryKpiBonusItemRepository kpiBonusItemRepo;
    private final AttendanceSheetRepository attendanceSheetRepo;
    private final AttendanceEntryRepository attendanceEntryRepo;
    private final com.nhatnam.server.repository.FactoryKpiCarryOverSeedRepository carryOverSeedRepo;

    private final ObjectProvider<FactoryKpiService> selfProvider;

    private FactoryKpiService self() {
        return selfProvider.getObject();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // API CHÍNH
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Optional<FactoryKpiBonus> find(int month, int year) {
        return kpiBonusRepo.findByMonthAndYear(month, year);
    }

    public FactoryKpiBonus getOrCompute(int month, int year) {
        return kpiBonusRepo.findByMonthAndYear(month, year)
                .orElseGet(() -> self().computeInNewTransaction(month, year));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FactoryKpiBonus computeInNewTransaction(int month, int year) {
        return kpiBonusRepo.findByMonthAndYear(month, year)
                .orElseGet(() -> compute(month, year));
    }

    @Transactional
    public FactoryKpiBonus recompute(int month, int year) {
        return recompute(month, year, null);
    }

    @Transactional
    public FactoryKpiBonus recompute(int month, int year, Long securityRate) {
        long rate = securityRate != null
                ? Math.max(0L, securityRate)
                : kpiBonusRepo.findByMonthAndYear(month, year)
                .map(b -> b.getSecurityRate() != null ? b.getSecurityRate() : SECURITY_FIXED_BONUS)
                .orElse(SECURITY_FIXED_BONUS);

        kpiBonusRepo.findByMonthAndYear(month, year).ifPresent(existing -> {
            kpiBonusItemRepo.deleteByKpiBonus_Id(existing.getId());
            kpiBonusRepo.delete(existing);
            kpiBonusRepo.flush();
        });
        return compute(month, year, rate);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TÍNH TOÁN
    // ══════════════════════════════════════════════════════════════════════════

    private FactoryKpiBonus compute(int month, int year) {
        return compute(month, year, SECURITY_FIXED_BONUS);
    }

    private FactoryKpiBonus compute(int month, int year, long securityRate) {
        long from = monthStartMs(month, year);
        long to   = monthEndMs(month, year);

        // ── 1. Tổng sản lượng hoàn thành trong tháng ──────────────────────────
        BigDecimal totalKg = sumCompletedOutput(from, to);
        BigDecimal totalTon = totalKg.divide(KG_PER_TON, 6, RoundingMode.HALF_UP);

        // ── 2. Quỹ thưởng cả phòng (làm tròn hàng nghìn) ──────────────────────
        long rawPool = totalTon.multiply(BigDecimal.valueOf(RATE_PER_TON))
                .setScale(0, RoundingMode.HALF_UP).longValue();
        long bonusPool = roundToStep(rawPool, POOL_ROUNDING_STEP, POOL_ROUNDING);

        // ── 3. Quỹ dư từ các tháng trước ──────────────────────────────────────
        List<CarryOverEntry> ledgerIn = previousLedger(month, year);
        long carryOverIn = ledgerIn.stream().mapToLong(CarryOverEntry::getAmount).sum();

        // ── 4. Có bảng chấm công chưa ─────────────────────────────────────────
        boolean hasAttendance = attendanceSheetRepo.existsByMonthAndYearAndDepartment(
                month, year, PayrollDepartment.FACTORY);

        // ── 5. Bảo vệ — mức cố định ───────────────────────────────────────────
        List<User> securityUsers = activeUsersWithRole(Role.FACTORY_SECURITY).stream()
                .filter(u -> countsForKpi(u, Role.FACTORY_SECURITY))
                .toList();
        List<FactoryKpiBonusItem> items = new ArrayList<>();
        long securityTotal = 0L;

        FactoryKpiBonus bonus = FactoryKpiBonus.builder()
                .month(month).year(year)
                .totalOutputKg(totalKg.setScale(2, RoundingMode.HALF_UP))
                .totalOutputTon(totalTon.setScale(4, RoundingMode.HALF_UP))
                .ratePerTon(RATE_PER_TON)
                .securityRate(securityRate)
                .bonusPool(bonusPool)
                .carryOverIn(carryOverIn)
                .items(new ArrayList<>())
                .build();

        for (User u : securityUsers) {
            long amount = securityRate;
            securityTotal += amount;

            items.add(FactoryKpiBonusItem.builder()
                    .kpiBonus(bonus)
                    .user(u)
                    .userFullName(u.getFullName())
                    .role(Role.FACTORY_SECURITY)
                    .roleLabel(ROLE_LABELS.get(Role.FACTORY_SECURITY))
                    .weight(0.0)
                    .fixedAmountRole(true)
                    .rawAmount(amount)
                    .amount(amount)
                    .build());
        }

        // ── 6. Các vị trí còn lại — chia theo trọng số hiệu dụng ─────────────
        Map<User, Double> weighted = effectiveWeights(month, year, hasAttendance);
        double totalWeight = weighted.values().stream().mapToDouble(Double::doubleValue).sum();

        long distributable = Math.max(0L, bonusPool - securityTotal + carryOverIn);
        long distributedTotal = 0L;

        long baseUnit = 0L;

        if (totalWeight > 0 && distributable > 0) {
            // ── B1. ĐƠN GIÁ CHUẨN ─────────────────────────────────────────────
            BigDecimal perWeight = BigDecimal.valueOf(distributable)
                    .divide(BigDecimal.valueOf(totalWeight), 6, RoundingMode.HALF_UP);
            long rawPerWeight = perWeight.setScale(0, RoundingMode.HALF_UP).longValue();
            baseUnit = roundToStep(rawPerWeight, EMPLOYEE_ROUNDING_STEP, BASE_UNIT_ROUNDING);

            // ── B1b. HẠ ĐƠN GIÁ CHUẨN CHO TỚI KHI TỔNG CHI NẰM GỌN TRONG QUỸ ──
            while (baseUnit > 0 && totalForBaseUnit(weighted, baseUnit) > distributable)
                baseUnit -= EMPLOYEE_ROUNDING_STEP;

            if (baseUnit <= 0) {
                baseUnit = 0L;
                log.warn("[KPI] Tháng {}/{}: quỹ chia {}đ không đủ một bậc {}đ cho {} người "
                                + "(tổng hệ số {}). Không chia, chuyển toàn bộ sang tháng sau.",
                        month, year, distributable, EMPLOYEE_ROUNDING_STEP,
                        weighted.size(), totalWeight);
            }

            for (Map.Entry<User, Double> e : weighted.entrySet()) {
                User u = e.getKey();
                double w = e.getValue();
                Role kpiRole = kpiRoleOf(u);

                // ── B2. Tiền từng người = làm tròn LÊN(đơn giá chuẩn × hệ số) ──
                long raw = BigDecimal.valueOf(baseUnit).multiply(BigDecimal.valueOf(w))
                        .setScale(0, RoundingMode.HALF_UP).longValue();
                long amount = roundToStep(raw, EMPLOYEE_ROUNDING_STEP, EMPLOYEE_ROUNDING);

                distributedTotal += amount;

                items.add(FactoryKpiBonusItem.builder()
                        .kpiBonus(bonus)
                        .user(u)
                        .userFullName(u.getFullName())
                        .role(kpiRole)
                        .roleLabel(ROLE_LABELS.getOrDefault(kpiRole, kpiRole != null ? kpiRole.name() : "—"))
                        .weight(w)
                        .fixedAmountRole(false)
                        .rawAmount(raw)
                        .amount(amount)
                        .build());
            }
        }

        // ── 6b. Người KHÔNG được chia vẫn phải có mặt trong danh sách với 0đ ──
        Set<Long> paidIds = new HashSet<>();
        for (User u : weighted.keySet()) paidIds.add(u.getId());

        for (User u : usersEligibleForWeight()) {
            if (paidIds.contains(u.getId())) continue;

            Role kpiRole = kpiRoleOf(u);
            if (kpiRole == null || !ROLE_WEIGHTS.containsKey(kpiRole)) continue;

            items.add(FactoryKpiBonusItem.builder()
                    .kpiBonus(bonus)
                    .user(u)
                    .userFullName(u.getFullName())
                    .role(kpiRole)
                    .roleLabel(ROLE_LABELS.getOrDefault(kpiRole, kpiRole.name()))
                    .weight(0.0)
                    .fixedAmountRole(false)
                    .rawAmount(0L)
                    .amount(0L)
                    .build());
        }

        // ── 7. Phần dư → sổ quỹ tháng sau ─────────────────────────────────────
        List<CarryOverEntry> ledger = new ArrayList<>(ledgerIn);
        long ownContribution = Math.max(0L, bonusPool - securityTotal);
        if (ownContribution > 0) ledger.add(new CarryOverEntry(month, year, ownContribution));

        List<CarryOverEntry> ledgerOut = consumeFifo(ledger, distributedTotal);
        long carryOverOut = ledgerOut.stream().mapToLong(CarryOverEntry::getAmount).sum();

        long available = ledger.stream().mapToLong(CarryOverEntry::getAmount).sum();
        if (distributedTotal > available) {
            log.error("[KPI] Tháng {}/{}: chi {}đ VƯỢT quỹ khả dụng {}đ (chênh {}đ). "
                            + "Đây là LỖI LOGIC — tổng chia phải luôn nằm trong quỹ.",
                    month, year, distributedTotal, available, distributedTotal - available);
        }

        bonus.setCarryOverDetail(writeLedger(ledgerOut));
        bonus.setSecurityTotal(securityTotal);
        bonus.setTotalWeight(totalWeight);
        bonus.setDistributedTotal(distributedTotal);
        bonus.setCarryOverOut(carryOverOut);
        bonus.getItems().addAll(items);

        FactoryKpiBonus saved = kpiBonusRepo.save(bonus);
        log.info("[KPI] Tháng {}/{}: sản lượng {}kg ({} tấn) → quỹ {}đ | bảo vệ {}đ "
                        + "| tổng hệ số {} | đơn giá chuẩn {}đ | chia {}đ | dư {}đ",
                month, year, totalKg, totalTon, bonusPool, securityTotal,
                totalWeight, baseUnit, distributedTotal, carryOverOut);
        return saved;
    }

    private long totalForBaseUnit(Map<User, Double> weighted, long baseUnit) {
        long sum = 0L;
        for (double w : weighted.values()) {
            long raw = BigDecimal.valueOf(baseUnit).multiply(BigDecimal.valueOf(w))
                    .setScale(0, RoundingMode.HALF_UP).longValue();
            sum += roundToStep(raw, EMPLOYEE_ROUNDING_STEP, EMPLOYEE_ROUNDING);
        }
        return sum;
    }

    public BigDecimal sumCompletedOutput(long fromMs, long toMs) {
        BigDecimal sum = workOrderRepo.sumCompletedOutputBetween(fromMs, toMs);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    public BigDecimal sumAllCompletedOutput() {
        BigDecimal sum = workOrderRepo.sumAllCompletedOutput();
        return sum != null ? sum : BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public List<CarryOverEntry> carryOverInDetail(int month, int year) {
        return previousLedger(month, year);
    }

    private List<CarryOverEntry> previousLedger(int month, int year) {
        YearMonth prev = YearMonth.of(year, month).minusMonths(1);
        List<CarryOverEntry> ledger = kpiBonusRepo
                .findByMonthAndYear(prev.getMonthValue(), prev.getYear())
                .map(this::readLedger)
                .orElseGet(ArrayList::new);

        for (com.nhatnam.server.entity.FactoryKpiCarryOverSeed seed
                : carryOverSeedRepo.findByApplyMonthAndApplyYearOrderBySourceYearAscSourceMonthAsc(month, year)) {
            long amt = seed.getAmount() != null ? seed.getAmount() : 0L;
            if (amt > 0)
                ledger.add(new CarryOverEntry(seed.getSourceMonth(), seed.getSourceYear(), amt));
        }

        ledger.sort(Comparator
                .comparingInt(CarryOverEntry::getYear)
                .thenComparingInt(CarryOverEntry::getMonth));

        return ledger;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // QUỸ DƯ KHAI BÁO TAY — CRUD
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<com.nhatnam.server.entity.FactoryKpiCarryOverSeed> listCarryOverSeeds() {
        return carryOverSeedRepo.findAllByOrderByApplyYearDescApplyMonthDesc();
    }

    @Transactional(readOnly = true)
    public List<com.nhatnam.server.entity.FactoryKpiCarryOverSeed> listCarryOverSeeds(int month, int year) {
        return carryOverSeedRepo
                .findByApplyMonthAndApplyYearOrderBySourceYearAscSourceMonthAsc(month, year);
    }

    @Transactional
    public com.nhatnam.server.entity.FactoryKpiCarryOverSeed addCarryOverSeed(
            int applyMonth, int applyYear,
            int sourceMonth, int sourceYear,
            long amount, String note, String actor) {

        if (applyMonth < 1 || applyMonth > 12 || sourceMonth < 1 || sourceMonth > 12)
            throw new IllegalArgumentException("Tháng không hợp lệ");
        if (amount <= 0)
            throw new IllegalArgumentException("Số tiền dư phải lớn hơn 0");

        if (YearMonth.of(sourceYear, sourceMonth).isAfter(YearMonth.of(applyYear, applyMonth).minusMonths(1)))
            throw new IllegalArgumentException(
                    "Tháng phát sinh phải trước tháng được cộng quỹ dư");

        var seed = com.nhatnam.server.entity.FactoryKpiCarryOverSeed.builder()
                .applyMonth(applyMonth).applyYear(applyYear)
                .sourceMonth(sourceMonth).sourceYear(sourceYear)
                .amount(amount)
                .note(note)
                .createdAt(System.currentTimeMillis())
                .createdBy(actor)
                .build();

        seed = carryOverSeedRepo.save(seed);
        log.info("[KPI] {} khai báo quỹ dư {}đ (nguồn T{}/{}) cộng vào T{}/{}",
                actor, amount, sourceMonth, sourceYear, applyMonth, applyYear);
        return seed;
    }

    @Transactional
    public void deleteCarryOverSeed(long id) {
        carryOverSeedRepo.deleteById(id);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRỌNG SỐ & NGÀY CÔNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * HỆ SỐ THƯỞNG theo số ngày/buổi công thực tế — thang bậc mới 2026.
     *
     * <pre>
     *   công < 15         → 0.0   (không được thưởng)
     *   15 ≤ công < 19    → 0.8   (80%)
     *   19 ≤ công ≤ 22    → 0.9   (90%)
     *   công > 22         → 1.0   (100%)
     * </pre>
     *
     * <p><b>NGOẠI LỆ ĐẶC BIỆT:</b> nhân viên trong
     * {@link #ALWAYS_FULL_ATTENDANCE_NAMES} LUÔN được trả về 1.0 — bỏ qua toàn
     * bộ dữ liệu chấm công.
     */
    public double attendanceRatio(User user, int month, int year, boolean hasAttendanceSheet) {
        if (isAlwaysFullAttendance(user)) return 1.0;

        if (!hasAttendanceSheet) return 1.0;

        AttendanceEntry entry = attendanceEntryRepo
                .findByUserAndPeriod(user.getId(), month, year)
                .orElse(null);
        if (entry == null) return 0.0;

        Integer present = entry.getPresentDays();
        double countBasis = present != null ? present.doubleValue() : 0.0;

        if (countBasis < MIN_DAYS_FOR_BONUS) return 0.0;
        if (countBasis < TIER_90_THRESHOLD)  return 0.8;
        if (countBasis <= TIER_100_THRESHOLD) return 0.9;
        return 1.0;
    }

    /**
     * Map nhân viên xưởng (trừ bảo vệ) → TRỌNG SỐ HIỆU DỤNG.
     * <pre>
     *   trọng số hiệu dụng = trọng số vị trí × hệ số parttime × tỉ lệ ngày công
     *   hệ số parttime: fulltime = 1.0 · parttime = 0.6
     * </pre>
     */
    private Map<User, Double> effectiveWeights(int month, int year, boolean hasAttendanceSheet) {
        Map<User, Double> result = new LinkedHashMap<>();

        for (User u : usersEligibleForWeight()) {
            Role role = kpiRoleOf(u);
            if (role == null) continue;

            double base  = ROLE_WEIGHTS.getOrDefault(role, 0.0);
            boolean alwaysFull = isAlwaysFullAttendance(u);

            if (hasAttendanceSheet && !alwaysFull) {
                AttendanceEntry entry = attendanceEntryRepo
                        .findByUserAndPeriod(u.getId(), month, year)
                        .orElse(null);
                if (entry != null && Boolean.TRUE.equals(entry.getPartTime())) {
                    base *= PARTTIME_WEIGHT_FACTOR;
                }
            }

            double ratio = attendanceRatio(u, month, year, hasAttendanceSheet);
            double eff   = base * ratio;

            if (eff > 0) result.put(u, round4(eff));
        }
        return result;
    }

    /** Nhân viên xưởng được tham gia chia theo trọng số (không gồm bảo vệ). */
    private List<User> usersEligibleForWeight() {
        Map<Long, User> merged = new LinkedHashMap<>();
        for (Role r : ROLE_WEIGHTS.keySet()) {
            for (User u : activeUsersWithRole(r)) merged.put(u.getId(), u);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * ROLE DÙNG ĐỂ TÍNH KPI của nhân viên.
     *
     * <p><b>Ưu tiên tuyệt đối {@code _user.payroll_role}</b>, nhưng CÓ OVERRIDE
     * THEO TÊN cho một số trường hợp đặc biệt (xem {@link #ALWAYS_PACKAGING_NAMES}).
     */
    private Role kpiRoleOf(User u) {
        Role explicit = u.getPayrollRole();
        if (explicit != null) {
            // ── NGOẠI LỆ: override theo tên (VD Ngô Thị Mỹ Hạnh) ───────────
            //   Đặt TRƯỚC mọi kiểm tra payroll_role. Nếu tên khớp danh sách
            //   đóng gói → luôn trả về FACTORY_PACKAGING_WORKER.
            Role overridden = overridePackagingRole(u, explicit);
            if (ROLE_WEIGHTS.containsKey(overridden) || overridden == Role.FACTORY_SECURITY) return overridden;
            return null;
        }

        Role lowest = u.getAllRoles().stream()
                .filter(ROLE_WEIGHTS::containsKey)
                .min(Comparator.comparingDouble(ROLE_WEIGHTS::get))
                .orElse(null);

        if (lowest == null) {
            return u.getAllRoles().contains(Role.FACTORY_SECURITY) ? Role.FACTORY_SECURITY : null;
        }

        log.warn("[KPI] Nhân viên #{} \"{}\" CHƯA có chức vụ trả lương (payroll_role). "
                        + "Tạm tính theo role thấp nhất: {} (×{}). "
                        + "Vào trang Nhân sự set Bộ phận / Chức vụ để tính đúng.",
                u.getId(), u.getFullName(), lowest, ROLE_WEIGHTS.get(lowest));
        return overridePackagingRole(u, lowest);
    }

    private boolean countsForKpi(User u, Role role) {
        Role effective = kpiRoleOf(u);
        return effective == role;
    }

    /** Nhân viên đang hoạt động (chưa khoá, chưa xoá mềm) có role chỉ định. */
    private List<User> activeUsersWithRole(Role role) {
        Map<Long, User> merged = new LinkedHashMap<>();
        for (User u : userRepo.findByRole(role))             merged.put(u.getId(), u);
        for (User u : userRepo.findByRolesContaining(role))  merged.put(u.getId(), u);
        for (User u : userRepo.findByPayrollRole(role))      merged.put(u.getId(), u);

        return merged.values().stream()
                .filter(u -> !u.isLockAccount())
                .filter(u -> !u.isDeleted())
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TIỆN ÍCH
    // ══════════════════════════════════════════════════════════════════════════

    public static long roundToStep(long value, long step, RoundingMode mode) {
        if (step <= 1) return value;
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(step), 0, mode)
                .multiply(BigDecimal.valueOf(step))
                .longValue();
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    public static long monthStartMs(int month, int year) {
        return LocalDate.of(year, month, 1).atStartOfDay(VN).toInstant().toEpochMilli();
    }

    public static long monthEndMs(int month, int year) {
        return LocalDate.of(year, month, 1).plusMonths(1)
                .atStartOfDay(VN).toInstant().toEpochMilli();
    }
}