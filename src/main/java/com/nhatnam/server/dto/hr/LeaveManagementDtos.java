package com.nhatnam.server.dto.hr;

import lombok.*;

import java.util.List;

/**
 * DTO cho trang "Quản lý phép" (Owner/Admin).
 *
 * <p>Cùng data với {@code LeaveReportExportService.export()} nhưng ở dạng JSON để
 * UI dựng bảng thay vì chỉ tải file Excel.
 *
 * <p>Đơn vị "days-minutes":
 * <ul>
 *   <li>Kênh chân lý là <b>số phút</b> (1 ngày = 480 phút).</li>
 *   <li>{@code minutes} là kênh chính; {@code days} và {@code label} là biểu diễn.</li>
 *   <li>{@code label} format: "n days" | "n days m mins" | "m mins" |
 *       "-…" (âm khi vượt quỹ).</li>
 * </ul>
 */
public class LeaveManagementDtos {

    /** Payload cấp cao trả về cho UI. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LeaveManagementResponse {
        /** Năm đang xem — luôn là năm hiện tại. */
        private int year;
        /** Năm trước năm hiện tại — cho label cột "phép tồn năm trước". */
        private int priorYear;
        /** Nhóm nhân viên theo phòng ban (đã sort theo role rank). */
        private List<DepartmentGroup> departments;
    }

    /** Một nhóm phòng ban trong bảng — mỗi nhóm là 1 khối trong UI. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DepartmentGroup {
        /** Key nội bộ (MANAGEMENT / ACCOUNTING / FACTORY / SALES / WAREHOUSE_AND_DRIVER). */
        private String key;
        /** Nhãn hiển thị tiếng Việt. */
        private String label;
        private List<EmployeeRow> employees;
    }

    /** 1 dòng nhân viên trong bảng. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EmployeeRow {
        private Long userId;
        private String fullName;
        private String position;
        /** ms — có thể null nếu chưa khai. */
        private Long workStartDate;

        /**
         * Usage của TỪNG THÁNG (index 0 = tháng 1, index 11 = tháng 12).
         *
         * <p>Đã BAO GỒM cả ngày phép được duyệt và số phút bù trễ/về sớm.
         * Chú ý: khi tính lại lương (mở lại), {@code leaveMinutesUsed} bị reset →
         * tháng đó sẽ hiện lại số MỚI, đúng spec "không hiển thị tháng đang tính
         * lại" chính là hệ quả của reset này (số cũ biến mất, số mới chưa có).
         */
        private List<MonthCell> months;

        /** Cột N — phép tồn năm trước (đơn vị ngày, luôn bội số của 0.5). */
        private double priorYearBalanceDays;
        /** Cột O — phép cơ bản cộng dồn của năm hiện tại (đơn vị ngày, nguyên). */
        private double currentYearEntitledDays;
        /** Cột P — tổng đã sử dụng trong năm (kết hợp ngày + phút). */
        private MonthCell totalUsed;
        /** Cột Q — tổng phép được cộng (thâm niên + OT/hỗ trợ khác). */
        private double totalPlusDays;
        /** Cột R — phép còn lại. */
        private MonthCell remaining;
    }

    /**
     * Ô hiển thị dạng "days-minutes". Dùng cho từng tháng, cột Đã sử dụng và cột Còn lại.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MonthCell {
        /** Tổng số phút — chân lý duy nhất. */
        private long minutes;
        /**
         * Phần "ngày" — luôn theo bội số 0.5. Ví dụ 1901 phút → 3.5 (dư 221 phút).
         * Được biểu diễn dạng số cho FE render lại (không phụ thuộc parse label).
         */
        private double days;
        /** Phần dư sau khi lấy các nửa-ngày tròn, đơn vị PHÚT (0..239). */
        private int extraMinutes;
        /** Chuỗi "X ngày Y phút" đã format sẵn — dùng khi FE chỉ cần hiển thị. */
        private String label;
    }
}
