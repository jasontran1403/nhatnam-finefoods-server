package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * DÒNG CHẤM CÔNG của 1 nhân viên trong 1 {@link AttendanceSheet}.
 *
 * <p>Breakdown chi tiết từng ngày được lưu ở {@link #dailyJson} dưới dạng
 * mảng JSON để không phải tạo 31 cột / 31 bảng con:
 * <pre>
 * [
 *   {"d":1,"w":"Hai","v":1.0,"t":"WORK","in":"08:02","out":"17:00",
 *    "sym":"X","late":2,"early":0,
 *    "ss":[{"in":"08:02","out":"17:00"}]},
 *   {"d":2,"w":"Ba","v":0.0,"t":"OFF","sym":"V"},
 *   ...
 * ]
 * </pre>
 * Ý nghĩa các khoá:
 * <ul>
 *   <li>{@code d} — ngày trong tháng, {@code w} — thứ theo file</li>
 *   <li>{@code v} — số công, {@code t} — loại ngày
 *       (WORK | HALF | OFF | LEAVE | HOLIDAY | UNPAID | MISSING)</li>
 *   <li>{@code in} / {@code out} — giờ vào sớm nhất & giờ ra muộn nhất</li>
 *   <li>{@code ss} — TẤT CẢ các lượt vào/ra trong ngày (máy hỗ trợ tối đa 3 lượt)</li>
 *   <li>{@code sym} — ký hiệu gốc của máy chấm công (X có công, V vắng,
 *       O chấm thiếu)</li>
 *   <li>{@code late} / {@code early} — số phút đi trễ / về sớm</li>
 * </ul>
 */
@Entity
@Table(
        name = "attendance_entry",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attendance_entry_sheet_user",
                columnNames = {"sheet_id", "user_id"}
        ),
        indexes = @Index(name = "idx_attendance_entry_user", columnList = "user_id")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sheet_id", nullable = false)
    private AttendanceSheet sheet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Snapshot tên nhân viên tại thời điểm chấm công */
    @Column(name = "user_full_name", length = 200)
    private String userFullName;

    /**
     * MÃ NHÂN VIÊN TRÊN MÁY CHẤM CÔNG (VD: "01002") — đọc từ file.
     * KHÁC với {@code user.id}; lưu lại để lần import sau khớp nhanh và chính xác
     * hơn so với khớp theo họ tên.
     */
    @Column(name = "employee_code", length = 50)
    private String employeeCode;

    /** Tên nhân viên đúng như ghi trong file (có thể lệch chính tả so với hồ sơ) */
    @Column(name = "source_name", length = 200)
    private String sourceName;

    /** Số ngày CÓ DỮ LIỆU CHẤM CÔNG (có ít nhất 1 lần quẹt thẻ) */
    @Column(name = "present_days")
    @Builder.Default
    private Integer presentDays = 0;

    // ── Số liệu máy chấm công tự tổng hợp (lưu để đối chiếu, chưa dùng để tính lương) ──

    @Column(name = "machine_total_hours")
    private Integer machineTotalHours;

    @Column(name = "machine_total_work_units")
    private Integer machineTotalWorkUnits;

    @Column(name = "late_count")
    private Integer lateCount;

    @Column(name = "late_minutes")
    private Integer lateMinutes;

    @Column(name = "early_count")
    private Integer earlyCount;

    @Column(name = "early_minutes")
    private Integer earlyMinutes;

    /** Số công CHUẨN của tháng (T2–T6 = 1, T7 = 0.5 — hoặc theo file) */
    @Column(name = "standard_days")
    private Double standardDays;

    /** Tổng số công THỰC TẾ đi làm */
    @Column(name = "actual_days")
    @Builder.Default
    private Double actualDays = 0.0;

    /**
     * SỐ NGÀY ĐƯỢC HƯỞNG PHỤ CẤP CƠM.
     *
     * <p>Tách riêng khỏi {@link #presentDays} vì hai chỉ số này KHÔNG còn trùng
     * nhau kể từ khi có đơn nghỉ phép có lương: nhân viên nghỉ phép có lương thì
     * không quẹt thẻ (nên không vào {@code presentDays}) nhưng vẫn được tiền cơm
     * theo quy định. Ngược lại nghỉ không lương thì mất cả công lẫn tiền cơm.
     *
     * <p>Đếm theo quy tắc: ngày CÓ QUẸT THẺ, hoặc ngày nghỉ/công tác được duyệt
     * CÓ LƯƠNG. Ngày nghỉ không phép và ngày nghỉ nửa buổi mà không đi làm nốt
     * nửa còn lại đều không được tính.
     *
     * <p>{@code null} với dữ liệu cũ chưa tính lại — khi đó {@code HrService}
     * quay về dùng {@code presentDays} như trước để phiếu lương tháng cũ không đổi số.
     */
    @Column(name = "meal_days")
    private Double mealDays;

    /** Số ngày nghỉ có phép */
    @Column(name = "leave_days")
    @Builder.Default
    private Double leaveDays = 0.0;

    /**
     * Số PHÚT NGÀY PHÉP đã tiêu thụ để bù đắp phút trễ/sớm trong kỳ này.
     * Được tính lại mỗi lần recalculate — không cộng dồn qua các lần tính lại.
     */
    @Column(name = "leave_minutes_used")
    @Builder.Default
    private Integer leaveMinutesUsed = 0;

    /**
     * Số PHÚT NGÀY PHÉP CÒN LẠI sau kỳ này (sau khi đã trừ leaveMinutesUsed).
     *
     * <p>Dùng để:
     * <ul>
     *   <li>Hiển thị UI: quy đổi ra "X.5 ngày Y phút"
     *       (X.5 = (minutes ÷ 240) × 0.5, Y = minutes % 240)</li>
     *   <li>Báo cáo: minutes ÷ 480 = số ngày thực (3 số thập phân)</li>
     * </ul>
     *
     * <p>Được reset và tính lại mỗi lần recalculate để tránh cộng dồn sai.
     */
    @Column(name = "leave_balance_minutes_after")
    @Builder.Default
    private Integer leaveBalanceMinutesAfter = 0;

    /** Số ngày nghỉ không phép / không lương */
    @Column(name = "unpaid_days")
    @Builder.Default
    private Double unpaidDays = 0.0;

    /** Tổng số giờ tăng ca trong tháng — field cũ, giữ cho dashboard cũ chạy. */
    @Column(name = "overtime_hours")
    @Builder.Default
    private Double overtimeHours = 0.0;

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 2 — OT theo công thức mới (×1.5 thường, ×2.0 CN, ×3.0 lễ)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Tổng số PHÚT OT ngày thường (T2-T7, không lễ).
     * Tính bằng số phút quá 17:00 ở các ngày có giờ ra sau 17:30.
     * Xem {@code PayrollCalculationService.weekdayOtMinutes}.
     */
    @Column(name = "ot_weekday_minutes")
    @Builder.Default
    private Integer otWeekdayMinutes = 0;

    /** Tổng số PHÚT đi làm chủ nhật (= giờ ra − giờ vào, không trừ nghỉ trưa). */
    @Column(name = "ot_sunday_minutes")
    @Builder.Default
    private Integer otSundayMinutes = 0;

    /** Tổng số PHÚT đi làm ngày lễ (= giờ ra − giờ vào, không trừ nghỉ trưa). */
    @Column(name = "ot_holiday_minutes")
    @Builder.Default
    private Integer otHolidayMinutes = 0;

    /** Tổng tiền OT đã quy đổi và làm tròn LÊN bội 5.000đ. */
    @Column(name = "ot_amount")
    @Builder.Default
    private Long otAmount = 0L;

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 4 — SNAPSHOT LƯƠNG LÚC CHỐT
    // ══════════════════════════════════════════════════════════════════════
    //
    // Khi CompanyAttendanceService.calculate() chạy, nó tính toàn bộ breakdown
    // của mỗi nhân viên (lương cơ bản prorated, BH, phụ cấp từng loại, thưởng,
    // thực nhận) và lưu vào các field dưới đây. Mục đích:
    //
    //   1. OWNER sửa cấu hình lương / phụ cấp của 1 nhân viên sau khi đã tính
    //      lương tháng cũ → file lương tổng hợp + file bank export vẫn giữ
    //      đúng số ĐÃ TÍNH, không bị kéo theo thay đổi mới.
    //   2. Export không phải recompute SalaryBreakdownDto cho từng NV mỗi lần
    //      → nhanh hơn đáng kể cho công ty 100+ nhân viên.
    //
    // Chi tiết breakdown lưu dạng JSON (serialize của SalaryBreakdownDto) để
    // tránh phải thêm 20+ cột. Hai cột denormalized (totalIncome, netPay) giữ
    // riêng để query/aggregate (báo cáo Chart tổng quỹ lương theo tháng).
    //
    // Khi "Mở lại" (reopen), tất cả snapshot bị xoá về null — xem
    // AttendanceEntry.clearSnapshot().

    /** JSON serialize của SalaryBreakdownDto tại thời điểm calculate(). */
    @Column(name = "salary_snapshot_json", columnDefinition = "TEXT")
    private String salarySnapshotJson;

    /** Lương CƠ BẢN đủ tháng (standardBaseSalary) — snapshot. */
    @Column(name = "snap_base_salary")
    private Long snapBaseSalary;

    /** Lương theo chấm công (prorated theo công thực tế) — snapshot. */
    @Column(name = "snap_prorated_base")
    private Long snapProratedBase;

    /** Lương đóng BH — snapshot. */
    @Column(name = "snap_insurance_salary")
    private Long snapInsuranceSalary;

    /** Tổng BH (NV + công ty = ~32% insuranceSalary) — snapshot. */
    @Column(name = "snap_insurance_total")
    private Long snapInsuranceTotal;

    /** Phụ cấp CƠM — snapshot (đã trừ các ngày không được cơm theo Phase 1). */
    @Column(name = "snap_meal_allowance")
    private Long snapMealAllowance;

    /** Phụ cấp ĐIỆN THOẠI — snapshot. */
    @Column(name = "snap_phone_allowance")
    private Long snapPhoneAllowance;

    /** Phụ cấp OT — snapshot (= otAmount, lặp lại ở đây để export đọc nhất quán). */
    @Column(name = "snap_ot_allowance")
    private Long snapOtAllowance;

    /** Phụ cấp TRÁCH NHIỆM — snapshot. */
    @Column(name = "snap_responsibility_allowance")
    private Long snapResponsibilityAllowance;

    /** Tổng các phụ cấp CÒN LẠI (không nằm trong 4 cột cố định trên). */
    @Column(name = "snap_other_allowance")
    private Long snapOtherAllowance;

    /** Thưởng (KPI + doanh thu + thưởng thường). */
    @Column(name = "snap_bonus_total")
    private Long snapBonusTotal;

    /** Tổng thu nhập (lương theo chấm công + tất cả phụ cấp + thưởng, chưa trừ BH NV). */
    @Column(name = "snap_total_income")
    private Long snapTotalIncome;

    /** Lương THỰC NHẬN sau cùng, đã làm tròn (cột cuối file lương tổng hợp). */
    @Column(name = "snap_net_pay")
    private Long snapNetPay;

    /** Mốc timestamp của snapshot — biết snapshot này cũ hay mới so với lần chỉnh cấu hình gần nhất. */
    @Column(name = "snap_computed_at")
    private Long snapComputedAt;

    /**
     * Có snapshot hợp lệ không (dùng để quyết định export dùng snapshot hay
     * fallback về compute live). Trả true khi {@link #snapNetPay} đã được set.
     */
    public boolean hasSnapshot() {
        return snapNetPay != null;
    }

    /** Xoá sạch snapshot — gọi khi reopen() hoặc khi file chấm công bị thay. */
    public void clearSnapshot() {
        this.salarySnapshotJson = null;
        this.snapBaseSalary = null;
        this.snapProratedBase = null;
        this.snapInsuranceSalary = null;
        this.snapInsuranceTotal = null;
        this.snapMealAllowance = null;
        this.snapPhoneAllowance = null;
        this.snapOtAllowance = null;
        this.snapResponsibilityAllowance = null;
        this.snapOtherAllowance = null;
        this.snapBonusTotal = null;
        this.snapTotalIncome = null;
        this.snapNetPay = null;
        this.snapComputedAt = null;
    }

    /** Breakdown từng ngày — mảng JSON (xem javadoc class) */
    @Column(name = "daily_json", columnDefinition = "TEXT")
    private String dailyJson;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    /**
     * Snapshot trạng thái part-time tại thời điểm tính lương tháng này.
     * <p>Giúp khi tính lại tháng cũ vẫn giữ đúng trạng thái — ví dụ T8 part-time,
     * T9 lên full-time, tính lại T8 vẫn dùng part-time.
     */
    @Column(name = "part_time")
    @Builder.Default
    private Boolean partTime = false;

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }
}