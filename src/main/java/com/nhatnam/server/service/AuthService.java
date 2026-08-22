package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.AuthLoginRequest;
import com.nhatnam.server.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse login(AuthLoginRequest request) throws Exception;
    AuthResponse switchRole(String username, String newRole) throws Exception;

    /**
     * NẠP LẠI phiên hiện tại từ DB — để đổi/gán role có hiệu lực sau khi F5,
     * không phải đăng xuất rồi đăng nhập lại.
     *
     * @param currentSelectedRole role đang chọn, đọc từ JWT hiện tại (có thể null)
     */
    AuthResponse refreshSession(String username, String currentSelectedRole) throws Exception;
    void setDefaultRole(String username, String newDefaultRole) throws Exception;
}