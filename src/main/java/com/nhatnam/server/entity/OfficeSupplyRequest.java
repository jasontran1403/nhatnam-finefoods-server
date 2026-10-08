package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * PHIẾU ĐĂNG KÝ VPP của nhân viên.
 *
 * <p>Mỗi nhân viên tạo tối đa 1 phiếu đang PENDING cho mỗi văn phòng.
 * Sau khi OWNER bấm "Đặt hàng", phiếu được chuyển sang ORDERED, toàn bộ
 * items được copy sang {@link OfficeSupplyOrder} và phiếu bị xóa (clear).
 */
@Entity
@Table(name = "office_supply_request",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_osr_user_warehouse",
        columnNames = {"user_id", "warehouse_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OfficeSupplyRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Kho / văn phòng: Quận 9 hoặc Phổ Quang. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private SupplyWarehouse warehouse;

    @Builder.Default
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OfficeSupplyRequestItem> items = new ArrayList<>();

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
