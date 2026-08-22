package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.config.JwtService;
import com.nhatnam.server.dto.request.AuthLoginRequest;
import com.nhatnam.server.dto.response.AuthResponse;
import com.nhatnam.server.entity.Token;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.enumtype.TokenType;
import com.nhatnam.server.repository.TokenRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.AuthService;
import dev.samstevens.totp.secret.SecretGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final SecretGenerator secretGenerator;

    @Override
    @Transactional
    public AuthResponse login(AuthLoginRequest request) {
        try {
            // 1. Authenticate credentials
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(),
                            request.getPassword()
                    )
            );

            // 2. Find user
            User user = userRepository.findByUsername(request.getUsername())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            // 3. Collect all roles
            Set<Role> allRoles = user.getAllRoles();

            // 4. Nếu user chưa chọn role và có nhiều role
            if (request.getSelectedRole() == null && allRoles.size() > 1) {
                // Nếu user.role (default) được set và hợp lệ → tự đăng nhập với role đó
                if (user.getRole() != null && allRoles.contains(user.getRole())) {
                    request.setSelectedRole(user.getRole().name());
                    // fall through to step 5
                } else {
                    // Chưa có default → yêu cầu chọn
                    List<String> roleNames = allRoles.stream()
                            .map(Role::name)
                            .sorted()
                            .collect(Collectors.toList());
                    return AuthResponse.builder()
                            .userId(user.getId())
                            .fullName(user.getFullName())
                            .isLock(user.isLockAccount())
                            .requireRoleSelection(true)
                            .availableRoles(roleNames)
                            .build();
                }
            }

            // 5. Xác định role dùng để tạo JWT
            Role activeRole;
            if (request.getSelectedRole() != null) {
                // Validate: selectedRole phải nằm trong danh sách roles của user
                try {
                    activeRole = Role.valueOf(request.getSelectedRole());
                    if (!allRoles.contains(activeRole)) {
                        throw new RuntimeException("Role không hợp lệ cho tài khoản này");
                    }
                } catch (IllegalArgumentException e) {
                    throw new RuntimeException("Role không tồn tại: " + request.getSelectedRole());
                }
            } else {
                // Chỉ có 1 role (hoặc role đơn legacy)
                activeRole = allRoles.iterator().next();
            }

            // 6. Tạo JWT với selected_role được nhúng vào claim
            String jwtToken = jwtService.generateTokenWithRole(user, activeRole);

            // 7. Lưu token
            Token token = Token.builder()
                    .user(user)
                    .token(jwtToken)
                    .tokenType(TokenType.BEARER)
                    .expired(false)
                    .revoked(false)
                    .build();
            tokenRepository.save(token);

            // 8. Build response
            List<AuthResponse.WarehouseInfo> warehouseInfos = user.getAllWarehouses().stream()
                    .map(w -> new AuthResponse.WarehouseInfo(w.getId(), w.getName()))
                    .collect(Collectors.toList());

            return AuthResponse.builder()
                    .userId(user.getId())
                    .fullName(user.getFullName())
                    .username(user.getUsername())
                    .isLock(user.isLockAccount())
                    .role(activeRole.name())
                    .accessToken(jwtToken)
                    .requireRoleSelection(false)
                    .availableRoles(allRoles.stream().map(Role::name).sorted().collect(Collectors.toList()))
                    .warehouseId(user.getWarehouse() != null ? user.getWarehouse().getId() : null)
                    .warehouseName(user.getWarehouse() != null ? user.getWarehouse().getName() : null)
                    .warehouses(warehouseInfos.isEmpty() ? null : warehouseInfos)
                    .build();

        } catch (BadCredentialsException | UsernameNotFoundException e) {
            throw new RuntimeException("Tài khoản không hợp lệ, xin vui lòng kiểm tra lại!");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public AuthResponse switchRole(String username, String newRole) throws Exception {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User không tồn tại"));

        Set<Role> allRoles = user.getAllRoles();
        Role activeRole;
        try {
            activeRole = Role.valueOf(newRole);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Role không tồn tại: " + newRole);
        }
        if (!allRoles.contains(activeRole)) {
            throw new RuntimeException("Role không hợp lệ cho tài khoản này");
        }

        String jwtToken = jwtService.generateTokenWithRole(user, activeRole);
        Token token = Token.builder()
                .user(user).token(jwtToken)
                .tokenType(TokenType.BEARER).expired(false).revoked(false).build();
        tokenRepository.save(token);

        List<AuthResponse.WarehouseInfo> warehouseInfos = user.getAllWarehouses().stream()
                .map(w -> new AuthResponse.WarehouseInfo(w.getId(), w.getName()))
                .collect(Collectors.toList());
        List<String> roleNames = allRoles.stream()
                .map(Role::name).sorted().collect(Collectors.toList());

        return AuthResponse.builder()
                .userId(user.getId()).fullName(user.getFullName())
                .isLock(user.isLockAccount())
                .role(activeRole.name()).accessToken(jwtToken)
                .requireRoleSelection(false).availableRoles(roleNames)
                .warehouseId(user.getWarehouse() != null ? user.getWarehouse().getId() : null)
                .warehouseName(user.getWarehouse() != null ? user.getWarehouse().getName() : null)
                .warehouses(warehouseInfos.isEmpty() ? null : warehouseInfos)
                .build();
    }

    /**
     * NẠP LẠI PHIÊN — đọc lại roles/kho từ DB cho tài khoản đang đăng nhập.
     *
     * <h3>Vấn đề</h3>
     * OWNER gán thêm role hoặc đổi role cho nhân viên, nhưng nhân viên phải ĐĂNG
     * XUẤT rồi đăng nhập lại mới thấy. Nguyên nhân KHÔNG nằm ở backend:
     * {@code JwtAuthenticationFilter} gọi {@code loadUserByUsername} mỗi request
     * nên quyền phía server đã tươi sẵn. Thứ bị cũ là bản sao user mà frontend
     * cache trong localStorage lúc đăng nhập — menu và route guard đọc từ đó.
     *
     * <h3>Cách xử lý role đang chọn</h3>
     * <ul>
     *   <li><b>Vẫn còn hợp lệ</b> → GIỮ NGUYÊN, và giữ luôn token cũ. Được gán
     *       thêm role không có nghĩa là muốn nhảy sang role đó ngay; user tự bấm
     *       "Chuyển vai trò" khi cần. Chỉ danh sách {@code availableRoles} là mới.</li>
     *   <li><b>Đã bị thu hồi</b> → chọn lại role khác (ưu tiên role mặc định trong
     *       hồ sơ, không có thì lấy role đầu) và CẤP TOKEN MỚI. Không cấp token
     *       mới thì filter sẽ rơi về nhánh "gộp toàn bộ quyền trong DB" — rộng hơn
     *       ý định — trong khi frontend vẫn tưởng đang ở role đã mất.</li>
     * </ul>
     *
     * <p>Tài khoản bị KHOÁ thì ném lỗi để frontend đá ra ngoài ngay lần F5 kế tiếp.
     */
    @Override
    public AuthResponse refreshSession(String username, String currentSelectedRole) throws Exception {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User không tồn tại"));

        // BusinessException = "phiên này KHÔNG dùng được nữa" → controller trả
        // UNAUTHORIZED và frontend đá ra đăng nhập lại. Lỗi khác (DB trục trặc…)
        // KHÔNG được rơi vào nhánh này, xem catch ở controller.
        if (user.isLockAccount()) {
            throw new com.nhatnam.server.common.BusinessException("Tài khoản đã bị khoá");
        }

        Set<Role> allRoles = user.getAllRoles();
        if (allRoles.isEmpty()) {
            throw new com.nhatnam.server.common.BusinessException("Tài khoản chưa được gán vai trò nào");
        }

        // Role đang chọn còn hợp lệ không?
        Role activeRole = null;
        if (currentSelectedRole != null && !currentSelectedRole.isBlank()) {
            try {
                Role parsed = Role.valueOf(currentSelectedRole);
                if (allRoles.contains(parsed)) activeRole = parsed;
            } catch (IllegalArgumentException ignored) {}
        }

        boolean needNewToken = false;
        if (activeRole == null) {
            // Role cũ đã bị thu hồi → rơi về role mặc định, hoặc role đầu tiên.
            activeRole = (user.getRole() != null && allRoles.contains(user.getRole()))
                    ? user.getRole()
                    : allRoles.iterator().next();
            needNewToken = true;
        }

        String accessToken = null;
        if (needNewToken) {
            accessToken = jwtService.generateTokenWithRole(user, activeRole);
            tokenRepository.save(Token.builder()
                    .user(user).token(accessToken)
                    .tokenType(TokenType.BEARER).expired(false).revoked(false).build());
        }

        List<AuthResponse.WarehouseInfo> warehouseInfos = user.getAllWarehouses().stream()
                .map(w -> new AuthResponse.WarehouseInfo(w.getId(), w.getName()))
                .collect(Collectors.toList());
        List<String> roleNames = allRoles.stream()
                .map(Role::name).sorted().collect(Collectors.toList());

        return AuthResponse.builder()
                .userId(user.getId()).username(user.getUsername())
                .fullName(user.getFullName())
                .isLock(user.isLockAccount())
                .role(activeRole.name())
                // null = frontend GIỮ token đang dùng (không cần cấp mới)
                .accessToken(accessToken)
                .requireRoleSelection(false).availableRoles(roleNames)
                .warehouseId(user.getWarehouse() != null ? user.getWarehouse().getId() : null)
                .warehouseName(user.getWarehouse() != null ? user.getWarehouse().getName() : null)
                .warehouses(warehouseInfos.isEmpty() ? null : warehouseInfos)
                .build();
    }

    @Override
    public void setDefaultRole(String username, String newDefaultRole) throws Exception {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User không tồn tại"));

        if (newDefaultRole == null || newDefaultRole.isBlank()) {
            // Bỏ default: set role = null (nếu user có nhiều role thì sẽ hỏi khi login)
            user.setRole(null);
        } else {
            Role role;
            try { role = Role.valueOf(newDefaultRole); }
            catch (IllegalArgumentException e) { throw new RuntimeException("Role không hợp lệ: " + newDefaultRole); }
            if (!user.getAllRoles().contains(role))
                throw new RuntimeException("Role này không thuộc tài khoản của bạn");
            user.setRole(role);
        }
        userRepository.save(user);
    }
}