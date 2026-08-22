package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Một dòng nguyên liệu thực nhận trong một {@link MaterialRequestReceipt}.
 *
 * <p>Đây là NGUỒN SỰ THẬT của số thực nhận. {@code MaterialRequestItem.qtyReceived}
 * chỉ là TỔNG CỘNG DỒN được tính lại từ tất cả các dòng này sau mỗi đợt — giữ lại để
 * bước hoàn thành (tính giá vốn), báo cáo và phân tích giá không phải sửa gì.
 */
@Entity
@Table(name = "material_request_receipt_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialRequestReceiptItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private MaterialRequestReceipt receipt;

    /** Dòng nguyên liệu trong phiếu mà đợt này giao vào. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_item_id", nullable = false)
    private MaterialRequestItem materialRequestItem;

    /** Số lượng nhận TRONG ĐỢT NÀY (không phải tổng cộng dồn). */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal qty;

    /**
     * Loại đơn vị của {@link #qty}: STORAGE (đvt lưu kho) hoặc ORDER (đvt đặt hàng).
     *
     * <p><b>Ràng buộc quan trọng:</b> đợt ĐẦU TIÊN của một nguyên liệu sẽ KHOÁ giá trị
     * này; các đợt bù sau bắt buộc dùng cùng loại đơn vị. Nếu không, tổng cộng dồn
     * {@code qtyReceived} sẽ trộn "50 Kg + 2 Thùng = 52" → giá vốn và tồn kho đều sai.
     */
    @Column(name = "received_unit_type", length = 20)
    private String receivedUnitType;

    /** Đvt nhận thực tế (snapshot text, VD "Thùng"). */
    @Column(name = "received_unit", length = 50)
    private String receivedUnit;

    /** Tỷ lệ quy đổi áp dụng cho đợt này (1 orderUnit = ratio storageUnit). */
    @Column(name = "conversion_ratio", precision = 12, scale = 4)
    private BigDecimal conversionRatio;

    /** SL thực tế đã cộng vào kho (đã quy đổi ra đvt lưu kho) = qty × ratio nếu ORDER. */
    @Column(name = "stock_qty", precision = 12, scale = 3)
    private BigDecimal stockQty;

    /** HSD của lô hàng đợt này — mỗi đợt có thể khác nhau. */
    @Column(name = "expiry_date")
    private Long expiryDate;

    /** Nhật ký các lần cân của đợt này — JSON array số, VD "[39.05,40.95]". */
    @Column(name = "weighing_logs", columnDefinition = "TEXT")
    private String weighingLogs;

    /**
     * Lô tồn kho xưởng được tạo ra bởi dòng này (phiếu FACTORY). Dùng để truy vết
     * đợt nhận → lô kho. Null với phiếu SELLER (nhập kho ở bước hoàn thành).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_material_stock_id")
    private FactoryMaterialStock factoryMaterialStock;

    /** Chỉ dùng cho phiếu SELLER: kho nhận lô hàng của đợt này. */
    @Column(name = "warehouse_id")
    private Long warehouseId;
}
