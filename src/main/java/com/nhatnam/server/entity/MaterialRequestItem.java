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
     * TỔNG số lượng thực nhận, CỘNG DỒN qua tất cả các đợt nhận
     * ({@link MaterialRequestReceiptItem}). Được tính lại sau mỗi lần lưu đợt.
     * Null nếu chưa nhận đợt nào.
     *
     * <p>Đơn vị của số này = {@link #receivedUnitType} (bị khoá từ đợt đầu tiên).
     */
    @Column(name = "qty_received", precision = 12, scale = 3)
    private BigDecimal qtyReceived;

    /**
     * Tiến độ nhận của riêng dòng này:
     * <ul>
     *   <li>{@code PENDING} — chưa nhận đợt nào.</li>
     *   <li>{@code PARTIAL} — đã nhận một phần, còn chờ giao bù.</li>
     *   <li>{@code FULFILLED} — đã nhận đủ (hoặc dư) so với số đặt.</li>
     *   <li>{@code CLOSED_SHORT} — chốt khi vẫn còn thiếu (NCC không giao bù nữa);
     *       chỉ set ở bước "Xác nhận đã giao xong", kèm lý do ở cấp phiếu.</li>
     * </ul>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "receive_status", length = 20)
    @Builder.Default
    private ReceiveStatus receiveStatus = ReceiveStatus.PENDING;

    public enum ReceiveStatus { PENDING, PARTIAL, FULFILLED, CLOSED_SHORT }

    /**
     * Ngày hạn sử dụng của lô hàng nhận (nullable).
     * Được nhập cùng lúc xác nhận nhận hàng.
     */
    @Column(name = "expiry_date")
    private Long expiryDate;

    /**
     * Nhật ký các lần cân thực tế khi nhận hàng — lưu dạng JSON array số,
     * ví dụ "[39.05,40.95,40]". Dùng để đối chiếu/kiểm tra lại tổng qtyReceived
     * (vì hàng thường nhận theo rổ/quy cách, mỗi lần cân 1 giá trị rồi cộng dồn).
     * Null nếu nhập trực tiếp tổng (không qua tính năng cộng dồn nhiều lần cân).
     */
    @Column(name = "weighing_logs", columnDefinition = "TEXT")
    private String weighingLogs;

    /**
     * Nguyên liệu xưởng liên kết (nếu chọn từ danh mục). Nullable khi nhập free-text.
     * Dùng để tra tỷ lệ quy đổi / HSD / lead-time khi xác nhận & nhận hàng.
     */
    @Column(name = "factory_material_id")
    private Long factoryMaterialId;

    /**
     * Đơn vị dùng khi ĐẶT ({@code unit} ở trên là đvt hiển thị/đặt). Loại đơn vị người
     * dùng chọn khi tạo phiếu: STORAGE (đvt lưu kho) hoặc ORDER (đvt đặt hàng).
     */
    @Column(name = "order_unit_type", length = 20)
    private String orderUnitType;

    /**
     * Loại đơn vị khi NHẬN thực tế: STORAGE (đvt lưu kho) hoặc ORDER (đvt đặt hàng).
     * Nếu ORDER → khi cộng kho nhân với {@code conversionRatio}.
     */
    @Column(name = "received_unit_type", length = 20)
    private String receivedUnitType;

    /** Đơn vị nhận thực tế (snapshot text, ví dụ "Thùng"/"Chai"). */
    @Column(name = "received_unit", length = 50)
    private String receivedUnit;

    /** Tỷ lệ quy đổi snapshot lúc đặt (1 orderUnit = ratio storageUnit). Nullable. */
    @Column(name = "conversion_ratio", precision = 12, scale = 4)
    private BigDecimal conversionRatio;

    /**
     * Nhà cung cấp đã cung cấp dòng nguyên liệu này — kế toán gán khi Hoàn thành phiếu
     * (chọn trong danh sách NCC của phiếu — {@link MaterialRequestVendor}).
     * Nullable cho tới khi hoàn thành.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplied_by_vendor_id")
    private MaterialRequestVendor suppliedByVendor;

    /**
     * Đơn giá (VNĐ/đơn vị) — kế toán nhập khi Hoàn thành phiếu, dựa trên qtyReceived thực tế.
     * Nullable cho tới khi hoàn thành.
     */
    @Column(name = "unit_price", precision = 18, scale = 3)
    private BigDecimal unitPrice;

    /**
     * Thành tiền dòng này = qtyReceived * unitPrice (snapshot lúc hoàn thành, để không
     * phải tính lại nếu unitPrice/qtyReceived có thay đổi định dạng tính toán sau này).
     */
    @Column(name = "line_amount", precision = 18, scale = 3)
    private BigDecimal lineAmount;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /** Chỉ dùng cho phiếu SELLER: liên kết tới Ingredient được đặt. Nullable với phiếu FACTORY. */
    @Column(name = "ingredient_id")
    private Long ingredientId;

    /** Chỉ dùng cho phiếu SELLER: kho nhận lô hàng (chọn ở bước nhận). Nullable. */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    // ══════════════════════════════════════════════════════════════════════════
    //  PHIẾU ĐẶT VĂN PHÒNG PHẨM / ĐỒ DÙNG (orderType = SUPPLY)
    //  Các field dưới đây đều nullable → phiếu nguyên liệu không bị ảnh hưởng.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Danh mục khoản chi của NCC được chọn ({@link VendorExpenseCategory}).
     * Quyết định dòng này là hàng tiêu hao (nhập kho) hay dịch vụ (không nhập kho).
     */
    @Column(name = "expense_category_id")
    private Long expenseCategoryId;

    /**
     * Vật dụng tồn kho tương ứng ({@link SupplyItem}) — snapshot từ category lúc
     * TẠO PHIẾU. Null khi category là SERVICE (dịch vụ ⇒ không nhập kho).
     */
    @Column(name = "supply_item_id")
    private Long supplyItemId;

    /**
     * Quy cách — SNAPSHOT lúc tạo phiếu (VD "4L/chai"). Đổi danh mục về sau
     * không làm sai lệch phiếu cũ. ({@code materialName} đóng vai trò itemName,
     * {@code unit} đóng vai trò đơn vị tính — dùng lại cột có sẵn.)
     */
    @Column(length = 200)
    private String specification;

    /** NCC cung cấp dòng này — gán ở bước xác nhận đặt hàng. FK MaterialVendor. */
    @Column(name = "supplier_id")
    private Long supplierId;

    /** Nhóm NCC ({@link SupplyOrderGroup}) mà dòng này thuộc về. */
    @Column(name = "supply_group_id")
    private Long supplyGroupId;

    /**
     * Kiểu nhập tiền ở bước tất toán:
     * {@code UNIT_PRICE} (mặc định — nhập đơn giá 1 đơn vị) hoặc
     * {@code TOTAL} (nhập tổng tiền của mặt hàng, BE tự chia ra đơn giá).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_input_mode", length = 20)
    private PriceInputMode priceInputMode;

    /**
     * Tổng tiền của mặt hàng SAU khi đã phân bổ thuế/phí (3 số thập phân).
     * Khác {@link #lineAmount} ở chỗ lineAmount dùng chung với luồng nguyên liệu;
     * ở luồng SUPPLY hai giá trị này bằng nhau — giữ riêng cho rõ nghĩa báo cáo.
     */
    @Column(name = "total_amount", precision = 18, scale = 3)
    private BigDecimal totalAmount;

    /**
     * Đã CHỐT NHẬN dòng này (dù còn thiếu). Phiếu chuyển sang RECEIVED khi
     * TẤT CẢ các dòng đều {@code receiveClosed = true}.
     */
    @Column(name = "receive_closed", nullable = false)
    @Builder.Default
    private boolean receiveClosed = false;

    /** Ghi chú riêng của từng mặt hàng (người tạo nhập ở bước 1). */
    @Column(columnDefinition = "TEXT")
    private String note;

    public enum PriceInputMode { UNIT_PRICE, TOTAL }
}