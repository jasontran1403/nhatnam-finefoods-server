package com.nhatnam.server.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    private Long id;
    private String username;
    private String fullName;
    private String email;
    private String phoneNumber;

    /** Role chính hiện tại (legacy field, giữ để tương thích UI cũ) */
    private String role;

    /** Tất cả roles của user — dùng cho UI multi-role */
    private Set<String> roles;

    private boolean isLockAccount;
    private boolean mfaEnabled;
    private Long timeCreate;

    // ── Xoá mềm ──────────────────────────────────────────────
    /** true = nhân viên đã bị xoá (soft delete) */
    private boolean deleted;
    private Long deletedAt;
    private String deletedBy;

    /** Bộ phận */
    private String department;

    /** Phòng ban */
    private String division;

    /** Chức vụ */
    private String position;

    /**
     * Ngày vào làm việc (epoch millis) — căn cứ tính thâm niên.
     *
     * <p>Bảng "Quản lý nhân sự" đọc danh sách nhân viên từ {@code GET /api/admin/users}
     * (DTO này), KHÔNG phải {@code GET /api/hr/employees}. Thiếu field ở đây thì
     * ngày vừa lưu xong không hiện lại được, dù DB đã ghi đúng.
     */
    private Long workStartDate;

    /**
     * NGÀY SINH (epoch millis) — dùng để tổ chức sinh nhật.
     * null = chưa khai báo.
     */
    private Long dateOfBirth;

    /**
     * Số ngày tới sinh nhật kế tiếp; {@code 0} = sinh nhật hôm nay.
     * {@code null} khi chưa khai báo ngày sinh (luôn xếp cuối khi sort).
     */
    private Integer daysUntilBirthday;

    /**
     * Sinh nhật rơi vào THÁNG NÀY và CHƯA QUA — cờ để FE tô màu hàng.
     * Tính ở server theo giờ VN để mọi máy trạm nhìn thấy giống nhau.
     */
    private Boolean birthdayThisMonth;

    // Thông tin kho gắn liền (cho WAREHOUSE / SUPER_WAREHOUSE)
    private Long warehouseId;
    private String warehouseName;

    /** Danh sách tất cả kho được phân công (đa kho) */
    private java.util.List<WarehouseInfo> warehouses;

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class WarehouseInfo {
        private Long id;
        private String name;
    }
}