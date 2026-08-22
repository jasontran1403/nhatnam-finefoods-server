package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.AttendanceExceptionType;
import com.nhatnam.server.enumtype.PayrollDepartment;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

/**
 * ĐƠN XIN ĐI TRỄ / VỀ SỚM / NGHỈ PHÉP CỦA CÁ NHÂN.
 *
 * <p>Khác với {@link AttendanceException} (áp dụng cho cả bộ phận), bảng này chỉ
 * áp dụng cho ĐÚNG 1 nhân viên trong ĐÚNG 1 ngày.
 *
 * <p><b>Chỉ đơn ĐÃ DUYỆT mới được tính.</b> Đơn bị TỪ CHỐI xem như không tồn tại,
 * ngày đó bị trừ công theo quy tắc thông thường.
 *
 * <pre>
 *   Họ tên          | Ngày  | Loại          | Mốc giờ | Trạng thái
 *   Lê Thị Út       | 07/07 | Đi trễ        | 10:00   | Đã duyệt   → ca 10:00–17:00
 *   Nguyễn Văn A    | 09/07 | Nghỉ cả ngày  |         | Đã duyệt   → đủ công
 *   Trần Thị B      | 10/07 | Về sớm        | 15:00   | Từ chối    → tính bình thường
 * </pre>
 *
 * <p>File đơn được upload RIÊNG theo bộ phận nên bản ghi mang thêm cột
 * {@code department} — xoá/tải lại file của bộ phận nào chỉ ảnh hưởng bộ phận đó.
 */
@Entity
@Table(
        name = "attendance_leave_request",
        indexes = {
                @Index(name = "idx_leave_request_period", columnList = "year,month,department"),
                @Index(name = "idx_leave_request_user", columnList = "user_id")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceLeaveRequest {

    /** Trạng thái duyệt đơn. */
    public enum LeaveStatus {
        /** Đã duyệt — ngày đó được tính đủ công theo loại đơn. */
        APPROVED("Đã duyệt"),
        /** Từ chối — bỏ qua đơn, tính công theo quy tắc thông thường. */
        REJECTED("Từ chối"),
        /** Chờ duyệt — chưa có hiệu lực, xử lý như Từ chối cho tới khi được duyệt. */
        PENDING("Chờ duyệt");

        private final String label;
        LeaveStatus(String label) { this.label = label; }
        public String getLabel() { return label; }

        /** Đọc trạng thái từ nhãn tiếng Việt trong file Excel. */
        public static LeaveStatus fromLabel(String raw) {
            if (raw == null || raw.isBlank()) return PENDING;
            String n = norm(raw);
            for (LeaveStatus s : values()) {
                if (norm(s.label).equals(n) || s.name().equalsIgnoreCase(raw.trim())) return s;
            }
            // Một số cách gõ thường gặp
            if (n.contains("duyet") && !n.contains("cho")) return APPROVED;
            if (n.contains("tu choi") || n.contains("khong duyet")) return REJECTED;
            return PENDING;
        }

        private static String norm(String s) {
            return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                    .replace('đ', 'd').replaceAll("\\s+", " ").trim();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "day", nullable = false)
    private Integer day;

    /** Bộ phận của file đơn. Dữ liệu cũ được migration set = FACTORY. */
    @Enumerated(EnumType.STRING)
    @Column(name = "department", nullable = false, length = 20)
    @Builder.Default
    private PayrollDepartment department = PayrollDepartment.FACTORY;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Họ tên đọc từ file — giữ lại cả khi không khớp được nhân viên nào */
    @Column(name = "source_name", length = 200)
    private String sourceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private AttendanceExceptionType type;

    /** Mốc giờ — bắt buộc với Đi trễ / Về sớm */
    @Column(name = "time_mark")
    private LocalTime timeMark;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private LeaveStatus status = LeaveStatus.PENDING;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
        if (department == null) department = PayrollDepartment.FACTORY;
    }

    /** Đơn có hiệu lực (đã được duyệt). */
    public boolean isEffective() { return status == LeaveStatus.APPROVED; }
}