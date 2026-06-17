package com.nhatnam.server.service.admin;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.user.CreateUserRequest;
import com.nhatnam.server.dto.user.UpdateUserRequest;
import com.nhatnam.server.dto.user.UserDto;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WarehouseRepository warehouseRepository;

    @Transactional(readOnly = true)
    public PageResponse<UserDto> list(String q, Role role, Boolean locked, Pageable pageable) {
        Page<User> page = userRepository.search(q, role, locked, pageable);
        List<UserDto> content = page.getContent().stream().map(this::toDto).toList();
        return PageResponse.from(page, content);
    }

    @Transactional(readOnly = true)
    public UserDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Transactional
    public UserDto create(CreateUserRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BusinessException("Username đã tồn tại");
        }
        if (req.getEmail() != null && !req.getEmail().isBlank()
                && userRepository.existsByEmail(req.getEmail())) {
            throw new BusinessException("Email đã tồn tại");
        }

        Warehouse warehouse = resolveWarehouse(req.getWarehouseId());

        // Xác định roles: ưu tiên req.getRoles(), fallback về req.getRole()
        Set<Role> allRoles = resolveRoles(req.getRoles(), req.getRole());
        if (allRoles.isEmpty()) throw new BusinessException("Phải có ít nhất 1 role");

        // Role chính = role đầu tiên (hoặc role đơn nếu chỉ có 1)
        Role primaryRole = allRoles.size() == 1
                ? allRoles.iterator().next()
                : (req.getRole() != null ? req.getRole() : allRoles.iterator().next());

        // Giải quyết nhiều kho
        Set<Warehouse> userWarehouses = resolveWarehouses(req.getWarehouseIds());
        // Nếu chỉ có warehouseId đơn và không có warehouseIds → dùng warehouse đơn làm default
        if (userWarehouses.isEmpty() && warehouse != null) {
            userWarehouses = new HashSet<>(Set.of(warehouse));
        }

        User u = User.builder()
                .username(req.getUsername())
                .password(passwordEncoder.encode(req.getPassword()))
                .fullName(req.getFullName())
                .email(req.getEmail())
                .phoneNumber(req.getPhoneNumber())
                .role(primaryRole)
                .roles(allRoles)
                .warehouse(warehouse)
                .userWarehouses(userWarehouses)
                .timeCreate(System.currentTimeMillis())
                .isLockAccount(false)
                .mfaEnabled(false)
                .build();
        return toDto(userRepository.save(u));
    }

    @Transactional
    public UserDto update(Long id, UpdateUserRequest req) {
        User u = findOrThrow(id);

        if (req.getFullName()    != null) u.setFullName(req.getFullName());
        if (req.getEmail()       != null) u.setEmail(req.getEmail());
        if (req.getPhoneNumber() != null) u.setPhoneNumber(req.getPhoneNumber());

        // Cập nhật roles nếu được gửi lên
        Set<Role> allRoles = resolveRoles(req.getRoles(), req.getRole());
        if (!allRoles.isEmpty()) {
            Role primaryRole = allRoles.size() == 1
                    ? allRoles.iterator().next()
                    : (req.getRole() != null ? req.getRole() : allRoles.iterator().next());
            u.setRole(primaryRole);
            u.setRoles(allRoles);
        }

        boolean hasWarehouseRole = u.getAllRoles().stream()
                .anyMatch(r -> r == Role.WAREHOUSE || r == Role.SUPER_WAREHOUSE);

        // Nếu gửi danh sách warehouseIds → cập nhật đa kho
        if (req.getWarehouseIds() != null && !req.getWarehouseIds().isEmpty()) {
            Set<Warehouse> whs = resolveWarehouses(req.getWarehouseIds());
            u.setUserWarehouses(whs);
            // Đặt warehouse đơn (legacy) là kho đầu tiên trong list
            u.setWarehouse(whs.iterator().next());
        } else if (req.getWarehouseId() != null) {
            // Single warehouse
            Warehouse wh = warehouseRepository.findById(req.getWarehouseId())
                    .orElseThrow(() -> new BusinessException("Kho không tồn tại: " + req.getWarehouseId()));
            u.setWarehouse(wh);
            u.setUserWarehouses(new HashSet<>(Set.of(wh)));
        } else if (!hasWarehouseRole) {
            u.setWarehouse(null);
            u.setUserWarehouses(new HashSet<>());
        }

        return toDto(userRepository.save(u));
    }

    @Transactional
    public UserDto setLocked(Long id, boolean locked) {
        User u = findOrThrow(id);
        u.setLockAccount(locked);
        return toDto(userRepository.save(u));
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException("Mật khẩu phải tối thiểu 6 ký tự");
        }
        User u = findOrThrow(id);
        u.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(u);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User findOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại: " + id));
    }

    private Warehouse resolveWarehouse(Long warehouseId) {
        if (warehouseId == null) return null;
        return warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException("Kho không tồn tại: " + warehouseId));
    }

    private Set<Warehouse> resolveWarehouses(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new HashSet<>();
        return ids.stream()
                .map(id -> warehouseRepository.findById(id)
                        .orElseThrow(() -> new BusinessException("Kho không tồn tại: " + id)))
                .collect(Collectors.toSet());
    }

    /**
     * Gộp roles từ Set và single role.
     */
    private Set<Role> resolveRoles(Set<Role> rolesSet, Role singleRole) {
        Set<Role> result = new HashSet<>();
        if (rolesSet != null && !rolesSet.isEmpty()) {
            result.addAll(rolesSet);
        } else if (singleRole != null) {
            result.add(singleRole);
        }
        return result;
    }

    private UserDto toDto(User u) {
        Set<Role> allRoles = u.getAllRoles();
        String primaryRole = u.getRole() != null
                ? u.getRole().name()
                : (allRoles.isEmpty() ? null : allRoles.iterator().next().name());

        Set<String> roleNames = allRoles.stream()
                .map(Role::name)
                .collect(Collectors.toSet());

        // Tất cả kho được phân công
        java.util.List<UserDto.WarehouseInfo> warehouseInfos = u.getAllWarehouses().stream()
                .map(w -> new UserDto.WarehouseInfo(w.getId(), w.getName()))
                .collect(Collectors.toList());

        // warehouse đơn legacy (ưu tiên warehouseId của getAllWarehouses first nếu không có warehouse đơn)
        Long whId = u.getWarehouse() != null ? u.getWarehouse().getId()
                    : (!warehouseInfos.isEmpty() ? warehouseInfos.get(0).getId() : null);
        String whName = u.getWarehouse() != null ? u.getWarehouse().getName()
                    : (!warehouseInfos.isEmpty() ? warehouseInfos.get(0).getName() : null);

        return UserDto.builder()
                .id(u.getId())
                .username(u.getUsername())
                .fullName(u.getFullName())
                .email(u.getEmail())
                .phoneNumber(u.getPhoneNumber())
                .role(primaryRole)
                .roles(roleNames)
                .isLockAccount(u.isLockAccount())
                .mfaEnabled(u.isMfaEnabled())
                .timeCreate(u.getTimeCreate())
                .warehouseId(whId)
                .warehouseName(whName)
                .warehouses(warehouseInfos.isEmpty() ? null : warehouseInfos)
                .department(u.getDepartment())
                .position(u.getPosition())
                .build();
    }
}