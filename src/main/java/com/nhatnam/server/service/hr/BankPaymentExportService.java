package com.nhatnam.server.service.hr;

import com.nhatnam.server.dto.hr.HrDtos.AllowanceItemDto;
import com.nhatnam.server.dto.hr.HrDtos.SalaryBreakdownDto;
import com.nhatnam.server.entity.Driver;
import com.nhatnam.server.entity.DriverAttendance;
import com.nhatnam.server.entity.EmployeeSalary;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.DriverAttendanceRepository;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.EmployeeSalaryRepository;
import com.nhatnam.server.service.FactoryPayrollService;
import com.nhatnam.server.utils.PayrollTaxCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Xuất file "DANH SÁCH CHI LƯƠNG" theo layout ngân hàng (VietinBank CN2).
 *
 * <p>Khác với {@link SalaryExportService#exportSalaryReport} — file lương nội bộ
 * đầy đủ khoản mục — file này chỉ có 5 cột: STT · Họ tên · Số tài khoản · Ngân
 * hàng · Số tiền, dùng để chuyển khoản hàng loạt. Số tiền = LƯƠNG THỰC NHẬN
 * (lương cơ bản đã chia theo công + phụ cấp), KHÔNG bao gồm thưởng/KPI.
 *
 * <h3>Quy tắc chọn nhân viên</h3>
 * <ul>
 *   <li>Nhân viên đã xoá mềm ({@code deleted = true}) → BỎ.</li>
 *   <li>Nhân viên bị khoá tài khoản ({@code isLockAccount = true}) → BỎ. File
 *       gửi ngân hàng chỉ liệt kê nhân viên đang thực sự làm việc; nhân viên
 *       khoá không nhận lương qua chuyển khoản chung.</li>
 *   <li>Nhân viên đang NGHỈ THAI SẢN ({@code onMaternityLeave = true}) → BỎ,
 *       nhưng KHÔNG xoá hồ sơ. Khi HR gỡ cờ (nhân viên đi làm trở lại), tháng
 *       tiếp theo sẽ tự có tên trong file như cũ.</li>
 *   <li>Tài khoản {@code nguyenhai} → BỎ.</li>
 *   <li>Họ tên chứa từ khoá trong {@link #EXCLUDED_NAME_KEYWORDS} (hiện có
 *       "Q9" — nhóm bảo vệ quận 9 nhận lương theo cơ chế riêng) → BỎ.</li>
 *   <li>Role {@code FACTORY_SECURITY} (Bảo vệ xưởng) → BỎ. Bảo vệ xưởng nhận
 *       lương khoán bằng tiền mặt / cơ chế riêng.</li>
 *   <li><b>Nhân viên KHÔNG có số tài khoản ngân hàng → BỎ.</b>
 *       (Không có TK thì không thể chuyển khoản, đưa vào chỉ làm rối file.)</li>
 *   <li><b>Nhân viên KHÔNG có hồ sơ lương đã duyệt ({@link EmployeeSalary}
 *       status = {@code APPROVED}) → BỎ.</b> Chưa duyệt lương nghĩa là chưa
 *       thống nhất số tiền chi, không xuất ra file NH.</li>
 *   <li>Số tài khoản + tên ngân hàng lấy từ hồ sơ nhân viên
 *       ({@link User#getBankAccountNumber()} / {@link User#getBankName()}).</li>
 * </ul>
 *
 * <p><b>LƯU Ý:</b> File này được xuất theo cơ chế PREVIEW — giống "Xuất file
 * lương tổng hợp". Không yêu cầu phải có {@code PayrollBatch} APPROVED trước;
 * số được dựng ngay từ hồ sơ lương hiện hành + chấm công đã upload. HR dùng
 * file này để REVIEW số sẽ gửi ngân hàng; sau khi review có thể quay lại
 * điều chỉnh hồ sơ / chấm công rồi xuất lại.
 *
 * <h3>Thứ tự dòng</h3>
 * <p>Sắp theo bộ phận → cấp bậc trong bộ phận → tên:
 * <pre>
 *   ACCOUNTING: Kế toán trưởng → Kế toán
 *   FACTORY:    Trưởng xưởng → Trợ lý → Quản lý → Kế toán xưởng
 *               → Nhân viên đóng gói → Công nhân SX
 *   SALES:      Trưởng phòng KD → Nhân viên KD
 *   WAREHOUSE:  Trưởng kho → Nhân viên kho
 *   DRIVER:     Tài xế (không phân cấp)
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankPaymentExportService {

    private final PayrollDepartmentResolver deptResolver;
    private final HrService hrService;
    private final FactoryPayrollService factoryPayrollService;
    // ── MỚI: kiểm tra nhân viên đã có hồ sơ lương APPROVED hay chưa ───────────
    private final EmployeeSalaryRepository employeeSalaryRepository;
    // ── MỚI: đếm công thực tế tài xế từ bảng điểm danh ODO ───────────────────
    // Cần để tính base + cơm cho tài xế THEO ĐÚNG công thức của file lương tổng
    // hợp — tránh dùng b.getBaseSalary() từ driverSalaryDetail vốn dùng nguồn
    // attendance khác → lệch số.
    private final DriverRepository driverRepo;
    private final DriverAttendanceRepository driverAttendanceRepo;

    // ── PHASE 4: SNAPSHOT ───────────────────────────────────────────────────
    //   Khi tháng đã CALCULATED/PUBLISHED và có snapshot của NV, dùng snapNetPay
    //   làm số chuyển khoản → file bank luôn bám sát số đã Tính lương, không
    //   kéo theo thay đổi hồ sơ lương về sau. Fallback về compute live nếu
    //   không có snapshot (NV mới thêm sau khi tính, hoặc tháng chưa tính).
    private final com.nhatnam.server.repository.AttendanceSheetRepository attendanceSheetRepo;
    private final com.nhatnam.server.repository.AttendanceEntryRepository attendanceEntryRepo;

    /** Tài khoản mặc định KHÔNG đưa vào file này (đi theo cơ chế trả lương riêng). */
    private static final String EXCLUDED_USERNAME = "nguyenhai";

    /**
     * TỪ KHOÁ TRONG TÊN nhân viên → loại khỏi file NH.
     *
     * <p>Hiện có "Q9" (bảo vệ / nhân viên quận 9) — nhóm này nhận lương theo cơ
     * chế riêng, không chuyển khoản qua file NH chung. So khớp không phân biệt
     * hoa/thường, không dấu; chỉ cần fullName CHỨA từ khoá là loại.
     */
    private static final List<String> EXCLUDED_NAME_KEYWORDS = List.of("Q9");

    /** Thứ tự bộ phận trong file — đúng thứ tự OWNER yêu cầu. */
    // Phase 1 (10/2026): bỏ MANAGEMENT khỏi hệ thống tính lương; OWNER/ADMIN
    // không còn được xuất file lương ngân hàng.
    private static final List<PayrollDepartment> DEPARTMENT_ORDER = List.of(
            PayrollDepartment.ACCOUNTING,
            PayrollDepartment.FACTORY,
            PayrollDepartment.SALES,
            PayrollDepartment.WAREHOUSE,
            PayrollDepartment.DRIVER
    );

    /**
     * Xếp cấp bậc trong 1 bộ phận. Số nhỏ hơn xếp trên. Role không thuộc bộ phận
     * (VD role phụ) rơi xuống cuối bằng 99.
     *
     * <p>Đồng bộ với {@code SalaryExportService.roleRank} nhưng KHÔNG có
     * FACTORY_SECURITY (đã lọc ở tầng trên) — để lỡ có ai lọt qua thì cũng xếp
     * cuối chứ không lẫn giữa danh sách nhân viên.
     */
    private static int roleRank(PayrollDepartment dept, Role role) {
        if (dept == null || role == null) return 99;
        return switch (dept) {
            // Phase 1: MANAGEMENT đã gỡ — OWNER/ADMIN không thuộc PayrollDepartment nữa.
            case ACCOUNTING -> switch (role) {
                case SUPER_ACCOUNTANT -> 0;
                case ACCOUNTANT       -> 1;
                default -> 99;
            };
            case FACTORY -> switch (role) {
                case SUPER_FACTORY_WORKER      -> 0;   // Trưởng xưởng
                case FACTORY_STAFF             -> 1;   // Trợ lý xưởng
                case FACTORY_MANAGER           -> 2;   // Quản lý xưởng
                case FACTORY_ACCOUNTANT        -> 3;   // Kế toán xưởng
                case FACTORY_PACKAGING_WORKER  -> 4;   // Nhân viên đóng gói
                case FACTORY_PRODUCTION_WORKER -> 5;   // Công nhân sản xuất
                case FACTORY_WORKER            -> 6;   // Nhân viên xưởng (fallback)
                default -> 99;
            };
            case SALES -> switch (role) {
                case SUPER_SELLER -> 0;
                case SELLER       -> 1;
                default -> 99;
            };
            case WAREHOUSE -> switch (role) {
                case SUPER_WAREHOUSE -> 0;
                case WAREHOUSE       -> 1;
                default -> 99;
            };
            case DRIVER -> 0;   // Tài xế không phân cấp
        };
    }

    /** Một dòng nhân viên trong file NH — dữ liệu đã sẵn sàng để in. */
    private static class Row {
        String fullName;
        String bankAccountNumber;   // null = HR chưa nhập → ô để trống
        String bankName;            // null = HR chưa nhập → ô để trống
        Long amount;                // null = chưa có lương trong app → ô để trống
    }

    /**
     * Xuất file .xlsx bảng chi lương theo layout ngân hàng.
     *
     * @param month 1..12
     * @param year  4 chữ số
     * @return bytes của file .xlsx
     */
    @Transactional(readOnly = true)
    public byte[] exportBankPaymentReport(int month, int year) {
        List<Row> rows = collectRows(month, year);
        try {
            return buildWorkbook(month, year, rows);
        } catch (Exception e) {
            log.error("[BankPaymentExport] Lỗi xuất file NH tháng {}/{}", month, year, e);
            throw new RuntimeException("Không xuất được file lương ngân hàng: " + e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COLLECT & FILTER
    // ══════════════════════════════════════════════════════════════════════════

    private List<Row> collectRows(int month, int year) {
        // ── Pre-load: nhân viên nào đã có hồ sơ lương status = APPROVED ──────
        //   Dùng Set<Long> userId để check O(1) trong vòng lặp.
        Set<Long> approvedSalaryUserIds = new HashSet<>();
        for (EmployeeSalary s : employeeSalaryRepository.findLatestApprovedPerUser()) {
            // User.getId() trả về long primitive nên không cần check != null
            if (s.getUser() != null) {
                approvedSalaryUserIds.add(s.getUser().getId());
            }
        }

        List<Row> out = new ArrayList<>();
        for (PayrollDepartment dept : DEPARTMENT_ORDER) {
            // `employeesOf` = active only (đã lọc CẢ deleted=true LẪN
            // isLockAccount=true). Đúng với yêu cầu mới: nhân viên khoá/xoá
            // KHÔNG có trong file chi lương — không dùng employeesWithLockedOf.
            List<User> employees = deptResolver.employeesOf(dept);

            // Sắp trước theo cấp bậc để buffer nội bộ đã đúng thứ tự khi ghi.
            // ▲ GIỮ NGUYÊN sort — filter phía dưới chỉ loại bỏ, không đổi thứ tự.
            List<User> ordered = new ArrayList<>(employees);
            ordered.sort(Comparator
                    .comparingInt((User u) -> roleRank(dept, deptResolver.payrollRoleOf(u)))
                    .thenComparing(u -> u.getFullName() != null ? u.getFullName() : "",
                            String.CASE_INSENSITIVE_ORDER));

            for (User u : ordered) {
                if (!shouldInclude(u)) continue;

                // ── FILTER MỚI 1: không có số TK ngân hàng → bỏ ───────────────
                String bankAcc = trimOrNull(u.getBankAccountNumber());
                if (bankAcc == null) {
                    log.debug("[BankPaymentExport] Bỏ user {} — chưa có số tài khoản ngân hàng",
                            u.getUsername());
                    continue;
                }

                // ── FILTER MỚI 2: chưa có hồ sơ lương APPROVED → bỏ ──────────
                if (!approvedSalaryUserIds.contains(u.getId())) {
                    log.debug("[BankPaymentExport] Bỏ user {} — chưa có hồ sơ lương được duyệt",
                            u.getUsername());
                    continue;
                }

                Row r = new Row();
                r.fullName = u.getFullName() != null ? u.getFullName().trim() : "";
                r.bankAccountNumber = bankAcc;
                r.bankName = trimOrNull(u.getBankName());
                r.amount = computeAmount(u, dept, month, year);
                out.add(r);
            }
        }
        return out;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Có đưa nhân viên này vào file không.
     *
     * <p>Điều kiện KHÔNG đưa:
     * <ul>
     *   <li>Username là {@link #EXCLUDED_USERNAME}.</li>
     *   <li>Role hưởng lương là {@code FACTORY_SECURITY} hoặc user được đánh dấu
     *       {@code attendanceExempt} (bảo vệ khoán trọn tháng).</li>
     *   <li>Họ tên chứa từ khoá trong {@link #EXCLUDED_NAME_KEYWORDS} (VD "Q9" —
     *       bảo vệ quận 9 nhận lương theo cơ chế riêng).</li>
     * </ul>
     *
     * <p>Việc lọc nhân viên đã xoá / bị khoá tài khoản làm ở tầng trên
     * (dùng {@code employeesOf} thay vì {@code employeesWithLockedOf}) — nên
     * hàm này không kiểm tra lại {@code deleted} / {@code isLockAccount}.
     *
     * <p>Việc lọc theo TK ngân hàng và hồ sơ lương APPROVED làm ở
     * {@link #collectRows} vì cần preload map — không đưa vào đây.
     */
    private boolean shouldInclude(User u) {
        if (u == null) return false;
        if (EXCLUDED_USERNAME.equalsIgnoreCase(u.getUsername())) return false;

        // Nghỉ thai sản → tạm dừng chuyển khoản. Khác với khoá / xoá (đã lọc
        // ở tầng employeesOf): nhân viên vẫn ở lại hệ thống, HR chỉ cần bỏ cờ
        // là tháng sau tự có tên trong file lại.
        if (u.isOnMaternityLeave()) return false;

        Role payrollRole = deptResolver.payrollRoleOf(u);
        if (payrollRole == Role.FACTORY_SECURITY) return false;
        // Bảo vệ khoán trọn tháng (attendanceExempt) đôi khi được set role khác
        // nhưng vẫn hưởng khoán — chặn thêm ở đây cho chắc.
        if (deptResolver.isAttendanceExempt(u)) return false;

        // So khớp từ khoá TÊN sau khi bỏ dấu + chuyển thường + gộp khoảng trắng
        // — để "Bảo vệ Q9", "bao ve q9  ", "BẢO VỆ Q.9" đều bị chặn như nhau.
        String normName = normalize(u.getFullName());
        if (!normName.isEmpty()) {
            for (String kw : EXCLUDED_NAME_KEYWORDS) {
                if (normName.contains(kw.toLowerCase())) return false;
            }
        }
        return true;
    }

    /** Bỏ dấu tiếng Việt, đổi thường, gộp nhiều khoảng trắng thành 1 — dùng
     *  để so khớp tên linh hoạt (không phân biệt "Q9" / "q9" / "Q 9"). */
    private static String normalize(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /**
     * Số tiền chuyển khoản = LƯƠNG THỰC NHẬN không thưởng, làm tròn LÊN 1.000đ.
     *
     * <p><b>Công thức khớp 100% với {@link SalaryExportService}</b> (cột "Lương
     * thực nhận" của file lương tổng hợp — nguồn chân lý mà OWNER duyệt):
     * <pre>
     *   amount = attendanceSalary + Σ visible_allowances
     *   amount = roundUpToThousand(amount)
     * </pre>
     * Trong đó:
     * <ul>
     *   <li><b>attendanceSalary</b> cho TÀI XẾ: {@code ceil(standardBase × actualDays / standardDays)},
     *       với {@code actualDays} = số ngày có điểm danh ODO (hoặc override nếu có).</li>
     *   <li><b>attendanceSalary</b> cho văn phòng: {@code b.getBaseSalary()} (đã
     *       prorate sẵn trong breakdown).</li>
     *   <li><b>visible_allowances</b>: lặp {@code b.getAllowances()}, bỏ các
     *       mục auto-generated từ hỗ trợ giao hàng ("Phụ cấp xăng xe (… km × …)",
     *       "Phụ cấp giao hàng"), và bỏ cột "Phụ cấp xăng xe" của tài xế (tính
     *       riêng ở file thưởng). Nếu có override → cơm = 30.000 × mealDays.</li>
     * </ul>
     *
     * <p>Vì sao KHÔNG dùng {@code b.getNetSalary()} hay {@code b.getAllowance()}
     * trực tiếp:
     * <ul>
     *   <li>{@code b.getNetSalary()} với tài xế bao gồm cả xăng + thưởng đơn hàng
     *       (được đẩy vào {@code bonus} khi tính breakdown) → cao hơn thực tế
     *       cần chuyển khoản.</li>
     *   <li>{@code b.getAllowance()} là tổng gộp, không lọc auto-generated.</li>
     *   <li>{@code b.getBaseSalary()} của tài xế có thể lệch với
     *       {@code countDriverWorkdays} do dùng nguồn attendance khác.</li>
     * </ul>
     *
     * <p>Trả {@code null} nếu tổng ≤ 0 — ô để trống, HR điền tay.
     */
    private Long computeAmount(User u, PayrollDepartment dept, int month, int year) {
        // ── PHASE 4: ưu tiên SNAPSHOT khi tháng đã Tính lương ───────────────
        Long snap = tryLoadSnapshotNet(u, month, year);
        if (snap != null) {
            log.debug("[BankPaymentExport] {} {}/{}: dùng snapshot = {}",
                    u.getFullName(), month, year, snap);
            return snap <= 0 ? null : com.nhatnam.server.utils.ManualAttendanceOverrides.roundUpToThousand(snap);
        }

        // PHASE 5 (10/2026): tài xế dùng CHUNG hrService như mọi bộ phận khác.
        // Odo chỉ còn dùng cho xăng/thưởng đơn hàng (xuất riêng ở file thưởng
        // tài xế, không nằm trong file chi lương ngân hàng này).
        SalaryBreakdownDto b;
        try {
            b = hrService.getSalaryBreakdownForUser(u.getId(), month, year);
        } catch (Exception e) {
            log.warn("[BankPaymentExport] Không lấy được lương của user {}: {}",
                    u.getId(), e.getMessage());
            return null;
        }
        if (b == null) return null;

        com.nhatnam.server.utils.ManualAttendanceOverrides.Override ov =
                com.nhatnam.server.utils.ManualAttendanceOverrides.lookup(
                        u.getFullName(), year, month);

        // ── 1) attendanceSalary — khớp với SalaryExportService ────────────
        long attendanceSalary = computeAttendanceSalary(u, b, dept, month, year, ov);

        // ── 2) Tổng phụ cấp hiển thị — khớp với SalaryExportService ───────
        long allowanceTotal = computeVisibleAllowanceTotal(b, dept, ov);

        long total = attendanceSalary + allowanceTotal;

        if (log.isDebugEnabled()) {
            log.debug("[BankPaymentExport] {} {}/{}: attendance={}, allowance={}, total={}" +
                            (ov != null ? " (OVERRIDE {}/{} công)" : ""),
                    u.getFullName(), month, year, attendanceSalary, allowanceTotal, total,
                    ov != null ? ov.actualDays() : null,
                    ov != null ? ov.mealDays() : null);
        }

        if (total <= 0) return null;
        return com.nhatnam.server.utils.ManualAttendanceOverrides.roundUpToThousand(total);
    }

    /**
     * Tính "Lương theo chấm công" khớp với {@code SalaryExportService}.
     *
     * <p>Logic:
     * <ul>
     *   <li><b>Tài xế</b>: {@code ceil(standardBase × actualDays / standardDays)}.
     *       {@code actualDays} = override nếu có, hoặc {@link #countDriverWorkdays}
     *       (số ngày có điểm danh ODO).</li>
     *   <li><b>Có override (bất kể bộ phận)</b>: {@code ceil(standardBase × overrideDays / standardDays)}
     *       — áp cả cho văn phòng nếu HR muốn chỉnh tay.</li>
     *   <li><b>Còn lại</b>: {@code b.getBaseSalary()} (đã prorate trong breakdown).</li>
     * </ul>
     */
    private long computeAttendanceSalary(User u, SalaryBreakdownDto b, PayrollDepartment dept,
                                         int month, int year,
                                         com.nhatnam.server.utils.ManualAttendanceOverrides.Override ov) {
        long baseSalaryFull = nz(b.getStandardBaseSalary() != null
                ? b.getStandardBaseSalary()
                : b.getBaseSalary());
        double standardDays = PayrollTaxCalculator.standardWorkdaysOf(month, year);

        // Số công lễ cần CỘNG BÙ cho NV này — 0 nếu override.skipHolidayBonus
        // hoặc dept không áp dụng (chỉ FACTORY và DRIVER).
        int holidayBonus = 0;
        if (dept == PayrollDepartment.FACTORY || dept == PayrollDepartment.DRIVER) {
            boolean skipHoliday = ov != null && ov.skipHolidayBonus();
            if (!skipHoliday) {
                holidayBonus = com.nhatnam.server.utils.VietnameseHolidays
                        .nonSundayHolidaysInMonth(year, month);
            }
        }

        // FIX (10/2026): dùng CHUNG PayrollTaxCalculator.prorateSalaryByDays
        // (làm tròn LÊN bội 5.000đ) cho cả 2 nhánh. Trước đây nhánh có override
        // dùng {@code Math.ceil(base/std × actual)} làm tròn 1đ, trong khi
        // SalaryExportService đọc {@code bd.getBaseSalary()} đã được HrService
        // prorate qua prorateSalaryByDays (bội 5.000đ) → chênh lệch tới hàng
        // nghìn ở các case có override (triệu chứng: file tổng hợp 5.649.000,
        // file NH 5.648.000 cho CÙNG 1 NV). Giờ 2 file ra số GIỐNG NHAU từ gốc.
        if (ov != null && standardDays > 0) {
            double actual = ov.actualDays();
            if (holidayBonus > 0) {
                actual = Math.min(standardDays, actual + holidayBonus);
            }
            return PayrollTaxCalculator.prorateSalaryByDays(
                    baseSalaryFull, actual, standardDays);
        }
        // Không override: b.getBaseSalary() đã được HrService cộng công lễ +
        // prorate theo prorateSalaryByDays (patch HrService) → dùng thẳng.
        return nz(b.getBaseSalary());
    }

    /**
     * Tính tổng các cột phụ cấp HIỂN THỊ trong file lương — khớp với
     * {@code SalaryExportService.writeEmployeeRow}:
     * <ul>
     *   <li>Lặp {@code b.getAllowances()}, mỗi item gom theo nhãn (bỏ phần
     *       {@code "(… km × …)"} trong ngoặc).</li>
     *   <li>Bỏ auto-generated từ hỗ trợ giao hàng (fuel-by-km, giao hàng…) —
     *       đã tính riêng ở file thưởng KPI xưởng.</li>
     *   <li>Tài xế: bỏ "Phụ cấp xăng xe" (vẫn tính riêng ở file thưởng).</li>
     *   <li>Có override → cơm = {@code 30.000 × ov.mealDays()}. Nếu danh sách
     *       không có mục cơm thì vẫn cộng vào.</li>
     * </ul>
     */
    private long computeVisibleAllowanceTotal(SalaryBreakdownDto b, PayrollDepartment dept,
                                              com.nhatnam.server.utils.ManualAttendanceOverrides.Override ov) {
        long total = 0L;
        boolean mealOverrideApplied = false;

        if (b.getAllowances() != null) {
            for (AllowanceItemDto a : b.getAllowances()) {
                if (a == null || a.getLabel() == null) continue;
                if (isAutoGeneratedDeliveryAllowance(a.getLabel())) continue;
                String baseLabel = stripDetail(a.getLabel());
                if (dept == PayrollDepartment.DRIVER
                        && "Phụ cấp xăng xe".equals(baseLabel)) continue;

                long val = nz(a.getAmount());
                if (ov != null && "Phụ cấp cơm trưa".equals(baseLabel)) {
                    val = 30_000L * ov.mealDays();
                    mealOverrideApplied = true;
                }
                total += val;
            }
        }
        // Override tồn tại nhưng breakdown không trả về mục cơm → vẫn cộng vào
        if (ov != null && !mealOverrideApplied) {
            total += 30_000L * ov.mealDays();
        }
        return total;
    }

    /**
     * Nhận diện phụ cấp AUTO-GENERATED từ hỗ trợ giao hàng — logic copy từ
     * {@code SalaryExportService} để 2 file đồng bộ.
     */
    private static boolean isAutoGeneratedDeliveryAllowance(String rawLabel) {
        if (rawLabel == null) return false;
        String s = rawLabel.trim();
        if (s.equals("Phụ cấp giao hàng") || s.startsWith("Phụ cấp giao hàng (")) return true;
        if (s.startsWith("Phụ cấp xăng xe (")) {
            if (s.contains(" km × ") || s.contains(" km x ")) return true;
            if (s.contains("(giao hàng)")) return true;
        }
        return false;
    }

    /** Cắt bỏ phần trong ngoặc của nhãn — khớp {@code SalaryExportService.stripDetail}. */
    private static String stripDetail(String label) {
        if (label == null) return null;
        int idx = label.indexOf(" (");
        return idx > 0 ? label.substring(0, idx).trim() : label.trim();
    }

    /**
     * Đếm số ngày đi làm thực tế của tài xế trong tháng từ bảng điểm danh ODO.
     *
     * @deprecated Phase 5 (10/2026): tài xế dùng chấm công chung của công ty,
     *   odo chỉ còn phục vụ việc tính xăng/thưởng đơn hàng. Method giữ để
     *   không gãy nếu còn chỗ nào tham chiếu — thực tế hiện không caller.
     */
    @Deprecated
    @SuppressWarnings("unused")
    private double countDriverWorkdays(User user, int month, int year) {
        Driver driver = driverRepo.findByUser_Id(user.getId()).orElse(null);
        if (driver == null) return 0;

        YearMonth ym = YearMonth.of(year, month);
        String from = ym.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String to   = ym.atEndOfMonth().format(DateTimeFormatter.ISO_LOCAL_DATE);

        List<DriverAttendance> records = driverAttendanceRepo.findByDateRange(from, to);
        return records.stream()
                .filter(r -> r.getDriver() != null && r.getDriver().getId().equals(driver.getId()))
                .map(DriverAttendance::getAttendanceDate)
                .distinct()
                .count();
    }

    private static long nz(Long v) { return v != null ? v : 0L; }

    // ══════════════════════════════════════════════════════════════════════════
    // WRITE EXCEL — layout khớp file mẫu CTY_NHAT_NAM.xlsx
    // ══════════════════════════════════════════════════════════════════════════

    private byte[] buildWorkbook(int month, int year, List<Row> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet sheet = wb.createSheet("NHẤT NAM");
            Styles s = new Styles(wb);

            // ── Bề rộng cột (đơn vị 1/256 ký tự) — chỉnh theo file mẫu ────────
            sheet.setColumnWidth(0,  6 * 256);   // A: STT
            sheet.setColumnWidth(1, 34 * 256);   // B: Họ tên
            sheet.setColumnWidth(2, 20 * 256);   // C: Số TK
            sheet.setColumnWidth(3, 22 * 256);   // D: Ngân hàng
            sheet.setColumnWidth(4, 18 * 256);   // E: Số tiền

            // ── Row 1: Tên công ty (canh trái, không merge) ───────────────────
            org.apache.poi.ss.usermodel.Row r1 = sheet.createRow(0);
            r1.setHeightInPoints(18);
            putCell(r1, 0, "CÔNG TY TNHH SXTP TMDV NHẤT NAM", s.companyName);

            // Row 2 để trống (giữ khoảng)
            sheet.createRow(1).setHeightInPoints(6);

            // ── Row 3: Tiêu đề (merge A3:E3), có tháng/năm ────────────────────
            org.apache.poi.ss.usermodel.Row r3 = sheet.createRow(2);
            r3.setHeightInPoints(26);
            String title = String.format("DANH SÁCH CHI LƯƠNG THÁNG %02d/%d", month, year);
            putCell(r3, 0, title, s.title);
            // Merge trên toàn dải để tiêu đề canh giữa bảng, không nhảy theo tab
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 0, 4));
            for (int c = 1; c <= 4; c++) putCell(r3, c, null, s.title);

            // Row 4 để trống
            sheet.createRow(3).setHeightInPoints(6);

            // ── Row 5–6: Header bảng, merge dọc mỗi ô ─────────────────────────
            org.apache.poi.ss.usermodel.Row r5 = sheet.createRow(4);
            org.apache.poi.ss.usermodel.Row r6 = sheet.createRow(5);
            r5.setHeightInPoints(22);
            r6.setHeightInPoints(6);
            String[] headers = {"STT", "HỌ VÀ TÊN CBNV", "SỐ TÀI KHOẢN", "NGÂN HÀNG", "SỐ TIỀN"};
            for (int c = 0; c < headers.length; c++) {
                putCell(r5, c, headers[c], s.header);
                putCell(r6, c, null, s.header);
                sheet.addMergedRegion(new CellRangeAddress(4, 5, c, c));
            }

            // ── Dữ liệu bắt đầu ROW 7 (index 6) ───────────────────────────────
            int firstDataRow = 7;   // 1-based, cho công thức SUM
            int excelRow = firstDataRow;
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                org.apache.poi.ss.usermodel.Row xr = sheet.createRow(excelRow - 1);
                xr.setHeightInPoints(18);

                putCell(xr, 0, i + 1, s.dataCenter);
                putCell(xr, 1, row.fullName, s.dataLeft);
                // Số TK / Ngân hàng đọc từ hồ sơ nhân viên. Nhân viên nào chưa
                // khai báo thì ô để trống — HR điền tay trước khi gửi ngân hàng.
                // Số TK bấm định dạng text để không bị Excel cắt số 0 đứng đầu.
                putCell(xr, 2, row.bankAccountNumber, s.dataCenter);
                putCell(xr, 3, row.bankName, s.dataLeft);
                if (row.amount != null) {
                    putCell(xr, 4, row.amount.doubleValue(), s.money);
                } else {
                    // Người chưa có lương trên app → để trống ô, khớp yêu cầu.
                    // Vẫn phải tạo ô rỗng có style border cho khớp bảng.
                    putCell(xr, 4, null, s.money);
                }
                excelRow++;
            }

            // Nếu KHÔNG có nhân viên nào, chừa 1 dòng trống cho công thức SUM
            // hoạt động (SUM(E7:E7) trên ô rỗng = 0, không lỗi #NAME?).
            if (rows.isEmpty()) {
                org.apache.poi.ss.usermodel.Row xr = sheet.createRow(excelRow - 1);
                for (int c = 0; c < 5; c++) putCell(xr, c, null, s.dataCenter);
                excelRow++;
            }
            int lastDataRow = excelRow - 1;   // 1-based

            // ── Dòng TỔNG CỘNG: merge A..D, ô E có SUM ────────────────────────
            org.apache.poi.ss.usermodel.Row rTotal = sheet.createRow(excelRow - 1);
            rTotal.setHeightInPoints(24);
            putCell(rTotal, 0, "TỔNG CỘNG", s.totalLabel);
            for (int c = 1; c <= 3; c++) putCell(rTotal, c, null, s.totalLabel);
            sheet.addMergedRegion(new CellRangeAddress(excelRow - 1, excelRow - 1, 0, 3));

            Cell totalCell = rTotal.createCell(4, CellType.FORMULA);
            totalCell.setCellFormula(String.format("SUM(E%d:E%d)", firstDataRow, lastDataRow));
            totalCell.setCellStyle(s.totalMoney);
            excelRow++;

            // Row trống trước footer
            sheet.createRow(excelRow - 1).setHeightInPoints(10);
            excelRow++;

            // ── Ngày ký (merge D..E) ──────────────────────────────────────────
            org.apache.poi.ss.usermodel.Row rDate = sheet.createRow(excelRow - 1);
            rDate.setHeightInPoints(18);
            String dateLine = String.format("Ngày          tháng        năm %d", year);
            putCell(rDate, 3, dateLine, s.dateLine);
            putCell(rDate, 4, null, s.dateLine);
            sheet.addMergedRegion(new CellRangeAddress(excelRow - 1, excelRow - 1, 3, 4));
            excelRow++;

            // ── Chữ ký: KẾ TOÁN | LÃNH ĐẠO ĐƠN VỊ ─────────────────────────────
            org.apache.poi.ss.usermodel.Row rSign = sheet.createRow(excelRow - 1);
            rSign.setHeightInPoints(22);
            // Merge B..C cho "KẾ TOÁN", D..E cho "LÃNH ĐẠO ĐƠN VỊ"
            putCell(rSign, 1, "KẾ TOÁN", s.signHeader);
            putCell(rSign, 2, null, s.signHeader);
            sheet.addMergedRegion(new CellRangeAddress(excelRow - 1, excelRow - 1, 1, 2));
            putCell(rSign, 3, "LÃNH ĐẠO ĐƠN VỊ", s.signHeader);
            putCell(rSign, 4, null, s.signHeader);
            sheet.addMergedRegion(new CellRangeAddress(excelRow - 1, excelRow - 1, 3, 4));

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void putCell(org.apache.poi.ss.usermodel.Row row, int col,
                                Object value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value == null) {
            c.setBlank();
        } else if (value instanceof Number n) {
            c.setCellValue(n.doubleValue());
        } else {
            c.setCellValue(String.valueOf(value));
        }
        if (style != null) c.setCellStyle(style);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STYLES — gọn, chỉ dùng cho file này
    // ══════════════════════════════════════════════════════════════════════════

    private static class Styles {
        final XSSFCellStyle companyName;
        final XSSFCellStyle title;
        final XSSFCellStyle header;
        final XSSFCellStyle dataLeft;
        final XSSFCellStyle dataCenter;
        final XSSFCellStyle money;
        final XSSFCellStyle totalLabel;
        final XSSFCellStyle totalMoney;
        final XSSFCellStyle dateLine;
        final XSSFCellStyle signHeader;

        Styles(XSSFWorkbook wb) {
            DataFormat fmt = wb.createDataFormat();
            // Định dạng số VN: 1.234.567 (chấm ngăn nghìn), không thập phân
            short vnMoney = fmt.getFormat("#,##0;-#,##0");

            // ── Company name ─────────────────────────────────────────────────
            companyName = wb.createCellStyle();
            companyName.setFont(font(wb, 11, true, false));
            companyName.setAlignment(HorizontalAlignment.LEFT);

            // ── Tiêu đề DANH SÁCH CHI LƯƠNG THÁNG mm/yyyy ────────────────────
            title = wb.createCellStyle();
            title.setFont(font(wb, 16, true, false));
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            // ── Header STT / HỌ TÊN / ... ────────────────────────────────────
            header = wb.createCellStyle();
            header.setFont(font(wb, 11, true, false));
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            allBorders(header, BorderStyle.THIN);

            // ── Ô dữ liệu ─────────────────────────────────────────────────────
            dataLeft = wb.createCellStyle();
            dataLeft.setFont(font(wb, 11, false, false));
            dataLeft.setAlignment(HorizontalAlignment.LEFT);
            dataLeft.setVerticalAlignment(VerticalAlignment.CENTER);
            allBorders(dataLeft, BorderStyle.THIN);

            dataCenter = wb.createCellStyle();
            dataCenter.setFont(font(wb, 11, false, false));
            dataCenter.setAlignment(HorizontalAlignment.CENTER);
            dataCenter.setVerticalAlignment(VerticalAlignment.CENTER);
            allBorders(dataCenter, BorderStyle.THIN);

            money = wb.createCellStyle();
            money.setFont(font(wb, 11, false, false));
            money.setAlignment(HorizontalAlignment.RIGHT);
            money.setVerticalAlignment(VerticalAlignment.CENTER);
            money.setDataFormat(vnMoney);
            allBorders(money, BorderStyle.THIN);

            // ── Dòng TỔNG CỘNG ────────────────────────────────────────────────
            totalLabel = wb.createCellStyle();
            totalLabel.setFont(font(wb, 11, true, false));
            totalLabel.setAlignment(HorizontalAlignment.CENTER);
            totalLabel.setVerticalAlignment(VerticalAlignment.CENTER);
            allBorders(totalLabel, BorderStyle.THIN);

            totalMoney = wb.createCellStyle();
            totalMoney.setFont(font(wb, 11, true, false));
            totalMoney.setAlignment(HorizontalAlignment.RIGHT);
            totalMoney.setVerticalAlignment(VerticalAlignment.CENTER);
            totalMoney.setDataFormat(vnMoney);
            allBorders(totalMoney, BorderStyle.THIN);

            // ── Dòng ngày ký ──────────────────────────────────────────────────
            dateLine = wb.createCellStyle();
            dateLine.setFont(font(wb, 11, false, true));   // in nghiêng
            dateLine.setAlignment(HorizontalAlignment.CENTER);

            // ── Header chữ ký: KẾ TOÁN | LÃNH ĐẠO ĐƠN VỊ ─────────────────────
            signHeader = wb.createCellStyle();
            signHeader.setFont(font(wb, 11, true, false));
            signHeader.setAlignment(HorizontalAlignment.CENTER);
            signHeader.setVerticalAlignment(VerticalAlignment.CENTER);
        }

        private static XSSFFont font(XSSFWorkbook wb, int size, boolean bold, boolean italic) {
            XSSFFont f = wb.createFont();
            f.setFontName("Times New Roman");
            f.setFontHeightInPoints((short) size);
            f.setBold(bold);
            f.setItalic(italic);
            return f;
        }

        private static void allBorders(XSSFCellStyle st, BorderStyle bs) {
            st.setBorderTop(bs);
            st.setBorderBottom(bs);
            st.setBorderLeft(bs);
            st.setBorderRight(bs);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 4 — SNAPSHOT LOOKUP
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Lấy snapshot thực nhận của 1 nhân viên trong tháng, nếu có.
     *
     * <p>Điều kiện trả giá trị khác null:
     * <ol>
     *   <li>Có bảng chấm công cả công ty (COMPANY_SENTINEL) cho tháng đó.</li>
     *   <li>{@code calcStatus} đã CALCULATED hoặc PUBLISHED.</li>
     *   <li>Có {@code attendance_entry} cho user này trong sheet.</li>
     *   <li>Entry có snapshot hợp lệ ({@code snapNetPay != null}).</li>
     * </ol>
     *
     * <p>Mọi điều kiện khác → trả null, caller fallback về compute live. Việc
     * này đảm bảo:
     * <ul>
     *   <li>NV mới thêm sau khi tính lương chưa có snapshot → xuất file bank
     *       vẫn ra số (dù OWNER có thể nên tính lại tháng để đồng bộ).</li>
     *   <li>Tháng chưa tính lương → xuất file bank vẫn chạy, dù là số live.</li>
     * </ul>
     */
    private Long tryLoadSnapshotNet(User u, int month, int year) {
        try {
            var sheetOpt = attendanceSheetRepo.findCompanySheet(month, year);
            if (sheetOpt.isEmpty()) return null;
            var sheet = sheetOpt.get();
            var st = sheet.getCalcStatus();
            if (st != com.nhatnam.server.enumtype.PayrollCalcStatus.CALCULATED
                    && st != com.nhatnam.server.enumtype.PayrollCalcStatus.PUBLISHED) {
                return null;
            }
            var entryOpt = attendanceEntryRepo.findBySheet_IdAndUser_Id(sheet.getId(), u.getId());
            if (entryOpt.isEmpty()) return null;
            var entry = entryOpt.get();
            if (!entry.hasSnapshot()) return null;
            return entry.getSnapNetPay();
        } catch (Exception e) {
            log.warn("[BankPaymentExport] Snapshot lookup fail user {}: {}", u.getId(), e.getMessage());
            return null;
        }
    }
}