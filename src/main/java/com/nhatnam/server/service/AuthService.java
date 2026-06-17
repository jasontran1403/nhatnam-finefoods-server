package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.AuthLoginRequest;
import com.nhatnam.server.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse login(AuthLoginRequest request) throws Exception;
    AuthResponse switchRole(String username, String newRole) throws Exception;
    void setDefaultRole(String username, String newDefaultRole) throws Exception;
}