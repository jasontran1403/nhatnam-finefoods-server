package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * NVL thực tế được dùng trong một mẻ sản xuất.
 * Ví dụ: mẻ này dùng 40kg thịt (standard: 30kg) → hao hụt +33.3%
 */
@Entity
@Table(name = "production_batch_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductionBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private ProductionBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_material_id", nullable = false)
    private FactoryMaterial factoryMaterial;

    /** Snapshot tên NVL */
    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    /** Số lượng thực tế dùng */
    @Column(name = "actual_qty", nullable = false, precision = 10, scale = 3)
    private BigDecimal actualQty;

    /** Đơn vị */
    @Column(nullable = false, length = 50)
    private String unit;

    /** Định lượng chuẩn tương ứng từ công thức (snapshot khi tạo mẻ) */
    @Column(name = "standard_qty", nullable = false, precision = 10, scale = 3)
    private BigDecimal standardQty;

    /**
     * Tỷ lệ lệch so với định lượng chuẩn (%).
     * = (actualQty - standardQty) / standardQty * 100
     * Dương (+%) = dùng nhiều hơn chuẩn
     * Âm  (–%) = dùng ít hơn chuẩn (tiết kiệm)
     */
    @Column(name = "variance_pct", precision = 8, scale = 2)
    private BigDecimal variancePct;

    /** Thứ tự hiển thị */
    @Builder.Default
    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}
