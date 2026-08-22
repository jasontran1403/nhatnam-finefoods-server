package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Kho Scrap của xưởng (đơn vị: kg) — nơi lưu lại sản lượng KHÔNG ĐẠT chất lượng
 * khi hoàn thành 1 mẻ sản xuất (completeBatch). Chỉ dùng để lưu vết/báo cáo,
 * không có luồng xuất/chuyển kho như FinishedGoodsStock (hàng lỗi không bán được).
 */
@Entity
@Table(name = "scrap_stock")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ScrapStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_product_id")
    private Long factoryProductId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** Luôn là "Kg" */
    @Column(nullable = false, length = 50)
    private String unit;

    /** Số kg hàng lỗi ghi nhận từ mẻ này */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Lý do lỗi — bắt buộc nhập khi hoàn thành mẻ nếu có scrapQty > 0 */
    @Column(columnDefinition = "TEXT")
    private String reason;

    /** Mẻ sản xuất nguồn gốc */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private ProductionBatch batch;

    @Column(name = "batch_code_snapshot", length = 100)
    private String batchCodeSnapshot;

    @Column(name = "factory_id")
    private Long factoryId;

    @Column(name = "factory_name_snapshot", length = 200)
    private String factoryNameSnapshot;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist void onCreate() { createdAt = System.currentTimeMillis(); }
}
