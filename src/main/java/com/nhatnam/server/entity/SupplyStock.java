package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * TỒN KHO VPP theo cặp (kho, vật dụng).
 *
 * <p>UNIQUE (warehouse_id, supply_item_id) — đây chính là ràng buộc thực thi quy tắc
 * "gộp theo (tên, quy cách, ĐVT), không phụ thuộc NCC": vì SupplyItem đã unique theo
 * bộ ba đó, nên mỗi kho chỉ có tối đa 1 dòng tồn cho mỗi vật dụng.
 */
@Entity
@Table(
        name = "supply_stock",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_supply_stock_wh_item",
                columnNames = {"warehouse_id", "supply_item_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplyStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "supply_item_id", nullable = false)
    private Long supplyItemId;

    /** Số lượng tồn — cho phép 3 số thập phân (SL có thể lẻ). */
    @Column(nullable = false, precision = 18, scale = 3)
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist @PreUpdate void touch() { updatedAt = System.currentTimeMillis(); }
}
