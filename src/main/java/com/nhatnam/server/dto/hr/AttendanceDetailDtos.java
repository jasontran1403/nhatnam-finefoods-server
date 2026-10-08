package com.nhatnam.server.dto.hr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO cho PAGE CHUYÊN CẦN — Phase 6 (10/2026).
 *
 * <p>Hiển thị bảng matrix: dòng = nhân viên, cột = từng ngày trong tháng + 2
 * cột cuối là tổng phút trễ / tổng phút sớm.
 *
 * <p>Mỗi ô ngày là 1 {@link DayCell} chứa 2 số:
 *   - {@code late} > 0 → về sớm/đi trễ (hiển thị đỏ)
 *   - {@code late} < 0 → đi sớm/về trễ hơn giờ chuẩn (hiển thị xanh dương)
 *   - {@code late} = 0 → đúng giờ (hiển thị xanh lá)
 * Áp dụng cho cả 2 giá trị (in và out).
 */
public class AttendanceDetailDtos {

    /**
     * 1 ô ngày — biểu diễn delta giờ vào và giờ ra so với 8:00 và 17:00.
     *
     * <p>Quy tắc:
     * - Khung sáng: 8:00-8:05 = đúng giờ (0); 8:06+ = trễ (minutes dương);
     *   7:59- = sớm (minutes âm).
     * - Khung chiều: 16:55-17:00 = đúng giờ (0); 16:54- = về sớm (minutes dương);
     *   17:01+ = về trễ (minutes âm).
     *
     * <p>Số dương (late) = BÁO ĐỘNG; số âm = TÍCH CỰC.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DayCell {
        /** Ngày trong tháng (1..31). */
        private int day;

        /** Delta phút vào: >0 trễ (đỏ), <0 sớm (xanh dương), =0 đúng giờ (xanh lá). */
        private Integer inDelta;

        /** Delta phút ra: >0 về sớm (đỏ), <0 về trễ (xanh dương), =0 đúng giờ (xanh lá). */
        private Integer outDelta;

        /** Có mặt (có punch) hay không. */
        private boolean present;

        /** Chủ nhật. */
        private boolean sunday;

        /** Ngày lễ. */
        private boolean holiday;

        /** Giờ ra là do hệ thống fill 17:00 (thiếu chấm công ra). */
        private boolean defaultedOut;

        /** Giờ vào là do hệ thống fill 08:00 (thiếu chấm công vào). */
        private boolean defaultedIn;

        /** Được duyệt nghỉ phép ngày này — hiển thị khác với vắng không phép. */
        private boolean leave;

        /**
         * PHASE 6b (10/2026): Được duyệt LÀM Ở NHÀ ngày này.
         * UI tô màu giống {@link #leave} (xanh lá nhạt) kèm badge "WFH".
         */
        private boolean wfh;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EmployeeAttendanceRow {
        private Long userId;
        private String fullName;
        private String roleLabel;
        private String department;
        /** Thứ tự xếp — khớp với order trong SalaryExportService. */
        private int sortOrder;
        private List<DayCell> days;
        /** Tổng phút ĐI TRỄ (chỉ cộng các inDelta > 0). */
        private int totalLateMinutes;
        /** Tổng phút VỀ SỚM (chỉ cộng các outDelta > 0). */
        private int totalEarlyLeaveMinutes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AttendanceDetailResponse {
        private int month;
        private int year;
        private int daysInMonth;
        /** Mảng cờ dài daysInMonth: true nếu ngày đó là chủ nhật. */
        private List<Boolean> sundays;
        /** Mảng cờ dài daysInMonth: true nếu ngày đó là ngày lễ. */
        private List<Boolean> holidays;
        /** Danh sách nhân viên theo thứ tự sort của file lương tổng hợp. */
        private List<EmployeeAttendanceRow> employees;
    }
}