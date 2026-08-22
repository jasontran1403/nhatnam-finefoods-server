package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.EmployeeRequestStatus;
import com.nhatnam.server.enumtype.EmployeeRequestType;
import com.nhatnam.server.enumtype.PayrollDepartment;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ĐƠN DO NHÂN VIÊN TỰ TẠO — nghỉ phép / công tác / đi trễ / về sớm / quên chấm công.
 *
 * <h3>Vì sao không dùng lại {@link AttendanceLeaveRequest}</h3>
 * Bảng cũ là kết quả PARSE TỪ FILE EXCEL do OWNER tải lên: mỗi dòng gắn cứng vào
 * {@code (year, month, day)} của file, không có người tạo, không có lý do, và bị
 * XOÁ SẠCH mỗi lần tải lại file. Đơn tự tạo thì ngược lại — do nhân viên sở hữu,
 * phải sống lâu dài, và có vòng đời duyệt riêng. Nhét chung một bảng thì thao tác
 * "tải lại file" sẽ xoá mất đơn của nhân viên.
 *
 * <h3>Ngày hiệu lực ≠ ngày tạo</h3>
 * Đơn được lấy ra theo {@link #fromDate}–{@link #toDate}, KHÔNG theo
 * {@link #createdAt}. Tạo ngày 29/5 nhưng xin nghỉ 3/6–4/6 thì đơn thuộc về
 * THÁNG 6, và chỉ tác động lên 2 ngày đó. Đây là lý do bảng lưu ngày dưới dạng
 * {@link LocalDate} thay vì bộ ba (year, month, day) như bảng cũ — đơn hoàn toàn
 * có thể vắt qua hai tháng.
 *
 * <h3>Nghỉ ít hơn 1 ngày</h3>
 * Khai thêm {@link #fromTime}–{@link #toTime}. Khoảng giờ này được TRỪ KHỎI khung
 * giờ phải có mặt của ngày, chứ không dời giờ vào/giờ ra. Nhờ vậy nhân viên xin
 * nghỉ 3 tiếng buổi chiều mà sáng vẫn đi trễ thì phần đi trễ vẫn bị tính — trừ
 * khi có thêm một đơn "Đi trễ" riêng cho đúng ngày đó.
 */
@Entity
@Table(
        name = "employee_request",
        indexes = {
                @Index(name = "idx_emp_request_user", columnList = "user_id"),
                @Index(name = "idx_emp_request_range", columnList = "from_date,to_date"),
                @Index(name = "idx_emp_request_status", columnList = "status"),
                @Index(name = "idx_emp_request_dept", columnList = "department,from_date")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ══════════════════════════════════════════════════════════════════════════
    // NGƯỜI TẠO
    // ══════════════════════════════════════════════════════════════════════════

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Snapshot họ tên lúc tạo đơn — đổi tên hồ sơ về sau không làm lệch đơn cũ. */
    @Column(name = "user_full_name", length = 200)
    private String userFullName;

    /**
     * BỘ PHẬN TÍNH LƯƠNG của người tạo, chốt tại thời điểm tạo đơn.
     * Lưu snapshot vì nhân viên có thể được chuyển bộ phận giữa chừng; đơn của
     * tháng cũ phải ở lại đúng bộ phận đã tính lương tháng đó.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "department", length = 20)
    private PayrollDepartment department;

    // ══════════════════════════════════════════════════════════════════════════
    // NỘI DUNG ĐƠN
    // ══════════════════════════════════════════════════════════════════════════

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private EmployeeRequestType type;

    /**
     * NGÀY BẮT ĐẦU hiệu lực.
     * Với loại 1 ngày (đi trễ / về sớm / quên chấm công) thì
     * {@code fromDate == toDate} — luôn ghi cả hai để mọi truy vấn dùng chung
     * một điều kiện chồng lấn khoảng ngày.
     */
    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    /** NGÀY KẾT THÚC hiệu lực (bao gồm chính ngày này). */
    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /**
     * NGHỈ NỬA NGÀY — DỮ LIỆU CŨ.
     *
     * <p>Đã được thay bằng {@link #days}: một cờ duy nhất cho cả phiếu không diễn
     * tả được "nghỉ 24–26 cả ngày, 27 chỉ nghỉ sáng". Giữ lại để đọc phiếu tạo
     * trước khi có danh sách buổi.
     */
    @Column(name = "half_day")
    @Builder.Default
    private Boolean halfDay = false;

    /**
     * CÁC BUỔI NGHỈ THỰC TẾ — nguồn sự thật của phiếu nghỉ phép.
     *
     * <h3>Vì sao không dùng khoảng ngày</h3>
     * Khoảng {@code fromDate–toDate} chỉ tả được đoạn LIÊN TỤC và NGUYÊN NGÀY.
     * Hai nhu cầu có thật đều gãy:
     * <pre>
     *   · Nghỉ 24/7 → hết sáng 27/7   = 3,5 ngày  → cờ nửa ngày cho cả phiếu là sai
     *   · Nghỉ 24, 25, 27, 29         = ngắt quãng → phải xé thành 3 phiếu
     * </pre>
     * Danh sách buổi giải quyết cả hai: mỗi ngày khai riêng sáng / chiều, ngày
     * không nghỉ thì không có trong danh sách.
     *
     * <p>{@link #fromDate} / {@link #toDate} vẫn được ghi = ngày nhỏ nhất / lớn
     * nhất trong danh sách. Chúng KHÔNG còn là định nghĩa của phiếu nữa, chỉ là
     * chỉ mục để các truy vấn theo kỳ lọc nhanh; ngày thật vẫn phải tra ở đây.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "employee_request_day",
            joinColumns = @JoinColumn(name = "request_id"),
            indexes = @Index(name = "idx_emp_request_day_date", columnList = "leave_date")
    )
    @OrderBy("date ASC")
    @Builder.Default
    private List<LeaveDay> days = new ArrayList<>();

    /**
     * MỘT NGÀY NGHỈ trong phiếu, tách theo hai buổi.
     *
     * <p>Chia buổi thay vì lưu số giờ: nghỉ 3 tiếng không quy được ra số ngày phép
     * tròn trịa, còn sáng/chiều thì luôn là 0,5 — số dư phép không bao giờ lẻ.
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeaveDay {

        @Column(name = "leave_date", nullable = false)
        private LocalDate date;

        /** Nghỉ buổi sáng. */
        @Column(name = "morning", nullable = false)
        private boolean morning;

        /** Nghỉ buổi chiều. */
        @Column(name = "afternoon", nullable = false)
        private boolean afternoon;

        /** Số ngày phép ngày này tiêu tốn: 0 / 0,5 / 1. */
        public double dayValue() {
            return (morning ? 0.5 : 0.0) + (afternoon ? 0.5 : 0.0);
        }

        public boolean isFullDay() { return morning && afternoon; }
        public boolean isEmpty()   { return !morning && !afternoon; }
    }

    /**
     * DỮ LIỆU CŨ — giờ bắt đầu nghỉ, từ thời còn cho nghỉ theo khung giờ tự do.
     *
     * <p>Phiếu mới KHÔNG bao giờ ghi cột này nữa (chỉ còn nửa ngày / cả ngày), nhưng
     * phiếu cũ vẫn phải đọc được để lương những tháng đã chốt không đổi kết quả.
     *
     * <p>KHÔNG đánh {@code @Deprecated}: đây là dữ liệu bắt buộc phải đọc lâu dài,
     * gắn nhãn đó chỉ sinh ra một đống cảnh báo vĩnh viễn ở chỗ code chạy đúng.
     */
    @Column(name = "from_time")
    private LocalTime fromTime;

    /** DỮ LIỆU CŨ — giờ kết thúc nghỉ. Xem ghi chú ở {@link #fromTime}. */
    @Column(name = "to_time")
    private LocalTime toTime;

    /** Số phút xin đi trễ / về sớm. Bắt buộc với 2 loại đó, bỏ trống với loại khác. */
    @Column(name = "minutes")
    private Integer minutes;

    /** LÝ DO — BẮT BUỘC. Không có lý do thì OWNER không có căn cứ để duyệt. */
    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    // ══════════════════════════════════════════════════════════════════════════
    // DUYỆT
    // ══════════════════════════════════════════════════════════════════════════

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private EmployeeRequestStatus status = EmployeeRequestStatus.PENDING;

    /**
     * SỐ CÔNG BỊ TRỪ — chỉ dùng khi {@code status = APPROVED_DEDUCTED}.
     * Ràng buộc nghiệp vụ: {@code 0.01 ≤ deductedDays ≤ 1}. Trừ 0 công thì đã là
     * duyệt thường, trừ hơn 1 công thì vượt quá công của chính ngày đó.
     */
    @Column(name = "deducted_days")
    private Double deductedDays;

    /**
     * SỐ NGÀY TRỪ VÀO QUỸ PHÉP NĂM — chỉ dùng cho phiếu {@code type = LEAVE}.
     *
     * <p>Tách hẳn khỏi {@link #deductedDays}: trường kia là "trừ công" cho đơn đi
     * trễ/về sớm, giới hạn 0–1 và KHÔNG liên quan tới quỹ phép. Dùng chung một
     * cột cho hai khái niệm sẽ khiến báo cáo phép cộng nhầm cả đơn đi trễ.
     *
     * <p>Người duyệt tự nhập con số này, không suy ra tự động từ khoảng ngày:
     * xin nghỉ 3 ngày mà quỹ chỉ còn 1 thì duyệt 1 ngày phép + 2 ngày không lương.
     * Máy không đoán được ranh giới đó (còn tuỳ cuối tuần, ngày lễ, thoả thuận
     * riêng) nên để người duyệt quyết.
     *
     * <p>ĐÂY CHÍNH LÀ SỔ TRỪ PHÉP. Số ngày đã dùng trong năm = tổng cột này trên
     * các phiếu đã duyệt — không có bảng số dư riêng để lệch với thực tế.
     */
    @Column(name = "paid_leave_days")
    private Double paidLeaveDays;

    /** Số ngày trong phiếu được duyệt nghỉ KHÔNG LƯƠNG (không trừ quỹ phép). */
    @Column(name = "unpaid_leave_days")
    private Double unpaidLeaveDays;

    /**
     * Ghi chú của OWNER khi duyệt, hoặc LÝ DO TỪ CHỐI (bắt buộc khi từ chối).
     * Nội dung này được đưa nguyên văn vào thông báo gửi cho nhân viên.
     */
    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_by_name", length = 200)
    private String decidedByName;

    @Column(name = "decided_at")
    private Long decidedAt;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == null) createdAt = now;
        if (status == null) status = EmployeeRequestStatus.PENDING;
        if (toDate == null) toDate = fromDate;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }

    // ══════════════════════════════════════════════════════════════════════════
    // TIỆN ÍCH NGHIỆP VỤ
    // ══════════════════════════════════════════════════════════════════════════

    /** Đơn còn chờ duyệt. */
    public boolean isPending() { return status == EmployeeRequestStatus.PENDING; }

    /** Đơn có hiệu lực khi tính công. */
    public boolean isEffective() { return status != null && status.isEffective(); }

    /**
     * Đơn có phủ lên ngày {@code d} hay không.
     *
     * <p>Có danh sách buổi thì tra ĐÚNG danh sách — phiếu nghỉ 24, 25, 27 KHÔNG
     * phủ ngày 26 dù ngày đó nằm giữa fromDate và toDate.
     */
    public boolean covers(LocalDate d) {
        if (d == null) return false;
        if (hasDays()) return dayAt(d) != null;
        return fromDate != null && toDate != null
                && !d.isBefore(fromDate) && !d.isAfter(toDate);
    }

    /** Nghỉ theo khung giờ (ít hơn 1 ngày) thay vì nghỉ trọn ngày. */
    public boolean isPartialDay() {
        return fromTime != null && toTime != null && fromTime.isBefore(toTime);
    }

    /** Số ngày phép đã trừ của phiếu này — 0 nếu không phải phiếu nghỉ phép đã duyệt. */
    public double effectivePaidLeaveDays() {
        if (type != com.nhatnam.server.enumtype.EmployeeRequestType.LEAVE) return 0.0;
        if (!isEffective()) return 0.0;                 // PENDING / REJECTED không trừ quỹ
        return paidLeaveDays == null ? 0.0 : Math.max(0.0, paidLeaveDays);
    }

    /** Tổng số ngày theo lịch mà phiếu phủ lên. */
    public long calendarDays() {
        if (fromDate == null || toDate == null) return 0;
        return java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) + 1;
    }

    /** Phiếu có khai danh sách buổi nghỉ hay không (phiếu mới luôn có). */
    public boolean hasDays() { return days != null && !days.isEmpty(); }

    /** DỮ LIỆU CŨ: cờ nửa ngày cho cả phiếu. */
    public boolean isHalfDay() { return !hasDays() && Boolean.TRUE.equals(halfDay); }

    /**
     * SỐ NGÀY PHÉP mà phiếu này tiêu tốn — trần khi duyệt.
     * Cộng theo BUỔI nên ra được 3,5 / 4 / 0,5… một cách tự nhiên.
     */
    public double leaveDays() {
        if (hasDays()) {
            double sum = 0;
            for (LeaveDay d : days) sum += d.dayValue();
            return Math.round(sum * 100.0) / 100.0;
        }
        return isHalfDay() ? 0.5 : (double) calendarDays();   // phiếu cũ
    }

    /** Buổi nghỉ của một ngày cụ thể, null nếu ngày đó không nghỉ. */
    public LeaveDay dayAt(LocalDate d) {
        if (d == null || !hasDays()) return null;
        for (LeaveDay x : days) if (d.equals(x.getDate())) return x;
        return null;
    }

    /** Số công bị trừ đã chuẩn hoá — 0 nếu không phải nhánh trừ công. */
    public double effectiveDeduction() {
        if (status != EmployeeRequestStatus.APPROVED_DEDUCTED || deductedDays == null) return 0.0;
        return Math.max(0.0, Math.min(1.0, deductedDays));
    }
}