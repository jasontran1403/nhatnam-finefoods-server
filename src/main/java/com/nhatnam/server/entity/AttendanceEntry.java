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

    /** Số ngày nghỉ không phép / không lương */
    @Column(name = "unpaid_days")
    @Builder.Default
    private Double unpaidDays = 0.0;

    /** Tổng số giờ tăng ca trong tháng */
    @Column(name = "overtime_hours")
    @Builder.Default
    private Double overtimeHours = 0.0;

    /** Breakdown từng ngày — mảng JSON (xem javadoc class) */
    @Column(name = "daily_json", columnDefinition = "TEXT")
    private String dailyJson;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }
}