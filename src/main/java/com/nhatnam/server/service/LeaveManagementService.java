package com.nhatnam.server.service;

import com.nhatnam.server.dto.hr.LeaveManagementDtos.*;
import com.nhatnam.server.entity.AttendanceEntry;
import com.nhatnam.server.entity.EmployeeRequest;
import com.nhatnam.server.entity.ManualLeaveUsage;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.AttendanceEntryRepository;
import com.nhatnam.server.repository.EmployeeRequestRepository;
import com.nhatnam.server.repository.ManualLeaveUsageRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.hr.PayrollDepartmentResolver;
import com.nhatnam.server.utils.LeaveBalanceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DỮ LIỆU BẢNG "QUẢN LÝ PHÉP" — trả về JSON cho FE dựng bảng trực tiếp.
 *
 * <p>Song sinh với {@link LeaveReportExportService}: cùng tập nhân viên, cùng cột,
 * cùng công thức. Khác biệt duy nhất: một trả về file XLSX, một trả về JSON.
 *
 * <p>Chuẩn hoá 1 chỗ:
 * <ul>
 *   <li>Đơn vị chân lý là <b>số phút</b> (1 ngày = 480 phút, 1 nửa-ngày = 240 phút).</li>
 *   <li>Format "n days m mins" tính chính xác trên phút, chỉ nới về ngày khi
 *       hiển thị — tránh sai 19 phút thành 0 do rounding.</li>
 * </ul>
 *
 * <p>Filter nhân viên:
 * <ul>
 *   <li>Có {@code role}, không bị khoá tài khoản.</li>
 *   <li>KHÔNG hiện user đã xoá mềm — spec "chỉ hiển thị nhân viên không bị khoá,
 *       không bị xoá".</li>
 *   <li>Bỏ 3 username hệ thống (khớp {@code LeaveReportExportService}).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LeaveManagementService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Số phút chuẩn của 1 ngày phép. */
    public static final int LEAVE_MINUTES_PER_DAY = 480;

    private static final List<String> EXCLUDED_USERNAMES =
            Arrays.asList("nguyenhai", "q9", "baoveq9");

    private static final List<String> DEPARTMENT_ORDER = List.of(
            "MANAGEMENT", "ACCOUNTING", "FACTORY", "SALES", "WAREHOUSE_AND_DRIVER");

    private static final Map<String, String> DEPARTMENT_LABELS = Map.of(
            "MANAGEMENT",           "Quản lý cấp cao",
            "ACCOUNTING",           "Kế toán",
            "FACTORY",              "Xưởng sản xuất",
            "SALES",                "Kinh doanh",
            "WAREHOUSE_AND_DRIVER", "Kho và giao nhận");

    private final UserRepository userRepository;
    private final EmployeeRequestRepository requestRepo;
    private final PayrollDepartmentResolver deptResolver;
    private final AttendanceEntryRepository attendanceEntryRepo;
    private final ManualLeaveUsageRepository manualUsageRepo;

    /**
     * Ranh giới ô tháng "nhập tay" vs "tính từ phiếu":
     * <ul>
     *   <li>Tháng ≤ {@link #MANUAL_USAGE_MAX_MONTH} → LẤY từ {@code ManualLeaveUsage}
     *       (mặc định 0 nếu chưa nhập). Ô ở FE cho phép click sửa.</li>
     *   <li>Tháng &gt; ranh giới → TÍNH tự động từ phiếu nghỉ đã duyệt +
     *       phút trễ/sớm bù quỹ.</li>
     * </ul>
     * Ranh giới cứng vì đây là cutover một lần cho năm 2026 (test T9 trở đi).
     */
    private static final int MANUAL_USAGE_MAX_MONTH = 8;

    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public LeaveManagementResponse buildResponse() {
        int currentYear = LocalDate.now(VN).getYear();
        int priorYear   = currentYear - 1;

        // 1) Nhân viên đang hoạt động (không khoá, không xoá)
        List<User> active = userRepository.findAll().stream()
                .filter(u -> u.getRole() != null && !u.isLockAccount() && !u.isDeleted())
                .filter(u -> u.getUsername() != null
                        && !EXCLUDED_USERNAMES.contains(u.getUsername().toLowerCase()))
                .toList();

        // 2) Phiếu nghỉ phép đã duyệt trong năm hiện tại → gom theo user
        Map<Long, List<EmployeeRequest>> leavesByUser =
                requestRepo.findAllApprovedLeavesOfYear(currentYear).stream()
                        .collect(Collectors.groupingBy(r -> r.getUser().getId()));

        // 2b) Manual usage T1..T{MANUAL_USAGE_MAX_MONTH} — nạp 1 lần cho cả bảng,
        // gom theo userId → month → minutes để buildRow tra nhanh.
        Map<Long, Map<Integer, Integer>> manualByUserMonth = manualUsageRepo
                .findAllForYear(currentYear).stream()
                .collect(Collectors.groupingBy(
                        m -> m.getUser().getId(),
                        Collectors.toMap(ManualLeaveUsage::getMonth,
                                ManualLeaveUsage::getTotalMinutes,
                                (a, b) -> a)));

        // 3) Nhóm theo phòng ban + sort trong nhóm theo role rank
        Map<String, List<User>> grouped = new LinkedHashMap<>();
        for (String d : DEPARTMENT_ORDER) grouped.put(d, new ArrayList<>());
        for (User u : active) {
            PayrollDepartment pd = deptResolver.departmentOf(u);
            if (pd == null) continue;
            String key = (pd == PayrollDepartment.WAREHOUSE || pd == PayrollDepartment.DRIVER)
                    ? "WAREHOUSE_AND_DRIVER" : pd.name();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
        }
        for (var e : grouped.entrySet()) {
            e.getValue().sort(Comparator
                    .comparingInt((User u) -> roleRank(e.getKey(), u.getRole()))
                    .thenComparing(u -> u.getFullName() != null ? u.getFullName() : ""));
        }

        // 4) Build response
        List<DepartmentGroup> depts = new ArrayList<>();
        for (String key : DEPARTMENT_ORDER) {
            List<User> users = grouped.getOrDefault(key, List.of());
            if (users.isEmpty()) continue;

            List<EmployeeRow> rows = new ArrayList<>();
            for (User u : users) {
                rows.add(buildRow(u, currentYear,
                        leavesByUser.getOrDefault(u.getId(), List.of()),
                        manualByUserMonth.getOrDefault(u.getId(), Map.of())));
            }
            depts.add(DepartmentGroup.builder()
                    .key(key)
                    .label(DEPARTMENT_LABELS.getOrDefault(key, key))
                    .employees(rows)
                    .build());
        }

        return LeaveManagementResponse.builder()
                .year(currentYear)
                .priorYear(priorYear)
                .departments(depts)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Ghi manual usage cho 1 ô — được HrController gọi khi OWNER click sửa
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Ghi tổng phép đã dùng (phút) cho 1 nhân viên × 1 tháng. Ràng buộc:
     * <ul>
     *   <li>{@code month} phải nằm trong 1..{@link #MANUAL_USAGE_MAX_MONTH} —
     *       tháng cao hơn được tính tự động, ghi tay sẽ gây rối.</li>
     *   <li>{@code year} = năm hiện tại (đang test 2026, chưa mở cho quá khứ).</li>
     *   <li>{@code minutes} ≥ 0. Bằng 0 → XOÁ dòng để ô hiển thị "-".</li>
     * </ul>
     * Trả về response bảng đầy đủ sau khi ghi để FE reload nhanh.
     */
    @Transactional
    public LeaveManagementResponse setManualUsage(Long userId, int year, int month,
                                                  int minutes, String actorUsername) {
        if (month < 1 || month > MANUAL_USAGE_MAX_MONTH) {
            throw new IllegalArgumentException(
                    "Chỉ được nhập tay cho tháng 1–" + MANUAL_USAGE_MAX_MONTH
                            + ". Từ T" + (MANUAL_USAGE_MAX_MONTH + 1)
                            + " trở đi hệ thống tự tính theo phiếu nghỉ.");
        }
        int currentYear = LocalDate.now(VN).getYear();
        if (year != currentYear) {
            throw new IllegalArgumentException(
                    "Chỉ được sửa cho năm hiện tại (" + currentYear + ").");
        }
        if (minutes < 0) {
            throw new IllegalArgumentException("Số phút không được âm.");
        }

        User u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Nhân viên không tồn tại."));
        if (u.isDeleted()) {
            throw new IllegalArgumentException("Nhân viên đã bị xoá.");
        }

        var existing = manualUsageRepo.findByUserIdAndYearAndMonth(userId, year, month);
        if (minutes == 0) {
            // 0 phút = XOÁ để ô hiển thị " - " và không chiếm dòng vô ích trong DB.
            existing.ifPresent(manualUsageRepo::delete);
        } else if (existing.isPresent()) {
            ManualLeaveUsage row = existing.get();
            row.setTotalMinutes(minutes);
            row.setUpdatedAt(System.currentTimeMillis());
            row.setUpdatedBy(actorUsername);
            manualUsageRepo.save(row);
        } else {
            manualUsageRepo.save(ManualLeaveUsage.builder()
                    .user(u)
                    .year(year)
                    .month(month)
                    .totalMinutes(minutes)
                    .updatedAt(System.currentTimeMillis())
                    .updatedBy(actorUsername)
                    .build());
        }

        return buildResponse();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Ghi số PHÉP TỒN năm trước (cột "<năm-1>") và số PHÉP NĂM HIỆN TẠI (cột
    // "<năm>") — OWNER click sửa trực tiếp trên bảng
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Ghi phép tồn năm trước ({@code User.priorYearLeaveBalance}) — cột "2025"
     * ở bảng "Quản lý phép — 2026". Chấp nhận số ngày có thập phân
     * (bội của 0.5 cho buổi, hoặc thấp hơn để bù phút).
     *
     * @param days số ngày (≥ 0). {@code 0} hợp lệ, ô sẽ hiển thị 0.
     */
    @Transactional
    public LeaveManagementResponse setPriorYearBalance(Long userId, double days) {
        if (days < 0) throw new IllegalArgumentException("Số ngày không được âm.");
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Nhân viên không tồn tại."));
        if (u.isDeleted()) throw new IllegalArgumentException("Nhân viên đã bị xoá.");
        u.setPriorYearLeaveBalance(days);
        userRepository.save(u);
        return buildResponse();
    }

    /**
     * Ghi số phép năm hiện tại ({@code entitledOffsetDays} — offset so với auto
     * formula) — cột "2026" ở bảng "Quản lý phép — 2026".
     *
     * <p>OWNER thấy 1 con số (vd. 8), sửa thành 1 con số khác (vd. 10). BE:
     * <pre>
     *   auto = entitledDaysFor(workStartDate, currentYear)   // vd. 8
     *   newOffset = desired − auto                            // vd. 10 − 8 = 2
     *   u.entitledOffsetDays = newOffset
     * </pre>
     * Kết quả: từ giờ đến hết năm, cột này = auto + 2. Mỗi 1 đầu tháng auto tự
     * lên 1 nên cột cũng lên 1 — không cần cronjob.
     *
     * @param days GIÁ TRỊ MỚI OWNER muốn thấy ở cột (≥ 0). Đây là con số cuối,
     *             không phải offset — BE tự tính offset.
     */
    @Transactional
    public LeaveManagementResponse setCurrentYearEntitled(Long userId, double days) {
        if (days < 0) throw new IllegalArgumentException("Số ngày không được âm.");
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Nhân viên không tồn tại."));
        if (u.isDeleted()) throw new IllegalArgumentException("Nhân viên đã bị xoá.");
        int currentYear = LocalDate.now(VN).getYear();
        double autoEntitled = LeaveBalanceCalculator
                .entitledDaysFor(u.getWorkStartDate(), currentYear);
        double newOffset = days - autoEntitled;
        u.setEntitledOffsetDays(newOffset);
        userRepository.save(u);
        return buildResponse();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Build 1 dòng nhân viên
    // ══════════════════════════════════════════════════════════════════════════

    private EmployeeRow buildRow(User u, int currentYear, List<EmployeeRequest> userLeaves,
                                 Map<Integer, Integer> manualByMonth) {
        // T1..T12
        List<MonthCell> months = new ArrayList<>(12);
        long totalUsedMinutes = 0;
        for (int m = 1; m <= 12; m++) {
            long monthMinutes;
            if (m <= MANUAL_USAGE_MAX_MONTH) {
                // T1..T8 — OWNER nhập tay. Không có bản ghi = 0 = ô hiển thị "-".
                // Phiếu đã duyệt và phút trễ/sớm KHÔNG cộng vào ô này để tránh
                // lộn xộn khi OWNER đã tổng hợp sẵn ở file ngoài.
                monthMinutes = manualByMonth.getOrDefault(m, 0);
            } else {
                // T9..T12 — tự động: paid days từ phiếu + phút trễ/sớm bù quỹ.
                double paidDays = calcPaidLeaveDaysInMonth(userLeaves, m, currentYear);
                int deductMinutes = leaveMinutesUsedInMonth(u.getId(), m, currentYear);
                monthMinutes = Math.round(paidDays * LEAVE_MINUTES_PER_DAY) + deductMinutes;
            }
            months.add(toCell(monthMinutes));
            totalUsedMinutes += monthMinutes;
        }

        double prior           = u.getPriorYearLeaveBalance() != null
                ? u.getPriorYearLeaveBalance() : 0.0;
        // Auto formula + offset do OWNER nhập tay ở bảng này (nullable).
        // Giữ auto để mỗi tháng tự lên 1; offset chỉ đè giá trị khởi đầu.
        double autoEntitled    = LeaveBalanceCalculator
                .entitledDaysFor(u.getWorkStartDate(), currentYear);
        double offset          = u.getEntitledOffsetDays() != null
                ? u.getEntitledOffsetDays() : 0.0;
        double currentEntitled = autoEntitled + offset;
        // Thâm niên hiện tại đang bị comment trong Export service — giữ nguyên hành vi
        double seniorityBonus  = 0.0;
        double otherBonus      = u.getBonusLeaveDays() != null ? u.getBonusLeaveDays() : 0.0;
        double totalPlus       = seniorityBonus + otherBonus;

        long entitledMinutes   = Math.round(
                (prior + currentEntitled + totalPlus) * LEAVE_MINUTES_PER_DAY);
        long remainingMinutes  = entitledMinutes - totalUsedMinutes;

        return EmployeeRow.builder()
                .userId(u.getId())
                .fullName(u.getFullName() != null ? u.getFullName() : u.getUsername())
                .position(u.getPosition() != null ? u.getPosition()
                        : (u.getRole() != null ? u.getRole().name() : null))
                .workStartDate(u.getWorkStartDate())
                .months(months)
                .priorYearBalanceDays(round2(prior))
                .currentYearEntitledDays(round2(currentEntitled))
                .totalUsed(toCell(totalUsedMinutes))
                .totalPlusDays(round2(totalPlus))
                .remaining(toCell(remainingMinutes))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Format & tính toán
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Quy đổi số phút → cell hiển thị "X ngày Y phút", ĐỒNG THỜI cấp cả 3 kênh
     * (minutes / days / extraMinutes / label) để FE khỏi phải parse chuỗi.
     *
     * <p>Đơn vị bước: <b>1 nửa-ngày = 240 phút</b>.
     * <ul>
     *   <li>1901 phút → 7 nửa-ngày dư 221 phút → 3.5 ngày 221 phút</li>
     *   <li>1440 phút → 6 nửa-ngày dư 0 phút   → 3 ngày</li>
     *   <li>-19 phút  → âm — vẫn giữ dấu để hiển thị nợ quỹ</li>
     * </ul>
     */
    public static MonthCell toCell(long minutes) {
        boolean neg = minutes < 0;
        long abs = Math.abs(minutes);
        long halfDays   = abs / 240;
        long extraMins  = abs % 240;
        double days     = halfDays * 0.5;

        String label;
        if (abs == 0) label = "0 ngày";
        else if (halfDays == 0) label = extraMins + " phút";
        else {
            String dStr = (halfDays % 2 == 0)
                    ? String.valueOf(halfDays / 2)
                    : (halfDays / 2) + ".5";
            label = extraMins == 0 ? dStr + " ngày" : dStr + " ngày " + extraMins + " phút";
        }
        if (neg) { label = "-" + label; days = -days; extraMins = -extraMins; }

        return MonthCell.builder()
                .minutes(minutes)
                .days(days)
                .extraMinutes((int) extraMins)
                .label(label)
                .build();
    }

    /**
     * Số ngày phép CÓ LƯƠNG đã nghỉ trong 1 tháng của user.
     *
     * <p>Sao chép công thức từ {@code LeaveReportExportService.calcPaidLeaveDaysInMonth}
     * — GIỮ NGUYÊN để 2 con đường (JSON vs XLSX) luôn khớp số. Nếu sau này đổi
     * chính sách chỉ cần đổi 1 chỗ: cân nhắc extract ra helper chung.
     */
    private double calcPaidLeaveDaysInMonth(List<EmployeeRequest> leaves, int month, int year) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd   = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        double total = 0;

        for (EmployeeRequest r : leaves) {
            if (r.getFromDate() == null || r.getToDate() == null) continue;
            if (r.getToDate().isBefore(monthStart) || r.getFromDate().isAfter(monthEnd)) continue;

            double paidDays = r.getPaidLeaveDays() != null ? r.getPaidLeaveDays() : 0.0;
            if (paidDays <= 0) continue;

            if (r.getDays() != null && !r.getDays().isEmpty()) {
                // ── FIX: đơn xin nghỉ có `days` chứa TẤT CẢ các buổi trong phiếu
                //   (paid + unpaid, đúng theo user xin). Trước đây vòng lặp
                //   cộng NGUYÊN dayValue của mọi buổi rơi trong tháng vào total
                //   → phiếu 3 ngày mà split 1 phép + 2 không lương vẫn bị trừ
                //   ĐỦ 3 ngày phép → quỹ về ÂM (nhân viên có 1 phép nhưng bảng
                //   hiển thị đã dùng 3, còn -2).
                //
                //   Sửa: chia tỷ lệ paidDays theo dayValue của tháng này trên
                //   tổng dayValue toàn phiếu. VD phiếu 3 ngày (2 rơi T9, 1 rơi
                //   T10), paid = 1 → T9 lấy 1 × 2/3, T10 lấy 1 × 1/3.
                double totalDayValues = 0;
                double thisMonthDayValues = 0;
                for (EmployeeRequest.LeaveDay ld : r.getDays()) {
                    if (ld.getDate() == null) continue;
                    double dv = ld.dayValue();
                    totalDayValues += dv;
                    if (!ld.getDate().isBefore(monthStart) && !ld.getDate().isAfter(monthEnd)) {
                        thisMonthDayValues += dv;
                    }
                }
                if (totalDayValues > 0) {
                    total += paidDays * thisMonthDayValues / totalDayValues;
                }
            } else {
                LocalDate effStart = r.getFromDate().isBefore(monthStart) ? monthStart : r.getFromDate();
                LocalDate effEnd   = r.getToDate().isAfter(monthEnd) ? monthEnd : r.getToDate();
                long daysInMonth = effStart.until(effEnd).getDays() + 1;
                long totalDays   = r.getFromDate().until(r.getToDate()).getDays() + 1;
                if (totalDays > 0) total += paidDays * daysInMonth / totalDays;
            }
        }
        return Math.round(total * 10.0) / 10.0;
    }

    private int leaveMinutesUsedInMonth(Long userId, int month, int year) {
        return attendanceEntryRepo
                .findByUserAndPeriod(userId, month, year)
                .map(e -> e.getLeaveMinutesUsed() != null ? e.getLeaveMinutesUsed() : 0)
                .orElse(0);
    }

    /**
     * Thứ tự trong phòng ban — GIỮ NGUYÊN {@code LeaveReportExportService.roleRank}
     * để export XLSX và bảng UI hiện đúng thứ tự.
     *
     * <p>Khớp thứ tự trong spec của user:
     * Kế toán trưởng (SUPER_ACCOUNTANT) → chuyên viên KT (ACCOUNTANT) →
     * Trưởng xưởng (SUPER_FACTORY_WORKER) → Quản lý xưởng (FACTORY_MANAGER) →
     * kế toán xưởng (FACTORY_ACCOUNTANT) → nhân viên đóng gói (FACTORY_STAFF) →
     * công nhân SX (FACTORY_PRODUCTION_WORKER) → trưởng KD (SUPER_SELLER) →
     * NV KD (SELLER) → NV kho (WAREHOUSE) → tài xế (DRIVER)…
     */
    private static int roleRank(String department, Role role) {
        if (role == null) return 99;
        return switch (department) {
            case "MANAGEMENT" -> switch (role) {
                case OWNER -> 0; case ADMIN -> 1; default -> 99;
            };
            case "ACCOUNTING" -> switch (role) {
                case SUPER_ACCOUNTANT -> 0; case ACCOUNTANT -> 1; default -> 99;
            };
            case "FACTORY" -> switch (role) {
                case SUPER_FACTORY_WORKER -> 0; case FACTORY_MANAGER -> 1;
                case FACTORY_STAFF -> 2; case FACTORY_ACCOUNTANT -> 3;
                case FACTORY_PRODUCTION_WORKER -> 4; case FACTORY_WORKER -> 5;
                case FACTORY_SECURITY -> 6; default -> 99;
            };
            case "SALES" -> switch (role) {
                case SUPER_SELLER -> 0; case SELLER -> 1; default -> 99;
            };
            case "WAREHOUSE_AND_DRIVER" -> switch (role) {
                case SUPER_WAREHOUSE -> 0; case WAREHOUSE -> 1; case DRIVER -> 2; default -> 99;
            };
            default -> 99;
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}