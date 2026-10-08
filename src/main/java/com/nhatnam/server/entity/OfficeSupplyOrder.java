package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * LẦN ĐẶT HÀNG VPP — snapshot sau khi OWNER bấm "Đặt hàng".
 *
 * <p>Mỗi lần OWNER bấm "Đặt hàng":
 * <ol>
 *   <li>Tổng hợp toàn bộ {@link OfficeSupplyRequest} của văn phòng đó.</li>
 *   <li>Tạo 1 {@code OfficeSupplyOrder} với danh sách {@link OfficeSupplyOrderItem}.</li>
 *   <li>Clear toàn bộ request (xóa) để tổng hợp lần đặt tiếp theo.</li>
 * </ol>
 *
 * <p>{@link #placedAt} = thời điểm bấm nút — dùng để tính "bao nhiêu ngày từ lần mua
 * gần nhất" trên trang báo cáo.
 */
@Entity
@Table(name = "office_supply_order")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OfficeSupplyOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kho / văn phòng đặt hàng. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private SupplyWarehouse warehouse;

    /** Thời điểm OWNER bấm "Đặt hàng". */
    @Column(name = "placed_at", nullable = false)
    private Long placedAt;

    @Column(name = "placed_by_name", length = 200)
    private String placedByName;

    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OfficeSupplyOrderItem> items = new ArrayList<>();

    @Column(name = "created_at")
    private Long createdAt;

    /**
     * JSON các khoản phí phân bổ cho đơn hàng — mảng [{"name":"Phí giao","amount":40000}, …].
     *
     * <p>Nhập vào khi Owner bấm "Đặt hàng". Tổng phí được chia theo tỉ trọng tiền hàng
     * của từng dòng và cộng vào {@code unitPrice} của OrderItem; giữ lại raw ở đây để
     * trang chi tiết đơn hàng vẫn hiển thị được chi phí đã nhập.
     */
    @Column(name = "fees_json", columnDefinition = "TEXT")
    private String feesJson;

    /** Tổng tiền hàng (chưa cộng phí) — tiện cho báo cáo. */
    @Column(name = "subtotal_amount", precision = 18, scale = 2)
    private java.math.BigDecimal subtotalAmount;

    /** Tổng tiền phí — snapshot để không phải parse feesJson. */
    @Column(name = "fees_amount", precision = 18, scale = 2)
    private java.math.BigDecimal feesAmount;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
