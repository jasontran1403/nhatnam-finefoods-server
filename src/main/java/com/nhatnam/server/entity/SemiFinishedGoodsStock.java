package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Kho bán thành phẩm của xưởng (đơn vị: kg) — nơi nhận sản lượng ĐẠT chất lượng
 * ngay khi hoàn thành 1 mẻ sản xuất (completeBatch), TRƯỚC khi đóng gói.
 *
 * Luồng:
 *  1. completeBatch → tách actualOutputQty (đạt) vào đây + scrapQty (lỗi) vào ScrapStock.
 *  2. Trưởng xưởng/Nhân viên xưởng lập Phiếu chuyển kho (SemiFinishedTransferNote) từ
 *     đây sang Kho thành phẩm (FinishedGoodsStock) — trừ theo FIFO (ưu tiên lô cũ nhất/
 *     gần hết hạn nhất), có thể trừ từng phần qua nhiều lần, từ nhiều batch khác nhau.
 *  3. Kế toán kho xưởng (FACTORY_ACCOUNTANT) xác nhận nhận phiếu chuyển → ghi vào
 *     FinishedGoodsStock theo SỐ LƯỢNG ĐÓNG GÓI (túi/hộp) + tổng trọng lượng thực cân,
 *     phần chênh lệch (kg chuyển − kg thực cân) → lập Biên bản hao hụt đóng gói.
 */
@Entity
@Table(name = "semi_finished_goods_stock")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SemiFinishedGoodsStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Thành phẩm (FactoryProduct) — plain FK + snapshot tên để vẫn đọc được nếu sản phẩm bị xoá */
    @Column(name = "factory_product_id")
    private Long factoryProductId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** Luôn là "Kg" — sản lượng trước đóng gói luôn tính theo trọng lượng */
    @Column(nullable = false, length = 50)
    private String unit;

    /** Số lượng còn lại (kg) của lô này, sau khi đã trừ các lần chuyển kho */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Số lượng ban đầu khi nhập (để trace) */
    @Column(name = "initial_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal initialQuantity;

    /** Ngày sản xuất — snapshot từ batch */
    @Column(name = "manufacture_date")
    private Long manufactureDate;

    /** Hạn sử dụng — snapshot từ batch */
    @Column(name = "expiry_date")
    private Long expiryDate;

    /**
     * Giá vốn nguyên liệu của 1 kg bán thành phẩm trong lô này (đồng/kg, 6 chữ số
     * thập phân, CHƯA làm tròn) — snapshot từ {@code ProductionBatch.unitCost}.
     *
     * <p>Chỉ mang tính tham chiếu/hiển thị. Giá vốn CHÍNH THỨC được chốt ở bước
     * kế toán xưởng xác nhận nhận phiếu chuyển sang Kho thành phẩm (lúc đó mới biết
     * số kg thực tế còn lại sau hao hụt đóng gói/cấp đông), nên ở bước đó hệ thống
     * TÍNH LẠI theo giá lô nguyên liệu tại thời điểm chốt — phòng trường hợp phiếu
     * đặt hàng được kế toán trưởng chốt giá SAU khi mẻ đã xong.
     */
    @Column(name = "unit_cost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    /** Mẻ sản xuất nguồn gốc (để trace ngược) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private ProductionBatch batch;

    @Column(name = "batch_code_snapshot", length = 100)
    private String batchCodeSnapshot;

    /** Xưởng sản xuất ra lô này (nullable) */
    @Column(name = "factory_id")
    private Long factoryId;

    @Column(name = "factory_name_snapshot", length = 200)
    private String factoryNameSnapshot;

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