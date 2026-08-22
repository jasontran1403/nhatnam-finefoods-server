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
 *   → Lệnh tạo 30/5, bắt đầu chiều 30/5, XONG ngày 1/6 ⇒ tính cho THÁNG 6.
 *     (mốc duy nhất là NGÀY HOÀN THÀNH, không phải ngày tạo/bắt đầu)
 *
 *   totalOutputTon = totalOutputKg / 1000
 *   bonusPool      = làm tròn(totalOutputTon × RATE_PER_TON) về hàng NGHÌN
 * </pre>
 *
 * <p><b>⚠ LƯU Ý VỀ CÁCH LÀM TRÒN QUỸ:</b> yêu cầu ghi "làm tròn LÊN hàng nghìn"
 * nhưng ví dụ đưa ra lại là làm tròn XUỐNG:
 * <pre>
 *   7081.20 kg → 7.0812 tấn × 1.400.000 = 9.913.680
 *     • làm tròn LÊN   hàng nghìn → 9.914.000
 *     • làm tròn XUỐNG hàng nghìn → 9.913.000  ← khớp phiếu thưởng thực tế
 * </pre>
 * Mã nguồn theo PHIẾU THỰC TẾ (làm tròn xuống). Muốn đổi sang làm tròn lên chỉ
 * cần sửa hằng số {@link #POOL_ROUNDING} thành {@link RoundingMode#CEILING}.
 *
 * <h3>2. Phân bổ — MÔ HÌNH "ĐƠN GIÁ CHUẨN"</h3>
 * <pre>
 *   • Bảo vệ xưởng (FACTORY_SECURITY): mức KHOÁN cố định (mặc định
 *     {@value #SECURITY_FIXED_BONUS}đ/người, OWNER nhập lại được khi tính KPI).
 *     KHÔNG chia bậc theo ngày công vì bảo vệ không nằm trong bảng chấm công.
 *   • Phần còn lại + quỹ dư tháng trước → chia 2 BƯỚC:
 *
 *       B1. đơn giá chuẩn = làm tròn XUỐNG trăm nghìn( quỹ chia / tổng hệ số )
 *           ⇒ chính là mức của CÔNG NHÂN SẢN XUẤT (hệ số 1,0)
 *
 *       B2. tiền mỗi người = làm tròn LÊN trăm nghìn( đơn giá chuẩn × hệ số hiệu dụng )
 *           hệ số hiệu dụng = hệ số vị trí × tỉ lệ ngày công
 *
 *       B1b. TRẦN QUỸ: nếu tổng chi ở B2 vượt quỹ chia (do làm tròn lên), hạ đơn
 *            giá chuẩn xuống từng bậc 100.000 rồi tính lại, tới khi vừa vặn.
 *
 *   • Phần lẻ → carryOverOut, tự động cộng vào quỹ tháng sau.
 * </pre>
 *
 * <p><b>TỔNG CHI KHÔNG BAO GIỜ VƯỢT QUỸ.</b> Thưởng lấy từ quỹ nên trần cứng là
 * {@code sản lượng × đơn giá + quỹ dư các tháng trước − thưởng cố định của bảo vệ}.
 * Trước khi có bước B1b, việc làm tròn LÊN cho từng người khiến tổng chi nhỉnh hơn
 * quỹ vài trăm nghìn; phần vượt bị nuốt khi trừ vào sổ và quỹ dư tháng sau âm thầm
 * về 0. Nay đơn giá chuẩn được hạ cho tới khi tổng chi lọt vào quỹ, phần còn thừa
 * mới là carryOverOut thật.
 *
 * <p>Bảo vệ xưởng CHỈ nhận mức khoán cố định, không tham gia chia phần còn lại —
 * họ không có mặt trong {@link #ROLE_WEIGHTS}.
 *
 * <p><b>Vì sao không nhân thẳng rồi làm tròn xuống như trước:</b> cách cũ cho ra
 * số tiền trôi theo phần lẻ của quỹ, khiến hai vị trí khác hệ số rơi vào cùng một
 * bậc trăm nghìn (Nhân viên xưởng ×1,1125 bằng Trưởng xưởng ×1,5). Neo vào đơn giá
 * chuẩn đã tròn trăm nghìn thì tỉ lệ giữa các vị trí luôn giữ đúng.
 *
 * <h3>3. Ngưỡng ngày công</h3>
 * <pre>
 *   công thực tế &lt; 10           → KHÔNG được thưởng KPI
 *   10 ≤ công thực tế &lt; 20      → trần 50% (xem PARTIAL_BONUS_FACTOR)
 *   công thực tế ≥ 20           → chia theo tỉ lệ công/công chuẩn (tối đa 100%)
 * </pre>
 *
 * <h3>4. Ví dụ đối chiếu — tháng 6/2026</h3>
 * <pre>
 *   Sản lượng 7.081,20 kg → quỹ 9.913.000đ, trừ 300.000đ bảo vệ = 9.613.000đ
 *   Nhân sự: 1 trưởng xưởng · 1 trợ lý · 1 kế toán xưởng · 1 quản lý xưởng
 *            · 6 CN sản xuất đủ công · 1 CN làm 15/24 công (trần 50% → ×0,5)
 *            · 1 bảo vệ · 1 kế toán nghỉ thai sản (0 công → 0đ)
 *
 *   Tổng hệ số 11,3375 → 9.613.000 / 11,3375 = 847.895 → đơn giá chuẩn 800.000đ
 *     Trưởng xưởng      ×1,5000 → 1.200.000
 *     Trợ lý xưởng      ×1,1125 →   890.000 →   900.000
 *     Kế toán xưởng     ×1,1125 →   890.000 →   900.000
 *     Quản lý xưởng     ×1,1125 →   890.000 →   900.000
 *     CN sản xuất ×6    ×1,0000 →   800.000 mỗi người
 *     CN 15 ngày        ×0,5000 →   400.000
 *     Bảo vệ                        300.000 (cố định)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryKpiService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    // ══════════════════════════════════════════════════════════════════════════
    // THAM SỐ CẤU HÌNH — sửa ở đây là đổi toàn bộ cách tính
    // ══════════════════════════════════════════════════════════════════════════

    /** Đơn giá thưởng trên 1 TẤN sản lượng (VNĐ). */
    public static final long RATE_PER_TON = 1_400_000L;

    /** Mức thưởng CỐ ĐỊNH cho bảo vệ xưởng (VNĐ/người/tháng). */
    /**
     * MỨC MẶC ĐỊNH cho bảo vệ xưởng — dùng làm giá trị gợi ý trên ô nhập của
     * OWNER và cho các tháng chưa từng tính. Mức thực tế của từng tháng nằm ở
     * {@code FactoryKpiBonus.securityRate}.
     */
    public static final long SECURITY_FIXED_BONUS = 300_000L;

    /** Bước làm tròn quỹ thưởng cả phòng: hàng NGHÌN. */
    public static final long POOL_ROUNDING_STEP = 1_000L;

    /**
     * Cách làm tròn quỹ. FLOOR = làm tròn xuống (khớp phiếu thưởng thực tế:
     * 9.913.680 → 9.913.000). Đổi sang {@link RoundingMode#CEILING} nếu muốn
     * làm tròn lên (9.914.000).
     */
    public static final RoundingMode POOL_ROUNDING = RoundingMode.FLOOR;

    /**
     * Bước làm tròn hàng TRĂM NGHÌN — áp dụng cho cả ĐƠN GIÁ CHUẨN lẫn tiền của
     * từng nhân viên.
     *
     * <h4>MÔ HÌNH CHIA THƯỞNG (2 bước — đây là điểm khác biệt quan trọng)</h4>
     * <pre>
     *   B1. đơn giá chuẩn = làm tròn XUỐNG( quỹ chia / tổng hệ số )
     *       → đây chính là mức của CÔNG NHÂN SẢN XUẤT (hệ số 1.0)
     *
     *   B2. tiền mỗi người = làm tròn LÊN( đơn giá chuẩn × hệ số hiệu dụng )
     * </pre>
     *
     * <p><b>Vì sao KHÔNG chia thẳng {@code perWeight × hệ số} rồi làm tròn:</b>
     * cách cũ khiến số tiền của mọi người trôi theo phần lẻ của quỹ. Hai vị trí
     * khác hệ số (VD ×1.1125 và ×1.5) có thể rơi vào CÙNG MỘT bậc trăm nghìn sau
     * khi làm tròn xuống → Nhân viên xưởng bằng Trưởng xưởng. Neo vào đơn giá
     * chuẩn tròn trăm nghìn thì khoảng cách giữa các vị trí luôn giữ đúng tỉ lệ.
     *
     * <h4>Đối chiếu phiếu thưởng T6/2026</h4>
     * <pre>
     *   quỹ chia 9.613.000 / tổng hệ số 11,4625 = 838.638 → đơn giá chuẩn 800.000
     *     Công nhân sản xuất ×1,0000 →   800.000
     *     Kế toán / Trợ lý / NV / QL ×1,1125 →   890.000 → 900.000
     *     Trưởng xưởng       ×1,5000 → 1.200.000
     *     Ms Dung (15/24 công) ×0,5   →   400.000
     * </pre>
     */
    public static final long EMPLOYEE_ROUNDING_STEP = 100_000L;

    /** Làm tròn XUỐNG khi tính ĐƠN GIÁ CHUẨN (mức của công nhân sản xuất). */
    public static final RoundingMode BASE_UNIT_ROUNDING = RoundingMode.FLOOR;

    /** Làm tròn LÊN khi quy tiền cho từng nhân viên (890.000 → 900.000). */
    public static final RoundingMode EMPLOYEE_ROUNDING = RoundingMode.CEILING;

    /**
     * Trần tỉ lệ ngày công. Đặt 1.0 nghĩa là làm dư công cũng chỉ tính tối đa
     * 100% — tăng ca đã được trả riêng, không cộng dồn vào thưởng KPI.
     * Muốn cho phép vượt thì nâng hằng số này lên.
     */
    public static final double MAX_ATTENDANCE_RATIO = 1.0;

    /** Số kg trong 1 tấn. */
    private static final BigDecimal KG_PER_TON = BigDecimal.valueOf(1000);

    // ══════════════════════════════════════════════════════════════════════════
    // SỔ QUỸ DƯ — giữ nguồn gốc từng khoản để hiển thị "của tháng nào"
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

    /** Đọc sổ quỹ dư từ JSON. Dữ liệu hỏng / bản ghi cũ chưa có cột → sổ rỗng. */
    private List<CarryOverEntry> readLedger(FactoryKpiBonus b) {
        if (b == null || b.getCarryOverDetail() == null || b.getCarryOverDetail().isBlank()) {
            // Bản ghi CŨ (tính trước khi có sổ) chỉ có tổng carryOverOut. Quy về
            // một khoản duy nhất mang tên chính tháng đó để không mất tiền.
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

    /**
     * Trừ {@code spend} khỏi sổ theo thứ tự CŨ NHẤT TRƯỚC (FIFO).
     * Khoản nào hết thì loại khỏi sổ. Trả về sổ còn lại.
     */
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

    /**
     * TRỌNG SỐ chia thưởng theo VỊ TRÍ.
     * Nhân viên sản xuất = 1.00 (mốc chuẩn), các vị trí khác cộng thêm %.
     * Bảo vệ KHÔNG có trong map này vì hưởng mức cố định.
     */
    /**
     * HỆ SỐ CHIA THƯỞNG THEO VỊ TRÍ — bội số của mức CHUẨN (base).
     *
     * <pre>
     *   Công nhân sản xuất = 1,0  → đúng bằng base       (bước 100.000đ)
     *   Trợ lý / QL / KT / NV xưởng = 1,2 → base + 20%   (bước 120.000đ)
     *   Trưởng xưởng       = 1,5  → base + 50%           (bước 150.000đ)
     * </pre>
     *
     * <p>Cách hiểu theo "bước": nâng base lên mỗi 100.000đ thì quỹ phải chi thêm
     * 100.000 cho mỗi công nhân, 120.000 cho mỗi nhân viên nhóm 1,2 và 150.000 cho
     * mỗi trưởng xưởng. Số bước tối đa mà quỹ gánh nổi chính là base.
     */
    public static final Map<Role, Double> ROLE_WEIGHTS = Map.of(
            Role.FACTORY_PRODUCTION_WORKER, 1.0,   // Công nhân sản xuất — MỐC CHUẨN (base)
            Role.FACTORY_WORKER,            1.2,   // Nhân viên xưởng    — base + 20%
            Role.FACTORY_STAFF,             1.2,   // Trợ lý xưởng       — base + 20%
            Role.FACTORY_ACCOUNTANT,        1.2,   // Kế toán xưởng      — base + 20%
            Role.FACTORY_MANAGER,           1.2,   // Quản lý xưởng      — base + 20%
            Role.SUPER_FACTORY_WORKER,      1.5    // Trưởng xưởng       — base + 50%
            // FACTORY_SECURITY (Bảo vệ xưởng) KHÔNG có ở đây — hưởng mức CỐ ĐỊNH
            // 300.000đ/tháng, xem SECURITY_FIXED_BONUS.
    );

    // ══════════════════════════════════════════════════════════════════════════
    // NGƯỠNG NGÀY CÔNG — nghỉ quá nhiều thì cắt / giảm thưởng
    // ══════════════════════════════════════════════════════════════════════════

    /** Dưới ngưỡng này (số công THỰC TẾ trong tháng) thì KHÔNG được thưởng KPI. */
    public static final double MIN_DAYS_FOR_BONUS = 10.0;

    /** Từ {@link #MIN_DAYS_FOR_BONUS} đến dưới ngưỡng này thì chỉ hưởng {@link #PARTIAL_BONUS_FACTOR}. */
    public static final double FULL_BONUS_DAYS_THRESHOLD = 20.0;

    /** Mức hưởng của nhóm 10 ≤ công < 20. */
    public static final double PARTIAL_BONUS_FACTOR = 0.5;

    /** Nhãn hiển thị vị trí trên phiếu lương. */
    public static final Map<Role, String> ROLE_LABELS = Map.of(
            Role.FACTORY_PRODUCTION_WORKER, "Công nhân sản xuất",
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

    /**
     * THAM CHIẾU CHÍNH MÌNH qua Spring proxy — bắt buộc để {@code REQUIRES_NEW}
     * có hiệu lực khi gọi nội bộ (gọi thẳng {@code this.method()} sẽ BỎ QUA
     * annotation {@code @Transactional}).
     */
    private final ObjectProvider<FactoryKpiService> selfProvider;

    private FactoryKpiService self() {
        return selfProvider.getObject();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // API CHÍNH
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Lấy (hoặc tính mới nếu chưa có) bảng thưởng KPI của tháng.
     * Kết quả được LƯU LẠI để phiếu lương tháng cũ ổn định số liệu.
     */
    @Transactional(readOnly = true)
    public Optional<FactoryKpiBonus> find(int month, int year) {
        return kpiBonusRepo.findByMonthAndYear(month, year);
    }

    /**
     * Lấy (hoặc tính mới nếu chưa có) bảng thưởng KPI của tháng.
     *
     * <p><b>QUAN TRỌNG — vì sao tách transaction:</b> hàm này được gọi từ những
     * luồng CHỈ ĐỌC (VD {@code FactoryPayrollService.departmentPayroll} chạy
     * trong {@code @Transactional(readOnly = true)}). Nếu để phần INSERT chạy
     * chung transaction đó, MySQL trả lỗi
     * {@code "Connection is read-only. Queries leading to data modification are
     * not allowed"} và transaction bị đánh dấu rollback-only → nổ
     * {@code UnexpectedRollbackException} khi controller trả về.
     *
     * <p>Vì vậy phần TÍNH & GHI được đẩy sang một transaction ĐỘC LẬP
     * ({@code REQUIRES_NEW}). Transaction read-only bên ngoài được tạm dừng,
     * bản ghi KPI được ghi và commit riêng, rồi transaction ngoài chạy tiếp.
     * Nếu tính KPI lỗi thì chỉ transaction con rollback — luồng đọc bảng lương
     * bên ngoài vẫn an toàn.
     */
    public FactoryKpiBonus getOrCompute(int month, int year) {
        return kpiBonusRepo.findByMonthAndYear(month, year)
                .orElseGet(() -> self().computeInNewTransaction(month, year));
    }

    /**
     * Tính KPI trong MỘT TRANSACTION RIÊNG — dùng cho các luồng chỉ đọc.
     * Kiểm tra lại một lần nữa bên trong transaction mới để tránh 2 request
     * đồng thời cùng tạo trùng bản ghi cho một tháng.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FactoryKpiBonus computeInNewTransaction(int month, int year) {
        return kpiBonusRepo.findByMonthAndYear(month, year)
                .orElseGet(() -> compute(month, year));
    }

    /**
     * Tính lại (ghi đè) bảng thưởng KPI của tháng.
     * Được gọi tự động sau mỗi lần import bảng chấm công.
     */
    @Transactional
    public FactoryKpiBonus recompute(int month, int year) {
        return recompute(month, year, null);
    }

    /**
     * Tính lại (ghi đè) bảng thưởng KPI của tháng.
     *
     * @param securityRate mức thưởng cố định cho MỘT bảo vệ xưởng.
     *        {@code null} = GIỮ NGUYÊN mức đã dùng ở lần tính trước của tháng
     *        này, hoặc {@link #SECURITY_FIXED_BONUS} nếu tháng chưa từng tính.
     *
     *        <p>Phân biệt {@code null} với 0 là có chủ đích: hàm này còn được gọi
     *        TỰ ĐỘNG sau mỗi lần import bảng chấm công, lúc đó không có ý định
     *        đổi mức. Nếu mặc định về 300.000 thì mỗi lần tải lại bảng chấm công
     *        sẽ âm thầm xoá con số OWNER vừa chỉnh.
     */
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

        // ── 3. Quỹ dư từ các tháng trước (kèm nguồn gốc từng khoản) ───────────
        List<CarryOverEntry> ledgerIn = previousLedger(month, year);
        long carryOverIn = ledgerIn.stream().mapToLong(CarryOverEntry::getAmount).sum();

        // ── 4. Tỉ lệ ngày công của từng người trong tháng ─────────────────────
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
            // ── BẢO VỆ XƯỞNG KHÔNG CHẤM CÔNG → hưởng ĐỦ mức khoán ────────────
            //
            //   Trước đây mức thưởng được chia bậc theo ngày công
            //   (0đ nếu dưới 10 công · nửa mức nếu 10–20 công · đủ mức nếu hơn).
            //   Nay bảo vệ đã bị loại khỏi khâu import bảng chấm công
            //   (PayrollDepartmentResolver.attendanceEmployeesOf) nên KHÔNG còn
            //   bản ghi AttendanceEntry nào cho họ.
            //
            //   Giữ lại cách chia bậc sẽ là một lỗi TRẢ THIẾU âm thầm:
            //   attendanceRatio() không tìm thấy bản ghi thì trả 0.0, và mọi bảo
            //   vệ sẽ nhận 0đ ngay khi tháng đó có bảng chấm công. Việc chấm công
            //   ca kíp thuộc trách nhiệm đơn vị cung cấp dịch vụ bảo vệ, không
            //   phải của bảng chấm công xưởng.
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
            // ── B1. ĐƠN GIÁ CHUẨN = mức của công nhân sản xuất (hệ số 1.0) ────
            //   Chia quỹ cho tổng hệ số rồi làm tròn XUỐNG hàng trăm nghìn.
            BigDecimal perWeight = BigDecimal.valueOf(distributable)
                    .divide(BigDecimal.valueOf(totalWeight), 6, RoundingMode.HALF_UP);
            long rawPerWeight = perWeight.setScale(0, RoundingMode.HALF_UP).longValue();
            baseUnit = roundToStep(rawPerWeight, EMPLOYEE_ROUNDING_STEP, BASE_UNIT_ROUNDING);

            // ── B1b. HẠ ĐƠN GIÁ CHUẨN CHO TỚI KHI TỔNG CHI NẰM GỌN TRONG QUỸ ──
            //
            //   Bước B2 làm tròn LÊN trăm nghìn cho từng người (960.000 → 1.000.000).
            //   Chỉ chia đúng "quỹ ÷ tổng hệ số" rồi làm tròn lên thì tổng chi luôn
            //   nhỉnh hơn quỹ — mỗi nhân viên hệ số 1,2 hoặc 1,5 đội thêm tới
            //   100.000đ. Với ~10 người xưởng, phần vượt lên tới vài trăm nghìn và
            //   bị nuốt mất khi trừ vào sổ quỹ (quỹ dư tháng sau về 0 một cách âm thầm).
            //
            //   Thưởng phải lấy TỪ quỹ nên không được vượt quỹ. Hạ đơn giá chuẩn
            //   xuống từng bậc 100.000 cho tới khi tổng chi vừa vặn.
            //
            //   Vì sao vòng lặp này luôn dừng và luôn cho kết quả TỐI ƯU: tiền của
            //   mỗi người là hàm KHÔNG GIẢM theo baseUnit, nên tổng chi cũng không
            //   giảm. Hạ dần từ mức cao nhất xuống, giá trị đầu tiên lọt vào quỹ
            //   chính là đơn giá chuẩn LỚN NHẤT còn khả thi. Chạm 0 thì dừng hẳn.
            while (baseUnit > 0 && totalForBaseUnit(weighted, baseUnit) > distributable)
                baseUnit -= EMPLOYEE_ROUNDING_STEP;

            if (baseUnit <= 0) {
                // Quỹ không đủ để mỗi người nhận nổi một bậc 100.000 (sản lượng quá
                // thấp, hoặc quân số quá đông). Không chia, toàn bộ dồn sang tháng sau.
                baseUnit = 0L;
                log.warn("[KPI] Tháng {}/{}: quỹ chia {}đ không đủ một bậc {}đ cho {} người "
                                + "(tổng hệ số {}). Không chia, chuyển toàn bộ sang tháng sau.",
                        month, year, distributable, EMPLOYEE_ROUNDING_STEP,
                        weighted.size(), totalWeight);
            }

            // baseUnit = 0 thì vẫn duyệt hết: mọi người xuất hiện trong phiếu với
            // 0đ. Bỏ hẳn vòng lặp sẽ khiến họ BIẾN MẤT khỏi bảng thưởng, kế toán
            // không phân biệt được "được 0đ" với "bị sót tên".
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
        //   Gồm: nghỉ thai sản / nghỉ dài (0 công), nghỉ quá nhiều (<10 công), và
        //   toàn bộ nhân sự xưởng khi tháng đó không có gì để chia.
        //   Trước đây họ bị loại khỏi map `weighted` nên BIẾN MẤT khỏi phiếu
        //   thưởng — kế toán không đối chiếu được là "0đ" hay "bị sót".
        // So khớp bằng ID, KHÔNG dùng weighted.containsKey(u): hai lần truy vấn
        // repository có thể trả về hai instance User khác nhau cho cùng một người.
        Set<Long> paidIds = new HashSet<>();
        for (User u : weighted.keySet()) paidIds.add(u.getId());

        for (User u : usersEligibleForWeight()) {
            if (paidIds.contains(u.getId())) continue;

            Role kpiRole = kpiRoleOf(u);
            // Bỏ qua người hưởng lương ở bộ phận khác (chỉ kiêm role xưởng)
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
        //   Sổ đầu vào = quỹ dư cũ (cũ nhất trước), rồi mới tới phần của CHÍNH
        //   tháng này. Tiêu FIFO nên tiền cũ luôn được chia trước.
        List<CarryOverEntry> ledger = new ArrayList<>(ledgerIn);
        long ownContribution = Math.max(0L, bonusPool - securityTotal);
        if (ownContribution > 0) ledger.add(new CarryOverEntry(month, year, ownContribution));

        List<CarryOverEntry> ledgerOut = consumeFifo(ledger, distributedTotal);
        long carryOverOut = ledgerOut.stream().mapToLong(CarryOverEntry::getAmount).sum();

        // Chốt chặn: sau khi có bước hạ đơn giá chuẩn ở B1b thì tổng chi KHÔNG BAO
        // GIỜ được vượt quỹ khả dụng. Còn vượt nghĩa là có đường tính tiền khác lọt
        // ra ngoài vòng kiểm soát (VD thêm một nhóm hưởng cố định mới mà quên trừ
        // vào quỹ trước khi chia) — phải thấy ngay trong log chứ không im lặng.
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

    /**
     * TỔNG TIỀN PHẢI CHI nếu dùng {@code baseUnit} làm đơn giá chuẩn.
     *
     * <p>Lặp lại đúng công thức của bước B2 (làm tròn LÊN trăm nghìn cho từng
     * người) để thử trước xem có vượt quỹ không, trước khi thực sự ghi số vào
     * phiếu thưởng.
     *
     * <p>Hàm KHÔNG GIẢM theo {@code baseUnit} — cơ sở để vòng lặp hạ dần ở B1b
     * dừng đúng tại đơn giá chuẩn lớn nhất còn nằm trong quỹ.
     */
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
     * Tổng sản lượng (kg) của các lệnh sản xuất ĐÃ HOÀN THÀNH trong khoảng thời gian.
     * Mốc tính là {@code actualEndDate} — ngày hoàn thành thực tế.
     */
    public BigDecimal sumCompletedOutput(long fromMs, long toMs) {
        BigDecimal sum = workOrderRepo.sumCompletedOutputBetween(fromMs, toMs);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /** Tổng sản lượng của TẤT CẢ lệnh sản xuất đã hoàn thành (dùng cho card dashboard). */
    public BigDecimal sumAllCompletedOutput() {
        BigDecimal sum = workOrderRepo.sumAllCompletedOutput();
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /**
     * SỔ QUỸ DƯ ĐẦU VÀO của tháng — dùng để hiển thị trên phiếu lương
     * ("513.000đ gồm 113.000đ của T6 và 400.000đ của T7").
     */
    @Transactional(readOnly = true)
    public List<CarryOverEntry> carryOverInDetail(int month, int year) {
        return previousLedger(month, year);
    }

    /**
     * SỔ QUỸ DƯ mang sang từ tháng liền trước.
     *
     * <p>Chỉ nhìn tháng N-1 là đủ: mỗi tháng luôn ghi lại TOÀN BỘ phần chưa tiêu
     * (gồm cả khoản thừa kế từ các tháng trước nữa), nên sổ của tháng N-1 đã là
     * ảnh chụp đầy đủ. Sổ rỗng nếu tháng trước chưa được tính.
     */
    private List<CarryOverEntry> previousLedger(int month, int year) {
        YearMonth prev = YearMonth.of(year, month).minusMonths(1);
        List<CarryOverEntry> ledger = kpiBonusRepo
                .findByMonthAndYear(prev.getMonthValue(), prev.getYear())
                .map(this::readLedger)
                .orElseGet(ArrayList::new);

        // ── Quỹ dư KHAI BÁO TAY ───────────────────────────────────────────────
        // Tháng trước chia có dư nhưng chưa từng được tính trên app (giai đoạn mới
        // chuyển đổi) ⇒ không có FactoryKpiBonus để đọc sổ. Khoản dư đó được OWNER
        // khai báo trong bảng factory_kpi_carry_over_seed và cộng vào đây.
        //
        // Cộng thay vì ghi đè: một tháng có thể vừa kế thừa sổ dư trong app, vừa
        // nhận thêm khoản chốt tay từ ngoài.
        for (com.nhatnam.server.entity.FactoryKpiCarryOverSeed seed
                : carryOverSeedRepo.findByApplyMonthAndApplyYearOrderBySourceYearAscSourceMonthAsc(month, year)) {
            long amt = seed.getAmount() != null ? seed.getAmount() : 0L;
            if (amt > 0)
                ledger.add(new CarryOverEntry(seed.getSourceMonth(), seed.getSourceYear(), amt));
        }

        // Xếp CŨ NHẤT TRƯỚC để consumeFifo tiêu đúng thứ tự — khoản khai báo tay
        // thường thuộc tháng cũ hơn nên phải chen vào đầu sổ, không dồn xuống cuối.
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

    /**
     * Thêm một khoản quỹ dư khai báo tay.
     *
     * <p>KHÔNG tự gọi {@link #recompute} ở đây: tháng đích có thể đang được chốt,
     * và người nhập thường thêm vài dòng liên tiếp. Tính lại sau khi nhập xong là
     * việc của người dùng (nút "Tính lại thưởng KPI"), tránh recompute thừa.
     */
    @Transactional
    public com.nhatnam.server.entity.FactoryKpiCarryOverSeed addCarryOverSeed(
            int applyMonth, int applyYear,
            int sourceMonth, int sourceYear,
            long amount, String note, String actor) {

        if (applyMonth < 1 || applyMonth > 12 || sourceMonth < 1 || sourceMonth > 12)
            throw new IllegalArgumentException("Tháng không hợp lệ");
        if (amount <= 0)
            throw new IllegalArgumentException("Số tiền dư phải lớn hơn 0");

        // Khoản dư phải phát sinh TRƯỚC tháng được cộng — nếu không thì đó là tiền
        // của tương lai, gần như chắc chắn là nhập nhầm tháng.
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
     * TỈ LỆ NGÀY CÔNG của nhân viên trong tháng — hệ số nhân vào trọng số vị trí.
     *
     * <pre>
     *   tỉ lệ = số công thực tế / số công chuẩn      (giới hạn trong [0 … MAX_ATTENDANCE_RATIO])
     *
     *   VD tháng 6/2026 có 24 công chuẩn:
     *       làm đủ 24 công → 24/24 = 1,000  → hưởng 100% trọng số vị trí
     *       làm 15 công    → 15/24 = 0,625  → hưởng 62,5%
     *       nghỉ thai sản  →  0/24 = 0,000  → không nhận thưởng
     * </pre>
     *
     * <p><b>Chưa có bảng chấm công của tháng</b> → trả về 1.0 cho tất cả, để vẫn
     * xem trước được mức chia. Sau khi OWNER upload chấm công, hệ thống tự gọi
     * {@link #recompute} nên số liệu sẽ được cập nhật lại theo ngày công thật.
     *
     * <p><b>Đã có bảng chấm công nhưng nhân viên không có dòng nào</b> → 0.0.
     * Người nghỉ trọn tháng (thai sản, nghỉ dài hạn) rơi vào trường hợp này và
     * tự động không được chia, vẫn hiển thị trong danh sách với 0đ.
     * Nếu là do import không khớp được tên thì xem tab "Thiếu" trong báo cáo
     * import để xử lý.
     */
    public double attendanceRatio(User user, int month, int year, boolean hasAttendanceSheet) {
        if (!hasAttendanceSheet) return 1.0;

        AttendanceEntry entry = attendanceEntryRepo
                .findByUserAndPeriod(user.getId(), month, year)
                .orElse(null);
        if (entry == null) return 0.0;

        // Công chuẩn TÍNH LẠI từ lịch tháng, không đọc entry.getStandardDays():
        // các bản ghi import trước khi sửa lỗi "thứ Bảy = nửa công" đang lưu số
        // sai (T7/2026 lưu 25 thay vì 27), khiến tỉ lệ ngày công bị đội lên.
        double std = com.nhatnam.server.utils.PayrollTaxCalculator.standardWorkdaysOf(month, year);
        double act = entry.getActualDays() != null ? entry.getActualDays() : 0.0;
        if (std <= 0) return 0.0;

        // ── NGƯỠNG NGÀY CÔNG — xét TRƯỚC khi chia theo tỉ lệ ──────────────────
        //   Nghỉ quá nhiều thì cắt hẳn thưởng, không tính pro-rata nữa.
        //   Lưu ý: ngưỡng xét trên SỐ CÔNG TUYỆT ĐỐI (act), không phải tỉ lệ —
        //   tháng ít ngày công chuẩn vẫn dùng chung một mốc 10 / 20 công.
        if (act < MIN_DAYS_FOR_BONUS) return 0.0;

        double ratio = act / std;
        if (ratio < 0) return 0.0;
        ratio = Math.min(ratio, MAX_ATTENDANCE_RATIO);

        // 10 ≤ công < 20 → TRẦN 50%. Dùng min() chứ không gán cứng 0.5 để người
        // làm đúng 10/24 công (pro-rata 0,4167) không bị đẩy LÊN thành 0,5.
        if (act < FULL_BONUS_DAYS_THRESHOLD) return Math.min(ratio, PARTIAL_BONUS_FACTOR);

        return ratio;
    }

    /**
     * Map nhân viên xưởng (trừ bảo vệ) → TRỌNG SỐ HIỆU DỤNG.
     * <pre>
     *   trọng số hiệu dụng = trọng số vị trí × tỉ lệ ngày công
     * </pre>
     * Người có trọng số 0 (nghỉ trọn tháng) bị loại khỏi map để không làm
     * phình mẫu số khi chia.
     */
    private Map<User, Double> effectiveWeights(int month, int year, boolean hasAttendanceSheet) {
        Map<User, Double> result = new LinkedHashMap<>();

        for (User u : usersEligibleForWeight()) {
            Role role = kpiRoleOf(u);
            if (role == null) continue;

            double base  = ROLE_WEIGHTS.getOrDefault(role, 0.0);
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
     * <p><b>Ưu tiên tuyệt đối {@code _user.payroll_role}</b> — cột này do Kế toán
     * trưởng set ở trang Nhân sự thông qua cặp (Bộ phận, Chức vụ), xem
     * {@link com.nhatnam.server.enumtype.OrgCatalog}. Suy ngược từ tập role đăng
     * nhập chỉ là PHƯƠNG ÁN DỰ PHÒNG cho nhân viên chưa được set.
     *
     * <p>Vì sao quan trọng: nhân viên kiêm nhiệm có nhiều role xưởng thì cách suy
     * ngược sẽ luôn chọn role có hệ số CAO NHẤT — một công nhân sản xuất (×1.00)
     * được cấp thêm quyền {@code FACTORY_WORKER} để vào màn hình nào đó sẽ tự
     * nhiên được nâng lên ×1.10 và ăn thưởng nhiều hơn thực tế.
     *
     * @return {@code null} nếu nhân viên hưởng lương ở BỘ PHẬN KHÁC (không chia
     *         thưởng KPI xưởng) hoặc không có role xưởng nào.
     */
    private Role kpiRoleOf(User u) {
        Role explicit = u.getPayrollRole();
        if (explicit != null) {
            // Đã set chức vụ trả lương → DÙNG ĐÚNG role đó, không quan tâm
            // nhân viên còn được cấp thêm quyền gì khác.
            if (ROLE_WEIGHTS.containsKey(explicit) || explicit == Role.FACTORY_SECURITY) return explicit;
            return null;   // hưởng lương ở Kinh doanh / Kho / Kế toán… → không chia
        }

        // ── DỰ PHÒNG: chưa set chức vụ trả lương ──────────────────────────────
        //   Lấy role xưởng có hệ số THẤP NHẤT, KHÔNG phải cao nhất. Đây là lựa
        //   chọn thận trọng có chủ đích: thà trả thiếu rồi bổ sung, còn hơn trả
        //   dư cho người chỉ được cấp thêm quyền chứ không thật sự giữ vị trí đó.
        //   Trường hợp này chỉ nên là TẠM THỜI — hãy vào trang Nhân sự set Bộ
        //   phận / Chức vụ cho nhân viên.
        Role lowest = u.getAllRoles().stream()
                .filter(ROLE_WEIGHTS::containsKey)
                .min(Comparator.comparingDouble(ROLE_WEIGHTS::get))
                .orElse(null);

        if (lowest == null) {
            // Không có role nào trong bảng hệ số → có thể là bảo vệ xưởng
            return u.getAllRoles().contains(Role.FACTORY_SECURITY) ? Role.FACTORY_SECURITY : null;
        }

        log.warn("[KPI] Nhân viên #{} \"{}\" CHƯA có chức vụ trả lương (payroll_role). "
                        + "Tạm tính theo role thấp nhất: {} (×{}). "
                        + "Vào trang Nhân sự set Bộ phận / Chức vụ để tính đúng.",
                u.getId(), u.getFullName(), lowest, ROLE_WEIGHTS.get(lowest));
        return lowest;
    }

    /**
     * TRUE nếu nhân viên thực sự hưởng thưởng KPI xưởng ở vị trí {@code role}.
     * Loại bỏ người có role xưởng nhưng {@code payroll_role} trỏ sang bộ phận khác.
     */
    private boolean countsForKpi(User u, Role role) {
        Role effective = kpiRoleOf(u);
        return effective == role;
    }

    /** Nhân viên đang hoạt động (chưa khoá, chưa xoá mềm) có role chỉ định. */
    private List<User> activeUsersWithRole(Role role) {
        Map<Long, User> merged = new LinkedHashMap<>();
        for (User u : userRepo.findByRole(role))             merged.put(u.getId(), u);
        for (User u : userRepo.findByRolesContaining(role))  merged.put(u.getId(), u);
        // Tra thêm theo CHỨC VỤ TRẢ LƯƠNG — kế toán xưởng thường chỉ có role đăng
        // nhập ACCOUNTANT, nếu bỏ dòng này họ sẽ không được chia thưởng KPI.
        for (User u : userRepo.findByPayrollRole(role))      merged.put(u.getId(), u);

        return merged.values().stream()
                .filter(u -> !u.isLockAccount())
                .filter(u -> !u.isDeleted())
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TIỆN ÍCH
    // ══════════════════════════════════════════════════════════════════════════

    /** Làm tròn {@code value} về bội số của {@code step} theo {@code mode}. */
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