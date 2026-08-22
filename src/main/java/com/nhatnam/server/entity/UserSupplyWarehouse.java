package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Bảng nối (user, kho VPP) — Owner gán. Dùng cho 3 chỗ:
 * <ol>
 *   <li>Dropdown "kho nhận" khi TẠO phiếu → chỉ list kho được gán
 *       (nếu chỉ có 1 kho thì FE auto-select).</li>
 *   <li>Page "Rút sử dụng" → chỉ thao tác được trên kho được gán.</li>
 *   <li>Owner thấy cả 2 kho, read-only (không cần bản ghi ở đây).</li>
 * </ol>
 */
@Entity
@Table(
        name = "user_supply_warehouse",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_supply_wh",
                columnNames = {"user_id", "warehouse_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserSupplyWarehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "assigned_at")
    private Long assignedAt;

    @Column(name = "assigned_by_name", length = 200)
    private String assignedByName;

    @PrePersist void onCreate() { if (assignedAt == null) assignedAt = System.currentTimeMillis(); }
}
