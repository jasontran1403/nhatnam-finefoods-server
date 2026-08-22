package com.nhatnam.server.dto.hr;

import com.nhatnam.server.enumtype.EmployeeRequestStatus;
import com.nhatnam.server.enumtype.EmployeeRequestType;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Toàn bộ DTO của luồng "Đơn nhân viên". */
public final class EmployeeRequestDtos {

    private EmployeeRequestDtos() {}

    // ══════════════════════════════════════════════════════════════════════════
    // NHÂN VIÊN TẠO ĐƠN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Body tạo đơn.
     *
     * <p>Chỉ ràng buộc được ở đây những thứ độc lập với loại đơn (lý do, ngày bắt
     * buộc). Các luật phụ thuộc loại — cửa sổ ngày cho phép, bắt buộc số phút với
     * đi trễ/về sớm, khoảng ngày phải xuôi chiều — nằm trong service vì chúng cần
     * biết {@code type} và ngày hiện tại theo giờ Việt Nam.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CreateRequestDto {

        @NotNull(message = "Chưa chọn loại phiếu")
        private EmployeeRequestType type;

        @NotNull(message = "Chưa chọn ngày bắt đầu")
        private LocalDate fromDate;

        /** Bỏ trống với loại 1 ngày — server tự gán bằng {@code fromDate}. */
        private LocalDate toDate;

        /**
         * CÁC BUỔI NGHỈ — chỉ dùng cho phiếu NGHỈ PHÉP.
         *
         * <p>Mỗi phần tử là một ngày kèm cờ sáng / chiều. Nhờ vậy khai được:
         * <pre>
         *   nửa ngày   : 27/7 morning=true, afternoon=false        → 0,5 ngày
         *   3,5 ngày   : 24,25,26 đủ 2 buổi + 27 chỉ sáng          → 3,5 ngày
         *   ngắt quãng : 24, 25, 27, 29 (bỏ 26 và 28)              → 4 ngày
         * </pre>
         *
         * <p>Ngày không tick buổi nào sẽ bị BỎ khỏi phiếu. Để trống cả danh sách
         * thì server suy ra nghỉ nguyên khoảng {@code fromDate–toDate}, cả ngày.
         */
        private List<LeaveDayDto> days;

        /** @deprecated Cờ nửa ngày cho cả phiếu — dùng {@link #days} thay thế. */
        private Boolean halfDay;

        /** Khung giờ nghỉ khi xin nghỉ ít hơn 1 ngày. */
        private LocalTime fromTime;
        private LocalTime toTime;

        /** Số phút xin đi trễ / về sớm. */
        @Min(value = 1, message = "Số phút phải lớn hơn 0")
        @Max(value = 600, message = "Số phút vượt quá một ca làm việc")
        private Integer minutes;

        @NotBlank(message = "Bắt buộc nhập lý do")
        @Size(max = 2000, message = "Lý do quá dài")
        private String reason;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OWNER DUYỆT
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Body của 3 thao tác duyệt. Trường nào bắt buộc phụ thuộc {@code action}:
     * <pre>
     *   APPROVE  → paid bắt buộc (true = có phép / false = không phép)
     *   DEDUCT   → deductedDays bắt buộc, 0.01 ≤ x ≤ 1
     *   REJECT   → note bắt buộc (lý do từ chối)
     * </pre>
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DecideRequestDto {

        /** APPROVE | DEDUCT | REJECT */
        @NotBlank(message = "Chưa chọn thao tác")
        private String action;

        /** Có phép (hưởng đủ công) hay không phép (công = 0). Dùng với APPROVE. */
        private Boolean paid;

        /** Số công bị trừ. Dùng với DEDUCT. */
        @DecimalMin(value = "0.01", message = "Số công trừ tối thiểu là 0.01")
        @DecimalMax(value = "1.0", message = "Số công trừ tối đa là 1")
        private Double deductedDays;

        /**
         * PHIẾU NGHỈ PHÉP: số ngày trừ vào quỹ phép năm. Dùng với APPROVE.
         *
         * <p>Để trống thì hệ thống tự lấy trọn số ngày của phiếu (nếu quỹ đủ) —
         * giữ nguyên thao tác một chạm cho trường hợp thường gặp nhất.
         */
        @DecimalMin(value = "0.0", message = "Số ngày phép không được âm")
        private Double paidLeaveDays;

        /** PHIẾU NGHỈ PHÉP: số ngày duyệt nghỉ KHÔNG LƯƠNG. Dùng với APPROVE. */
        @DecimalMin(value = "0.0", message = "Số ngày không lương không được âm")
        private Double unpaidLeaveDays;

        /** Ghi chú của OWNER; là LÝ DO TỪ CHỐI khi action = REJECT. */
        @Size(max = 2000, message = "Nội dung quá dài")
        private String note;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRẢ VỀ
    // ══════════════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EmployeeRequestDto {
        private Long id;

        private Long userId;
        private String userFullName;
        private String roleLabel;
        private String department;
        private String departmentLabel;

        private String type;
        private String typeLabel;

        private LocalDate fromDate;
        private LocalDate toDate;

        /** true = nghỉ nửa ngày (dữ liệu cũ, phiếu mới dùng {@link #days}). */
        private Boolean halfDay;

        /** Các buổi nghỉ thực tế — nguồn sự thật của phiếu nghỉ phép. */
        private List<LeaveDayDto> days;

        /** Tổng số ngày phép phiếu tiêu tốn (cộng theo buổi): 0,5 / 3,5 / 4… */
        private Double leaveDays;
        private LocalTime fromTime;
        private LocalTime toTime;
        private Integer minutes;

        /**
         * Mô tả thời gian đã dựng sẵn cho UI — "3/6/2026 → 4/6/2026 (2 ngày)",
         * "3/6/2026 · 30 phút", "3/6/2026". Dựng ở server để chi tiết đơn, danh
         * sách và nội dung thông báo luôn dùng chung một cách diễn đạt.
         */
        private String periodText;

        /** Số ngày đơn phủ lên, tính cả hai đầu. */
        private Double totalDays;

        private String reason;

        private String status;
        private String statusLabel;
        private Double deductedDays;

        /** Số ngày trừ vào quỹ phép năm (chỉ phiếu nghỉ phép đã duyệt). */
        private Double paidLeaveDays;

        /** Số ngày duyệt nghỉ không lương. */
        private Double unpaidLeaveDays;
        private String decisionNote;
        private String decidedByName;
        private Long decidedAt;

        private Long createdAt;
        private Long updatedAt;

        /** OWNER còn thao tác được không (chỉ đơn đang chờ duyệt). */
        private Boolean actionable;
    }

    /** Cấu hình cho form tạo đơn — FE lấy 1 lần để dựng dropdown + giới hạn lịch. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RequestTypeOptionDto {
        private String value;
        private String label;

        /** Khai theo khoảng ngày (DateRangePicker) hay 1 ngày (DatePicker). */
        private Boolean rangeBased;

        /** Bắt buộc nhập số phút. */
        private Boolean minutesBased;

        /**
         * Ngày SỚM NHẤT và MUỘN NHẤT được chọn, đã quy đổi ra ngày thật theo giờ
         * Việt Nam. FE chỉ việc truyền thẳng vào {@code minDate} / {@code maxDate}
         * của lịch, không tự tính offset để khỏi lệch luật với server.
         */
        private LocalDate minDate;
        private LocalDate maxDate;

        /** Cho phép khai khung giờ nghỉ ít hơn 1 ngày. */
        private Boolean allowPartialDay;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RequestFormConfigDto {
        private LocalDate today;
        private List<RequestTypeOptionDto> types;

        /** Giờ vào / tan ca chuẩn — form dùng làm giá trị gợi ý cho đồng hồ. */
        private String shiftStart;
        private String shiftEnd;
    }

    /** Thống kê nhanh cho panel của OWNER. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RequestSummaryDto {
        private long pending;
        private long total;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  QUỸ NGÀY PHÉP
    // ══════════════════════════════════════════════════════════════════════════

    /** Số dư phép của 1 nhân viên trong 1 năm. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeaveBalanceDto {
        private Long userId;
        private String fullName;
        private Integer year;

        /** Ngày vào làm — null nghĩa là chưa khai báo, quỹ phép chỉ có phần cơ bản. */
        private Long workStartDate;
        /** Số năm thâm niên tính tới cuối năm đó. */
        private Integer seniorityYears;

        /** Tổng ngày phép được hưởng trong năm (12 + thâm niên). */
        private Double entitledDays;
        /** Đã dùng — tổng paidLeaveDays của các phiếu nghỉ phép đã duyệt. */
        private Double usedDays;
        /** Còn lại; CÓ THỂ ÂM nếu trước đó duyệt vượt quỹ — không che đi. */
        private Double remainingDays;

        /** Cảnh báo cho người duyệt: chưa khai báo ngày vào làm nên quỹ có thể sai. */
        private Boolean missingWorkStartDate;
    }

    /** Một dòng trong lịch sử nghỉ phép. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeaveHistoryItemDto {
        private Long id;
        private String fromDate;
        private String toDate;
        private Long calendarDays;
        private Double paidLeaveDays;
        private Double unpaidLeaveDays;
        private String status;
        private String statusLabel;
        private String reason;
        private String decisionNote;
        private String decidedByName;
        private Long decidedAt;
    }

    /** MỘT NGÀY NGHỈ trong phiếu, tách theo hai buổi. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeaveDayDto {
        private LocalDate date;
        /** Nghỉ buổi sáng. */
        private Boolean morning;
        /** Nghỉ buổi chiều. */
        private Boolean afternoon;
    }
}