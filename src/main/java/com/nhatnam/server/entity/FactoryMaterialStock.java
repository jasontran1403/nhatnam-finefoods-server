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

    /**
     * GIÁ VỐN của 1 đơn vị LƯU KHO của lô này (đồng / {@link #unit}).
     *
     * <p>= tổng tiền của dòng nguyên liệu (đã gồm phí/thuế được phân bổ) chia cho số
     * lượng thực nhập kho. Được ghi ở bước CUỐI của phiếu đặt hàng — khi kế toán
     * trưởng nhập giá + phí/thuế và Hoàn thành phiếu
     * ({@code MaterialRequestService.complete}).
     *
     * <p>Trước bước đó (vừa nhận hàng, chưa chốt giá) giá vốn = 0 — đúng ý nghĩa
     * "chưa biết giá", không phải "miễn phí". Lô nhập tay không qua phiếu cũng = 0.
     */
    @Builder.Default
    @Column(name = "unit_cost", precision = 15, scale = 2)
    private BigDecimal unitCost = BigDecimal.ZERO;

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

    /**
     * Xưởng sở hữu lô tồn kho này. Mỗi xưởng có kho riêng.
     * Nullable để tương thích dữ liệu cũ (được backfill về Xưởng Quận 9).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_factory_id")
    private ProductionFactory productionFactory;

    /** Mã phiếu đặt hàng nguồn (snapshot, để hiển thị/tìm kiếm ở trang chi tiết lô). */
    @Column(name = "material_request_code", length = 50)
    private String materialRequestCode;

    /**
     * Mục 7 — snapshot 2 field của NCC cung cấp lô này, để tìm kiếm ở trang Chi tiết
     * các lô (OWNER/ADMIN). Lấy từ MaterialRequestVendor khi kế toán trưởng Hoàn thành phiếu.
     */
    @Column(name = "import_receipt_info", columnDefinition = "TEXT")
    private String importReceiptInfo;

    @Column(name = "serial_imei", columnDefinition = "TEXT")
    private String serialImei;

    /** Ngày đặt hàng (snapshot orderedAt của phiếu) — hiển thị ở trang chi tiết lô. */
    @Column(name = "ordered_at")
    private Long orderedAt;

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