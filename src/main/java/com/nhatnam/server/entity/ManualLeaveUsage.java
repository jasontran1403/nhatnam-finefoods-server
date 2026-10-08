package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Tổng phép ĐÃ DÙNG của một nhân viên trong một tháng, do OWNER nhập TAY.
 *
 * <p>Dùng cho các tháng lịch sử (T1–T8/2026) mà công ty đã có sẵn số liệu ở
 * file ngoài, không muốn nhập từng đơn xin nghỉ vào hệ thống. Bảng "Quản lý
 * phép" sẽ ưu tiên số ở đây thay cho phần tính tự động từ
 * {@code EmployeeRequest} + {@code AttendanceEntry.leaveMinutesUsed}.
 *
 * <p>Từ T9/2026 trở đi, nhân viên tự tạo phiếu → OWNER duyệt → hệ thống tự
 * cộng dồn, KHÔNG cần dòng nào ở bảng này.
 *
 * <p>Đơn vị lưu: PHÚT (int). Quy đổi hiển thị:
 * <ul>
 *   <li>ngày = minutes / 480 (làm tròn xuống theo nửa buổi)</li>
 *   <li>phút lẻ = minutes % 240 (dưới nửa buổi)</li>
 * </ul>
 * Cùng công thức với {@code LeaveManagementService.toCell()}.
 */
@Entity
@Table(name = "manual_leave_usage",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_manual_leave_usage_user_year_month",
                columnNames = {"user_id", "year", "month"}
        ),
        indexes = {
                @Index(name = "ix_manual_leave_usage_year_month", columnList = "year,month")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualLeaveUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "year", nullable = false)
    private int year;

    /** 1..12 — nhưng UI chỉ cho phép nhập cho tháng ≤ 8 của năm hiện tại. */
    @Column(name = "month", nullable = false)
    private int month;

    /** Tổng phép đã dùng trong tháng, đơn vị PHÚT (≥ 0). 0 = ô hiển thị "-". */
    @Column(name = "total_minutes", nullable = false)
    private int totalMinutes;

    /** Timestamp lần chỉnh sửa gần nhất — dùng cho audit đơn giản. */
    @Column(name = "updated_at")
    private Long updatedAt;

    @Column(name = "updated_by", length = 150)
    private String updatedBy;
}