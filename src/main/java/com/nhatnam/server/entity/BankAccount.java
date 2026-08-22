package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Tài khoản ngân hàng của công ty — dùng để breakdown số dư chuyển khoản trong
 * trang Quản lý dòng tiền. Nhận diện theo TÊN (name). Seed sẵn Vietcombank,
 * VietinBank; OWNER/ADMIN có thể thêm ngân hàng mới.
 */
@Entity
@Table(name = "bank_account")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên ngân hàng — khớp với bankName của phiếu thu/chi. */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Số tài khoản (tuỳ chọn, để hiển thị). */
    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
