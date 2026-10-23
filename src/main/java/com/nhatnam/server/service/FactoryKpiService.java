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
 * <h3>2. Phân bổ — MÔ HÌNH "ĐƠN GIÁ CHUẨN × TỈ LỆ" (10/2026)</h3>
 * <pre>
 *   B1.  đơn giá chuẩn (baseUnit) = làm tròn XUỐNG 100.000đ( quỹ chia / Σ roleWeight )
 *   B2.  thưởng gốc(u)            = baseUnit × roleWeight(u)
 *   B3.  tiền thực nhận(u)        = làm tròn LÊN 5.000đ(
 *                                     thưởng gốc(u) × parttimeFactor(u) × attendanceRatio(u)
 *                                   )
 *   B1b. TRẦN QUỸ: hạ baseUnit theo bước 100.000đ cho tới khi
 *        Σ(tiền thực nhận) ≤ quỹ (dùng realTotalForBaseUnit).
 *   Phần lẻ (bao gồm cả phần parttime/ratio bỏ lại) → carryOverOut.
 * </pre>
 *
 * <h3>3. Hệ số parttime & tỉ lệ ngày công (10/2026)</h3>
 * <pre>
 *   parttimeFactor   = 1.0 (fulltime) · 0.7 (parttime).
 *   attendanceRatio  = ngàyĐiLàm / standardDays, continuous (không còn bậc 15/19/22).
 *   ngàyĐiLàm        = union(ngày có chấm công, ngày có phiếu nghỉ phép đã duyệt,
 *                            ngày lễ) — không trùng, bất kể trễ/sớm/nửa buổi.
 *   standardDays     = ngày trong tháng − chủ nhật (lễ vẫn tính là chuẩn).
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

    /**
     * Bước làm tròn ĐƠN GIÁ CHUẨN (base per role weight) — hàng TRĂM NGHÌN.
     * Giữ nguyên để bảng thưởng vẫn hiển thị đơn giá tròn đẹp.
     */
    public static final long EMPLOYEE_ROUNDING_STEP = 100_000L;

    /**
     * (10/2026) Bước làm tròn TIỀN TỪNG NGƯỜI sau khi × parttime × tỉ lệ ngày
     * công — bước 5.000đ, theo yêu cầu owner (vd 846.153,8 → 850.000).
     */
    public static final long FINAL_ROUNDING_STEP = 5_000L;

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

    /**
     * (10/2026 — ĐÃ BỎ) Thang bậc cũ 15/19/22 công KHÔNG còn dùng.
     *
     * <p>Owner yêu cầu đổi sang tỉ lệ liên tục: {@code ngàyĐiLàm / standardDays}.
     * Giữ các hằng số dưới làm reference để không ai dùng nhầm lại — mọi điểm
     * đọc cũ đã được thay bằng {@link #attendanceRatio(User, int, int, boolean)}
     * phiên bản mới.
     */
    @Deprecated public static final double MIN_DAYS_FOR_BONUS = 15.0;
    @Deprecated public static final double TIER_90_THRESHOLD  = 19.0;
    @Deprecated public static final double TIER_100_THRESHOLD = 22.0;

    /**
     * Hệ số cho nhân viên parttime — áp SAU khi đã tính "thưởng gốc".
     * 70% theo yêu cầu owner.
     */
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
    // (10/2026) Tỉ lệ thưởng mới: cần ngày lễ + phiếu nghỉ phép đã duyệt để
    // tính "ngày đi làm hợp lệ" / standardDays.
    private final com.nhatnam.server.service.hr.HolidayService holidayService;
    private final com.nhatnam.server.service.hr.EmployeeRequestService employeeRequestService;

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

        // ── 6. Các vị trí còn lại — chia theo trọng số vị trí ─────────────────
        //      (10/2026) Trọng số CHỈ còn roleWeight; parttime (0.7) & tỉ lệ
        //      ngày công áp SAU khi tính "thưởng gốc", làm tròn LÊN 5.000đ.
        Map<User, Double> weighted = effectiveWeights(month, year, hasAttendance);
        double totalWeight = weighted.values().stream().mapToDouble(Double::doubleValue).sum();

        // Tính TRƯỚC hệ số parttime × tỉ lệ ngày công của từng người để dùng
        // chung cho vòng hạ baseUnit lẫn vòng chia tiền (đảm bảo cùng công thức).
        Map<User, Double> perUserFactor = new LinkedHashMap<>();
        for (User u : weighted.keySet()) {
            double parttimeFactor = 1.0;
            if (hasAttendance && !isAlwaysFullAttendance(u)) {
                AttendanceEntry pe = attendanceEntryRepo
                        .findByUserAndPeriod(u.getId(), month, year).orElse(null);
                if (pe != null && Boolean.TRUE.equals(pe.getPartTime())) {
                    parttimeFactor = PARTTIME_WEIGHT_FACTOR;
                }
            }
            double ratio = attendanceRatio(u, month, year, hasAttendance);
            perUserFactor.put(u, parttimeFactor * ratio);
        }

        long distributable = Math.max(0L, bonusPool - securityTotal + carryOverIn);
        long distributedTotal = 0L;

        long baseUnit = 0L;

        if (totalWeight > 0 && distributable > 0) {
            // ── B1. ĐƠN GIÁ CHUẨN theo trọng số vị trí thuần ──────────────────
            //      baseUnit = quỹ / Σ(roleWeight), làm tròn XUỐNG bước 100.000đ.
            //      Nhân viên "ăn full" (parttime=1, ratio=1) sẽ ra "thưởng gốc"
            //      = baseUnit × roleWeight — đây chính là số 1.000.000đ ở
            //      ví dụ owner.
            BigDecimal perWeight = BigDecimal.valueOf(distributable)
                    .divide(BigDecimal.valueOf(totalWeight), 6, RoundingMode.HALF_UP);
            long rawPerWeight = perWeight.setScale(0, RoundingMode.HALF_UP).longValue();
            baseUnit = roundToStep(rawPerWeight, EMPLOYEE_ROUNDING_STEP, BASE_UNIT_ROUNDING);

            // ── B1b. HẠ baseUnit TỚI KHI TỔNG CHI THỰC TẾ ≤ QUỸ ──────────────
            //      Dùng realTotalForBaseUnit (áp parttime × ratio + ceiling 5k)
            //      để không hạ oan. Trước đây check qua base-only ceiling 100k
            //      → mọi người bị cắt bớt, phần dư thừa ra quỹ dư.
            while (baseUnit > 0 && realTotalForBaseUnit(weighted, perUserFactor, baseUnit) > distributable)
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
                double w = e.getValue();                // trọng số vị trí thuần
                double factor = perUserFactor.getOrDefault(u, 0.0);   // parttime × ratio
                Role kpiRole = kpiRoleOf(u);

                // ── B2. "Thưởng gốc" theo vị trí = đơn giá chuẩn × trọng số.
                // ── B3. Nhân với hệ số parttime × tỉ lệ ngày công.
                // ── B4. Làm tròn LÊN bước 5.000đ.
                long raw = BigDecimal.valueOf(baseUnit)
                        .multiply(BigDecimal.valueOf(w))
                        .multiply(BigDecimal.valueOf(factor))
                        .setScale(0, RoundingMode.HALF_UP).longValue();
                long amount = raw > 0
                        ? roundToStep(raw, FINAL_ROUNDING_STEP, EMPLOYEE_ROUNDING)
                        : 0L;

                distributedTotal += amount;

                items.add(FactoryKpiBonusItem.builder()
                        .kpiBonus(bonus)
                        .user(u)
                        .userFullName(u.getFullName())
                        .role(kpiRole)
                        .roleLabel(ROLE_LABELS.getOrDefault(kpiRole, kpiRole != null ? kpiRole.name() : "—"))
                        .weight(w * factor)   // trọng số hiệu dụng để báo cáo
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

    /**
     * (10/2026) Tổng chi THỰC TẾ cho 1 giá trị baseUnit đã cho — tính theo
     * đúng công thức B2–B5 trong {@link #generateFactoryKpi}
     * (base × parttime × ratio, làm tròn LÊN 5.000đ). Dùng cho vòng "hạ
     * baseUnit cho tới khi vừa quỹ": trước đây check dùng công thức cũ
     * (chỉ base × 100k ceiling) nên baseUnit bị hạ oan, mọi người ăn ít đi.
     */
    private long realTotalForBaseUnit(Map<User, Double> weighted,
                                      Map<User, Double> perUserFactor,
                                      long baseUnit) {
        long sum = 0L;
        for (Map.Entry<User, Double> e : weighted.entrySet()) {
            double factor = perUserFactor.getOrDefault(e.getKey(), 0.0);
            long raw = BigDecimal.valueOf(baseUnit)
                    .multiply(BigDecimal.valueOf(e.getValue()))
                    .multiply(BigDecimal.valueOf(factor))
                    .setScale(0, RoundingMode.HALF_UP).longValue();
            if (raw <= 0) continue;
            sum += roundToStep(raw, FINAL_ROUNDING_STEP, EMPLOYEE_ROUNDING);
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
     * TỈ LỆ NGÀY CÔNG của nhân viên trong tháng (10/2026 — mới).
     *
     * <pre>
     *   attendanceRatio = ngàyĐiLàm / standardDays
     *   standardDays    = số ngày trong tháng − chủ nhật (lễ VẪN tính là chuẩn)
     *   ngàyĐiLàm       = số ngày THỎA một trong các điều kiện (union, mỗi
     *                     ngày đếm 1 lần, bất kể trễ/sớm/nửa buổi):
     *                       1. Có chấm công (ít nhất một đầu VÀO/RA);
     *                       2. Có phiếu nghỉ phép ĐÃ DUYỆT phủ ngày đó (cả
     *                          ngày hay nửa buổi, có lương hay bị trừ công —
     *                          miễn là KHÔNG phải "nghỉ không phép" bị ép 0);
     *                       3. Là ngày lễ (file chấm công hay cấu hình công
     *                          ty có đánh dấu lễ).
     * </pre>
     *
     * <p>Không còn thang bậc 15/19/22; đi làm đủ 26/26 → 1.0, 22/26 → 0.8462,
     * dưới 1 ngày vẫn tính tỉ lệ chứ không ép 0.
     *
     * <p>NGOẠI LỆ: {@link #ALWAYS_FULL_ATTENDANCE_NAMES} LUÔN trả 1.0; chưa
     * có bảng chấm công tháng đó → 1.0 (không trừ oan).
     */
    public double attendanceRatio(User user, int month, int year, boolean hasAttendanceSheet) {
        if (isAlwaysFullAttendance(user)) return 1.0;
        if (!hasAttendanceSheet) return 1.0;

        YearMonth ym = YearMonth.of(year, month);
        int daysInMonth = ym.lengthOfMonth();

        // Mẫu số = ngày trong tháng − chủ nhật (= standardWorkdaysInMonth).
        int sundayCount = 0;
        for (int d = 1; d <= daysInMonth; d++) {
            if (ym.atDay(d).getDayOfWeek() == java.time.DayOfWeek.SUNDAY) sundayCount++;
        }
        int standardDays = daysInMonth - sundayCount;
        if (standardDays <= 0) return 0.0;

        int qualifying = countBonusWorkingDays(user, month, year, ym, daysInMonth);
        if (qualifying <= 0) return 0.0;
        return Math.min(1.0, qualifying / (double) standardDays);
    }

    /**
     * Số ngày hợp lệ trong tháng để tính tỉ lệ thưởng — xem tài liệu
     * {@link #attendanceRatio}. Chỉ dành cho KPI xưởng; CN không tính (lễ
     * trùng CN vẫn bỏ, không ảnh hưởng tử/mẫu).
     */
    private int countBonusWorkingDays(User user, int month, int year,
                                      YearMonth ym, int daysInMonth) {
        AttendanceEntry entry = attendanceEntryRepo
                .findByUserAndPeriod(user.getId(), month, year)
                .orElse(null);

        // Day-of-month → có punch
        Set<Integer> punched = new HashSet<>();
        if (entry != null && entry.getDailyJson() != null && !entry.getDailyJson().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(entry.getDailyJson());
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                        Integer d = null;
                        if (n.hasNonNull("d")) d = n.get("d").asInt();
                        else if (n.hasNonNull("date")) {
                            try { d = LocalDate.parse(n.get("date").asText()).getDayOfMonth(); }
                            catch (Exception ignored) {}
                        }
                        if (d == null) continue;
                        String in = n.hasNonNull("in") ? n.get("in").asText() : null;
                        String out = n.hasNonNull("out") ? n.get("out").asText() : null;
                        if ((in != null && !in.isBlank()) || (out != null && !out.isBlank())) {
                            punched.add(d);
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("[KPI] Parse dailyJson user {} lỗi: {}", user.getId(), ex.getMessage());
            }
        }

        // Day-of-month → có phiếu nghỉ phép đã duyệt (có lương / trừ phép /
        // mix) — KHÔNG tính "nghỉ không phép đã duyệt" (zeroDay).
        Map<Integer, com.nhatnam.server.service.hr.EmployeeRequestService.DayEffect> effects;
        try {
            effects = employeeRequestService.effectsForUser(user.getId(), month, year);
        } catch (Exception ex) {
            effects = Map.of();
        }

        Set<LocalDate> holidays;
        try {
            holidays = holidayService.datesOfMonth(month, year);
        } catch (Exception ex) {
            holidays = Set.of();
        }

        int count = 0;
        for (int d = 1; d <= daysInMonth; d++) {
            LocalDate date = ym.atDay(d);
            if (date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) continue;

            boolean isHoliday = holidays.contains(date);
            boolean hasPunch  = punched.contains(d);
            var eff = effects.get(d);
            boolean approvedLeave = eff != null && !eff.zeroDay()
                    && (eff.fullDayCredit() || eff.minCredit() > 0);

            if (isHoliday || hasPunch || approvedLeave) count++;
        }
        return count;
    }

    /**
     * (10/2026) Map nhân viên xưởng (trừ bảo vệ) → TRỌNG SỐ VỊ TRÍ thuần.
     *
     * <p>Chính sách mới của owner: parttime (0.7) và tỉ lệ ngày công
     * ({@link #attendanceRatio}) KHÔNG còn áp vào trọng số nữa — thay vào đó
     * được nhân SAU khi đã tính "thưởng gốc" (base × đơn giá chuẩn) trong
     * {@link #generateFactoryKpi}. Nhờ vậy, bảng thưởng gốc giữa fulltime và
     * parttime có cùng vai trò là bằng nhau, parttime chỉ bị cắt 30% ở bước
     * cuối, không ảnh hưởng tới việc chia quỹ cho người khác.
     *
     * <p>Giữ cả nhân viên ratio = 0 (không có công nào) để họ vẫn hiển thị
     * trên bảng, nhưng nhận 0đ.
     */
    private Map<User, Double> effectiveWeights(int month, int year, boolean hasAttendanceSheet) {
        Map<User, Double> result = new LinkedHashMap<>();
        for (User u : usersEligibleForWeight()) {
            Role role = kpiRoleOf(u);
            if (role == null) continue;
            double base = ROLE_WEIGHTS.getOrDefault(role, 0.0);
            if (base > 0) result.put(u, round4(base));
        }
        return result;
    }

    /**
     * Nhân viên xưởng được tham gia chia theo trọng số (không gồm bảo vệ).
     *
     * <p>(10/2026) LOẠI nhân viên đang nghỉ thai sản theo chế độ ra khỏi danh
     * sách này: owner không muốn họ xuất hiện trên bảng Bonus KPI xưởng, và
     * cũng không cho họ "ăn phần" chia quỹ (phần họ bỏ lại tự ra `carryOverOut`
     * — bảng thưởng tháng sau có thêm; nếu tính vào Σ roleWeight, các nhân
     * viên còn lại bị chia nhỏ đi và baseUnit tụt mất 1 bậc 100.000đ).
     */
    private List<User> usersEligibleForWeight() {
        Map<Long, User> merged = new LinkedHashMap<>();
        for (Role r : ROLE_WEIGHTS.keySet()) {
            for (User u : activeUsersWithRole(r)) merged.put(u.getId(), u);
        }
        merged.values().removeIf(FactoryKpiService::isMaternityLeave);
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