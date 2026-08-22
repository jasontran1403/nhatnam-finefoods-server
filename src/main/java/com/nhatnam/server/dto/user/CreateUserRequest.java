package com.nhatnam.server.dto.user;

import com.nhatnam.server.enumtype.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Set;

@Data
public class CreateUserRequest {

    @NotBlank(message = "Username bắt buộc")
    @Size(min = 3, max = 50)
    private String username;

    @NotBlank(message = "Password bắt buộc")
    @Size(min = 6, max = 100)
    private String password;

    @NotBlank(message = "Tên hiển thị bắt buộc")
    private String fullName;

    @Email(message = "Email không hợp lệ")
    private String email;

    private String phoneNumber;

    /** Ngày tháng năm sinh (epoch millis, 00:00 giờ VN). Tuỳ chọn. */
    private Long dateOfBirth;

    /**
     * Role chính (legacy — vẫn giữ để tương thích).
     * Nếu roles không null/rỗng thì roles sẽ được dùng thay thế.
     */
    private Role role;

    /**
     * Danh sách roles (multi-role feature).
     * Nếu set thì role chính sẽ được lấy từ roles.iterator().next().
     */
    private Set<Role> roles;

    /** warehouseId — bắt buộc khi có role WAREHOUSE hoặc SUPER_WAREHOUSE */
    private Long warehouseId;

    /** Danh sách nhiều kho (đa kho) — nếu có sẽ ưu tiên hơn warehouseId đơn */
    private java.util.List<Long> warehouseIds;
}