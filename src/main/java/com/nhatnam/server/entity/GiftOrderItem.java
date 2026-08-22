package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * DÒNG SẢN PHẨM TRONG PHIẾU TẶNG QUÀ.
 *
 * <p>CỐ Ý KHÔNG có giá. Phiếu tặng quà không phải chứng từ bán hàng: seller đứng trước
 * mặt khách chỉ cần chọn "tặng cái gì, bao nhiêu", còn giá vốn đã được ghi nhận đầy đủ
 * ở phiếu xuất kho sinh ra lúc duyệt. Đưa giá lên phiếu này chỉ tạo cơ hội cho người
 * dùng hiểu nhầm đây là đơn bán giảm 100%.
 */
@Entity
@Table(name = "gift_order_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GiftOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gift_order_id", nullable = false)
    private GiftOrder giftOrder;

    /** FK dạng plain — sản phẩm có thể bị xoá mềm sau khi phiếu đã lưu. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** Snapshot tên + ĐVT: phiếu cũ vẫn đọc được nguyên vẹn khi sản phẩm đổi tên. */
    @Column(name = "product_name", nullable = false, length = 250)
    private String productName;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;
}
