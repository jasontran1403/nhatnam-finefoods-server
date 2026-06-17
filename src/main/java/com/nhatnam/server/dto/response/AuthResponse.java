package com.nhatnam.server.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {
    private Long userId;
    private String username;
    private String fullName;
    private boolean isLock;
    private String accessToken;
    private String role;

    /** Tất cả roles của user — để frontend hiện switch role */
    private List<String> availableRoles;

    /** true = cần user chọn role (frontend hiện popup) */
    private boolean requireRoleSelection;

    private Long warehouseId;
    private String warehouseName;

    /** Tất cả kho được phân công (đa kho) */
    private java.util.List<WarehouseInfo> warehouses;

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class WarehouseInfo {
        private Long id;
        private String name;
    }
}
