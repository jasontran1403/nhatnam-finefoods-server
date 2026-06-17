package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "customer_category")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên phân loại, duy nhất, ví dụ: "Nhà hàng", "Quán cà phê", "Siêu thị" */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    /**
     * Màu hiển thị dạng hex, ví dụ "#C9A84C".
     * Nếu null → UI dùng màu mặc định.
     */
    @Column(name = "color", length = 20)
    private String color;

    /** Thứ tự hiển thị (tăng dần). Nhỏ hơn = lên trên. */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
    }
}