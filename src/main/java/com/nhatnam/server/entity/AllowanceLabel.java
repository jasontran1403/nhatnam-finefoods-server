package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Danh mục NHÃN PHỤ CẤP (vd: Phụ cấp cơm trưa, Phụ cấp xăng xe…).
 * SUPER_ACCOUNTANT có thể tạo nhãn mới; nhãn được lưu lại để lần sau chọn lại
 * mà không phải gõ tay. Số tiền không lưu ở đây — nhập theo từng nhân viên.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "allowance_label")
public class AllowanceLabel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên nhãn phụ cấp — duy nhất. */
    @Column(nullable = false, unique = true)
    private String name;

    /** Nhãn mặc định do hệ thống seed (không cho xoá). ("system" là từ khoá MySQL nên map cột is_system). */
    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private boolean system = false;

    private Long createdAt;
}