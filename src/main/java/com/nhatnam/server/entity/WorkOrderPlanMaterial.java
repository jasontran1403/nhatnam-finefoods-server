package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Nguyên liệu trong phương án sản xuất.
 * Bao gồm: tên NVL, NCC, số lượng, đơn giá ước tính.
 * Chứng từ hóa đơn sẽ được upload sau khi mua về.
 */
@Entity
@Table(name = "work_order_plan_material")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkOrderPlanMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private WorkOrderPlan plan;

    /** Tên nguyên liệu */
    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    /** Số lượng cần */
    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, length = 50)
    private String unit;

    /** Tên nhà cung cấp */
    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    /** SĐT nhà cung cấp */
    @Column(name = "vendor_phone", length = 20)
    private String vendorPhone;

    /** Đơn giá ước tính */
    @Column(name = "estimated_unit_price", precision = 15, scale = 2)
    private BigDecimal estimatedUnitPrice;

    /** Thành tiền ước tính = quantity * estimatedUnitPrice */
    @Column(name = "estimated_total", precision = 15, scale = 2)
    private BigDecimal estimatedTotal;

    /** Ảnh hóa đơn/chứng từ mua nguyên liệu (JSON array URLs) */
    @Column(name = "invoice_images", columnDefinition = "TEXT")
    @Builder.Default
    private String invoiceImages = "[]";

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;
}
