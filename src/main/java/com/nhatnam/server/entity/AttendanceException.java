package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.AttendanceExceptionType;
import com.nhatnam.server.enumtype.PayrollDepartment;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

/**
 * LỊCH NGHỈ / ĐI TRỄ / VỀ SỚM — áp dụng cho TOÀN BỘ nhân viên của MỘT BỘ PHẬN.
 *
 * <p>Do OWNER upload theo tháng, RIÊNG cho từng bộ phận (Xưởng sản xuất /
 * Kinh doanh / Kho / Kế toán). Mỗi dòng = 1 ngày trong tháng có ngoại lệ.
 * Ngày không có dòng nào thì áp dụng ca chuẩn 08:00–17:00 như bình thường.
 *
 * <pre>
 *   Ngày   | Loại                    | Mốc thời gian
 *   07/07  | Nghỉ nửa ngày - Chiều   |              → ca thu còn 08:00–12:00
 *   14/07  | Đi trễ                  | 10:00        → ca thu còn 10:00–17:00
 *   20/07  | Nghỉ cả ngày            |              → đủ công vô điều kiện
 * </pre>
 */
@Entity
@Table(
        name = "attendance_exception",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attendance_exception_day_dept",
                columnNames = {"year", "month", "day", "department"}
        ),
        indexes = @Index(name = "idx_attendance_exception_period", columnList = "year,month,department")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "month", nullable = false)
    private Integer month;

    /** Ngày trong tháng (1–31) */
    @Column(name = "day", nullable = false)
    private Integer day;

    /** Bộ phận áp dụng ngoại lệ này. Dữ liệu cũ được migration set = FACTORY. */
    @Enumerated(EnumType.STRING)
    @Column(name = "department", nullable = false, length = 20)
    @Builder.Default
    private PayrollDepartment department = PayrollDepartment.FACTORY;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private AttendanceExceptionType type;

    /**
     * MỐC THỜI GIAN — bắt buộc với Đi trễ / Về sớm, bỏ trống với các loại nghỉ.
     * <ul>
     *   <li>Đi trễ  → giờ VÀO ca mới (VD 10:00)</li>
     *   <li>Về sớm  → giờ TAN ca mới (VD 14:00)</li>
     * </ul>
     */
    @Column(name = "time_mark")
    private LocalTime timeMark;

    /** Ghi chú tự do đọc từ file (nếu có) */
    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
        if (department == null) department = PayrollDepartment.FACTORY;
    }
}