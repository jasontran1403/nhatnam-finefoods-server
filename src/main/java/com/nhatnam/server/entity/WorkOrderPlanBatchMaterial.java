package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Nguyên liệu riêng của TỪNG MẺ trong phương án sản xuất (preview trước khi
 * tạo ProductionBatch thật). Vì mỗi mẻ được làm tròn riêng theo loại đơn vị
 * (kg/g/lít/mét giữ 2 số lẻ; túi/hộp/chai/lọ/can làm tròn lên), số lượng từng
 * mẻ KHÔNG đơn giản là tổng chia đều — phải lưu riêng từng dòng.
 *
 * Ví dụ: biến thể 30kg dùng 5 túi gia vị, lệnh 45kg → 2 mẻ:
 *   Mẻ 1 (30kg, 100%): Gia vị 5 túi
 *   Mẻ 2 (15kg, 50%):  Gia vị 3 túi (làm tròn lên từ 2.5)
 */
@Entity
@Table(name = "work_order_plan_batch_material")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkOrderPlanBatchMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private WorkOrderPlan plan;

    /** Số thứ tự mẻ (1, 2, 3...) — khớp với batchNumber của ProductionBatch sau này */
    @Column(name = "batch_number", nullable = false)
    private Integer batchNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_material_id", nullable = false)
    private FactoryMaterial factoryMaterial;

    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    /** Số lượng cần dùng cho riêng mẻ này (đã làm tròn theo loại đơn vị) */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal qty;

    @Column(nullable = false, length = 50)
    private String unit;

    @Builder.Default
    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}
