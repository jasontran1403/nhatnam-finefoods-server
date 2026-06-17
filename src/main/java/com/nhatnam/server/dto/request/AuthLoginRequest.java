package com.nhatnam.server.dto.request;

import lombok.Data;

@Data
public class AuthLoginRequest {
    private String username;
    private String password;
    /**
     * Role được chọn sau khi user thấy popup multi-role.
     * null = lần đăng nhập đầu (chưa chọn), hệ thống trả danh sách roles.
     * non-null = lần đăng nhập xác nhận, hệ thống tạo JWT với role này.
     */
    private String selectedRole;
}
