package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Dòng nguyên liệu trong phiếu đặt hàng.
 */
@Entity
@Table(name = "material_request_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_id", nullable = false)
    private MaterialRequest materialRequest;

    /** Tên nguyên liệu (free text) */
    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    /**
     * Đơn vị tính — hardcode: Kg, Gr, Lít, Túi, Hộp, Bịch, Thùng, Chai, Lon, Can
     */
    @Column(nullable = false, length = 20)
    private String unit;

    /** Số lượng yêu cầu */
    @Column(name = "qty_requested", nullable = false, precision = 12, scale = 3)
    private BigDecimal qtyRequested;

    /**
     * Số lượng thực nhận (nhân viên nhập khi nhận hàng).
     * Null nếu chưa nhận.
     */
    @Column(name = "qty_received", precision = 12, scale = 3)
    private BigDecimal qtyReceived;

    /**
     * Ngày hạn sử dụng của lô hàng nhận (nullable).
     * Được nhập cùng lúc xác nhận nhận hàng.
     */
    @Column(name = "expiry_date")
    private Long expiryDate;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;
}
