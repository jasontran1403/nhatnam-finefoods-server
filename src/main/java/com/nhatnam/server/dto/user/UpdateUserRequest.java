package com.nhatnam.server.dto.user;

import com.nhatnam.server.enumtype.Role;
import jakarta.validation.constraints.Email;
import lombok.Data;

import java.util.Set;

@Data
public class UpdateUserRequest {
    private String fullName;

    @Email(message = "Email không hợp lệ")
    private String email;

    private String phoneNumber;

    /** Ngày tháng năm sinh (epoch millis). null = giữ nguyên giá trị cũ. */
    private Long dateOfBirth;

    /** Role chính (legacy). Nếu roles không rỗng thì roles được dùng. */
    private Role role;

    /** Danh sách roles mới — thay toàn bộ roles hiện tại của user. */
    private Set<Role> roles;

    /** warehouseId — bắt buộc khi có role WAREHOUSE hoặc SUPER_WAREHOUSE */
    private Long warehouseId;

    /** Danh sách nhiều kho (đa kho) */
    private java.util.List<Long> warehouseIds;
}