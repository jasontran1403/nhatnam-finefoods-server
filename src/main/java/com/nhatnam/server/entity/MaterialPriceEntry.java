package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Biến động giá nguyên liệu — mỗi dòng ghi nhận 1 lần cập nhật đơn giá.
 * Đơn giá luôn quy về ĐƠN VỊ NHỎ NHẤT (VD: đ/gram, đ/ml).
 */
@Entity
@Table(name = "material_price_entry")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialPriceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên nguyên liệu hoặc tên sản phẩm đồ dùng tiêu hao */
    @Column(name = "material_name", nullable = false, length = 300)
    private String materialName;

    /** Đơn vị tính (kg, g, lít, chai...) */
    @Column(name = "unit", length = 50)
    private String unit;

    /** Đơn giá (đã quy về đơn vị nhỏ nhất) */
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /** Số lượng (nếu nhập theo option 1: số lượng + tổng tiền) */
    @Column(name = "quantity", precision = 15, scale = 4)
    private BigDecimal quantity;

    /** Tổng tiền (nếu nhập theo option 1) */
    @Column(name = "total_amount", precision = 15, scale = 4)
    private BigDecimal totalAmount;

    /** Nhà cung cấp / đơn vị cung cấp */
    @Column(name = "supplier_name", length = 300)
    private String supplierName;

    /** Tên người cập nhật — lưu nhưng không hiển thị */
    @Column(name = "updated_by_name", length = 200)
    private String updatedByName;

    /** Thời gian tạo (epoch ms) */
    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    /** Loại: MATERIAL hoặc CONSUMABLE */
    @Column(name = "entry_type", length = 20)
    @Builder.Default
    private String entryType = "MATERIAL";

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
    }
}
