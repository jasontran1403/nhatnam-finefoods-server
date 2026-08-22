package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * KHO VĂN PHÒNG PHẨM / ĐỒ DÙNG.
 *
 * <p>Seed 2 bản ghi: "Phổ Quang" và "Quận 9" (xem {@code SupplyWarehouseSeeder}).
 * Tồn kho, lịch sử nhập, lịch sử rút đều TÁCH RIÊNG theo kho — cùng một mặt hàng
 * ở 2 kho là 2 dòng tồn độc lập, không dùng chung số liệu.
 */
@Entity
@Table(name = "supply_warehouse")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplyWarehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
