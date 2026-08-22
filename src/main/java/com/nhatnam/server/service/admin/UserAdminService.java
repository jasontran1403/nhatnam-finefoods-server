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
    private final com.nhatnam.server.repository.TokenRepository tokenRepository;
    private final com.nhatnam.server.service.DriverUserSyncService driverUserSyncService;

    @Transactional(readOnly = true)
    public PageResponse<UserDto> list(String q, Role role, Boolean locked, Pageable pageable) {
        return list(q, role, locked, false, pageable);
    }

    /** @param includeDeleted true = hiện cả nhân viên đã xoá mềm */
    @Transactional(readOnly = true)
    public PageResponse<UserDto> list(String q, Role role, Boolean locked,
                                      boolean includeDeleted, Pageable pageable) {
        return list(q, role, locked, includeDeleted, false, pageable);
    }

    /**
     * @param birthdaySort true = sắp xếp theo SINH NHẬT GẦN ĐẾN NHẤT (0 ngày = hôm nay lên đầu),
     *                     người chưa khai báo ngày sinh xếp cuối.
     *
     * <p>Phải nạp TOÀN BỘ tập lọc rồi mới cắt trang: thứ tự này phụ thuộc ngày hiện tại
     * và có xử lý riêng cho 29/02, không diễn đạt được bằng {@code ORDER BY} của SQL.
     * Cùng cách làm với sort theo công nợ ở màn hình khách hàng.
     */
    @Transactional(readOnly = true)
    public PageResponse<UserDto> list(String q, Role role, Boolean locked,
                                      boolean includeDeleted, boolean birthdaySort,
                                      Pageable pageable) {
        if (!birthdaySort) {
            Page<User> page = userRepository.search(q, role, locked, includeDeleted, pageable);
            List<UserDto> content = page.getContent().stream().map(this::toDto).toList();
            return PageResponse.from(page, content);
        }

        Pageable all = org.springframework.data.domain.PageRequest.of(
                0, Integer.MAX_VALUE,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "id"));

        List<UserDto> sorted = new ArrayList<>(
                userRepository.search(q, role, locked, includeDeleted, all)
                        .getContent().stream().map(this::toDto).toList());

        sorted.sort(Comparator.comparingInt(
                (UserDto d) -> d.getDaysUntilBirthday() != null
                        ? d.getDaysUntilBirthday()
                        : Integer.MAX_VALUE)          // chưa khai báo → xuống cuối
                .thenComparing(d -> d.getFullName() != null ? d.getFullName() : ""));

        int total  = sorted.size();
        int pageNo = pageable.getPageNumber();
        int size   = pageable.getPageSize() > 0 ? pageable.getPageSize() : 20;
        int start  = pageNo * size;
        int end    = Math.min(start + size, total);
        List<UserDto> slice = start >= total ? List.of() : sorted.subList(start, end);
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;

        return PageResponse.<UserDto>builder()
                .content(slice)
                .page(pageNo)
                .size(size)
                .totalElements(total)
                .totalPages(totalPages)
                .first(pageNo == 0)
                .last(pageNo >= totalPages - 1)
                .build();
    }

    /** Danh sách nhân viên ĐÃ XOÁ (để tra cứu / khôi phục) */
    @Transactional(readOnly = true)
    public PageResponse<UserDto> listDeleted(Pageable pageable) {
        Page<User> page = userRepository.findDeleted(pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toDto).toList());
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
        if (req.getPhoneNumber() != null && !req.getPhoneNumber().isBlank()
                && userRepository.existsByPhoneNumber(req.getPhoneNumber())) {
            throw new BusinessException("Số điện thoại đã tồn tại");
        }
        // Cấm tự nhập tiền tố hệ thống dùng cho xoá mềm
        if (req.getUsername() != null && req.getUsername().startsWith(User.SOFT_DELETED_PREFIX)) {
            throw new BusinessException("Username không được bắt đầu bằng " + User.SOFT_DELETED_PREFIX);
        }
        // LƯU Ý: nhân viên đã xoá mềm KHÔNG chặn ở đây — username/email/SĐT của họ
        // đã được đổi thành "SOFT_DELETED_{id}_{...}" nên giá trị cũ đã được giải phóng.

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
                .dateOfBirth(req.getDateOfBirth())
                .role(primaryRole)
                .roles(allRoles)
                .warehouse(warehouse)
                .userWarehouses(userWarehouses)
                .timeCreate(System.currentTimeMillis())
                .isLockAccount(false)
                .mfaEnabled(false)
                .build();
        User saved = userRepository.save(u);

        // Có role Tài xế → tạo luôn bản ghi driver tương ứng để hai bảng khớp nhau
        driverUserSyncService.syncFromUser(saved);

        return toDto(saved);
    }

    @Transactional
    public UserDto update(Long id, UpdateUserRequest req) {
        User u = findOrThrow(id);

        if (req.getFullName()    != null) u.setFullName(req.getFullName());
        if (req.getEmail()       != null) u.setEmail(req.getEmail());
        if (req.getPhoneNumber() != null) u.setPhoneNumber(req.getPhoneNumber());
        if (req.getDateOfBirth() != null) u.setDateOfBirth(req.getDateOfBirth());

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

        User saved = userRepository.save(u);

        // Thêm/bỏ role Tài xế hoặc đổi họ tên → cập nhật bản ghi driver tương ứng
        driverUserSyncService.syncFromUser(saved);

        return toDto(saved);
    }

    @Transactional
    public UserDto setLocked(Long id, boolean locked) {
        User u = findOrThrow(id);
        u.setLockAccount(locked);
        return toDto(userRepository.save(u));
    }

    // ════════════════════════════════════════════════════════════════════════
    // XOÁ MỀM (SOFT DELETE)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * XOÁ MỀM một nhân viên.
     *
     * <ol>
     *   <li>{@code deleted = true}, {@code isLockAccount = true} → không đăng nhập được
     *       và bị loại khỏi mọi truy vấn thông báo (tất cả đều lọc isLockAccount = false).</li>
     *   <li>Gắn tiền tố {@code SOFT_DELETED_{id}_} vào username / email / phoneNumber
     *       → GIẢI PHÓNG các giá trị unique đó để tạo lại nhân viên mới với thông tin cũ.</li>
     *   <li>Giá trị gốc được lưu vào {@code originalUsername/Email/PhoneNumber}.</li>
     *   <li>Thu hồi toàn bộ token đang đăng nhập.</li>
     * </ol>
     *
     * <p>KHÔNG xoá cứng: user còn được tham chiếu ở đơn hàng, phiếu kho, log… → xoá cứng
     * sẽ vỡ khoá ngoại và mất lịch sử.
     */
    @Transactional
    public UserDto softDelete(Long id, String actorName) {
        User u = findOrThrow(id);

        if (u.isDeleted()) throw new BusinessException("Nhân viên này đã bị xoá trước đó");
        if (u.getAllRoles().contains(Role.SUPERADMIN))
            throw new BusinessException("Không thể xoá tài khoản SUPERADMIN");

        long now = System.currentTimeMillis();
        String tag = User.SOFT_DELETED_PREFIX + id + "_";

        // Lưu giá trị gốc trước khi đổi
        u.setOriginalUsername(u.getUsername());
        u.setOriginalEmail(u.getEmail());
        u.setOriginalPhoneNumber(u.getPhoneNumber());

        // Gắn tiền tố vào các field UNIQUE → giải phóng giá trị cũ
        u.setUsername(truncate(tag + u.getUsername(), 150));
        if (u.getEmail() != null && !u.getEmail().isBlank())
            u.setEmail(truncate(tag + u.getEmail(), 190));
        if (u.getPhoneNumber() != null && !u.getPhoneNumber().isBlank())
            u.setPhoneNumber(truncate(tag + u.getPhoneNumber(), 40));

        u.setDeleted(true);
        u.setDeletedAt(now);
        u.setDeletedBy(actorName);
        u.setLockAccount(true);          // chặn đăng nhập + loại khỏi mọi query thông báo

        // Thu hồi phiên đăng nhập hiện tại
        revokeAllTokens(u);

        User saved = userRepository.save(u);

        // Xoá mềm luôn bản ghi driver (thêm tiền tố "[Đã xóa] " + tắt active)
        driverUserSyncService.syncFromUser(saved);

        return toDto(saved);
    }

    /**
     * KHÔI PHỤC nhân viên đã xoá mềm.
     *
     * <p>Nếu username/email/SĐT gốc trong lúc đó đã bị tài khoản khác dùng mất thì
     * KHÔNG khôi phục được — phải sửa thông tin của tài khoản kia trước.
     */
    @Transactional
    public UserDto restore(Long id) {
        User u = findOrThrow(id);
        if (!u.isDeleted()) throw new BusinessException("Nhân viên này chưa bị xoá");

        String username = u.getOriginalUsername();
        String email    = u.getOriginalEmail();
        String phone    = u.getOriginalPhoneNumber();

        if (username != null && userRepository.existsByUsername(username))
            throw new BusinessException("Username \"" + username + "\" đã được tài khoản khác sử dụng");
        if (email != null && !email.isBlank() && userRepository.existsByEmail(email))
            throw new BusinessException("Email \"" + email + "\" đã được tài khoản khác sử dụng");
        if (phone != null && !phone.isBlank() && userRepository.existsByPhoneNumber(phone))
            throw new BusinessException("Số điện thoại \"" + phone + "\" đã được tài khoản khác sử dụng");

        if (username != null) u.setUsername(username);
        u.setEmail(email);
        u.setPhoneNumber(phone);

        u.setOriginalUsername(null);
        u.setOriginalEmail(null);
        u.setOriginalPhoneNumber(null);
        u.setDeleted(false);
        u.setDeletedAt(null);
        u.setDeletedBy(null);
        u.setLockAccount(false);

        User saved = userRepository.save(u);

        // Khôi phục tài khoản → mở lại bản ghi driver nếu vẫn còn role Tài xế
        driverUserSyncService.syncFromUser(saved);

        return toDto(saved);
    }

    private void revokeAllTokens(User u) {
        var tokens = tokenRepository.findAllValidTokenByUser(u.getId());
        if (tokens == null || tokens.isEmpty()) return;
        tokens.forEach(t -> { t.setExpired(true); t.setRevoked(true); });
        tokenRepository.saveAll(tokens);
    }

    private static String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) : s;
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
                .deleted(u.isDeleted())
                .deletedAt(u.getDeletedAt())
                .deletedBy(u.getDeletedBy())
                .mfaEnabled(u.isMfaEnabled())
                .timeCreate(u.getTimeCreate())
                .warehouseId(whId)
                .warehouseName(whName)
                .warehouses(warehouseInfos.isEmpty() ? null : warehouseInfos)
                .department(u.getDepartment())
                .division(u.getDivision())
                .position(u.getPosition())
                .workStartDate(u.getWorkStartDate())
                .dateOfBirth(u.getDateOfBirth())
                // Tính sẵn ở server: FE chỉ việc sort/tô màu, không phải tự suy ra
                // "sinh nhật tháng này" bằng múi giờ của trình duyệt.
                .daysUntilBirthday(
                        com.nhatnam.server.utils.AnniversaryUtil.daysUntilNext(u.getDateOfBirth()))
                .birthdayThisMonth(
                        com.nhatnam.server.utils.AnniversaryUtil.isUpcomingThisMonth(u.getDateOfBirth()))
                .build();
    }
}