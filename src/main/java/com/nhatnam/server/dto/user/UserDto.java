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

    /** Bộ phận / phòng ban */
    private String department;

    /** Chức vụ */
    private String position;

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
