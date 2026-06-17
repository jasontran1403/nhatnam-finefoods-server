package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Lô nguyên liệu trong kho xưởng.
 * Mỗi lần nhập (từ phiếu đặt hàng) tạo 1 bản ghi mới.
 * Xuất theo FIFO dựa trên createdAt (hoặc expiryDate nếu có).
 */
@Entity
@Table(name = "factory_material_stock")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FactoryMaterialStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên nguyên liệu */
    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    /** Đơn vị tính */
    @Column(nullable = false, length = 20)
    private String unit;

    /** Số lượng hiện tại của lô này */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Số lượng ban đầu khi nhập (để trace) */
    @Column(name = "initial_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal initialQuantity;

    /** Ngày hết hạn (nullable) */
    @Column(name = "expiry_date")
    private Long expiryDate;

    /** Phiếu đặt hàng nguồn gốc (nullable nếu nhập thủ công) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_id")
    private MaterialRequest materialRequest;

    /** Item trong phiếu đặt hàng (để trace ngược) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_item_id")
    private MaterialRequestItem materialRequestItem;

    /** WorkOrder đã dùng lô này (null nếu chưa dùng) */
    @Column(name = "work_order_id")
    private Long workOrderId;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
