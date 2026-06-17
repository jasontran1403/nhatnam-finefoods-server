package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Ghi lại từng lô nguyên liệu kho xưởng đã bị trừ khi bắt đầu một lệnh sản xuất (FIFO).
 * Dùng để hoàn kho khi lệnh/mẻ bị hủy hoặc máy móc hỏng sản xuất dang dở:
 * sẽ nhập số lượng nguyên liệu đã thực tế sử dụng, phần còn lại được cộng trả
 * lại đúng lô gốc này.
 *
 * Ví dụ: lệnh cần 50kg thịt, trừ theo FIFO (ưu tiên lô gần hết hạn nhất):
 *   - lô A (hết hạn 01/12): trừ 30kg  → 1 bản ghi deductedQty=30
 *   - lô B (hết hạn 02/12): trừ 20kg  → 1 bản ghi deductedQty=20
 * Nếu mẻ bị hủy và thực tế chỉ dùng 35kg:
 *   - phân bổ FIFO: lô A coi như dùng hết 30kg, lô B dùng 5/20kg
 *   - hoàn lại lô B: 20 - 5 = 15kg
 */
@Entity
@Table(name = "work_order_stock_deduction")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkOrderStockDeduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lệnh sản xuất liên quan */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;

    /** Lô nguyên liệu trong kho xưởng bị trừ */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private FactoryMaterialStock stock;

    /** Snapshot tên NVL (đề phòng lô bị xóa/đổi tên sau này) */
    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    /** Đơn vị */
    @Column(nullable = false, length = 50)
    private String unit;

    /** Số lượng đã trừ ra khỏi lô này khi bắt đầu lệnh sản xuất */
    @Column(name = "deducted_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal deductedQty;

    /**
     * Số lượng thực tế đã sử dụng — chỉ có giá trị sau khi mẻ bị hủy và
     * nhân viên nhập số lượng đã dùng (luôn <= deductedQty).
     * null = chưa hủy / chưa xử lý.
     */
    @Column(name = "actual_used_qty", precision = 12, scale = 3)
    private BigDecimal actualUsedQty;

    /**
     * Đã hoàn kho phần dư (deductedQty - actualUsedQty) chưa.
     * true sau khi đã xử lý hủy mẻ (dù phần dư = 0 vẫn đánh dấu true để không xử lý lại).
     */
    @Builder.Default
    @Column(name = "returned")
    private Boolean returned = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() {
        createdAt = System.currentTimeMillis();
    }
}