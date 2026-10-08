package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Dòng chi tiết của {@link OfficeSupplyOrder}.
 *
 * <p>Lưu cả {@code userId} / {@code userFullName} / {@code userPosition}
 * để trang chi tiết đơn hàng hiển thị "nhân viên nào đặt bao nhiêu".
 */
@Entity
@Table(name = "office_supply_order_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OfficeSupplyOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private OfficeSupplyOrder order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supply_item_id", nullable = false)
    private SupplyItem supplyItem;

    /** Nhân viên đặt — snapshot tên để không mất khi đổi hồ sơ. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_full_name", length = 200)
    private String userFullName;

    @Column(name = "user_position", length = 200)
    private String userPosition;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(length = 500)
    private String note;

    /**
     * Đơn giá đã BAO GỒM phí phân bổ, LƯU theo đơn vị tính nhỏ nhất.
     *
     * <p>Ví dụ: mua 100 cuốn tập tổng 1.000.000đ + phí giao hàng 20.000đ (chia tỉ trọng
     * cho tất cả line của đơn) → unitPrice = (1.000.000 / 100) + (phần phí phân bổ / 100).
     *
     * <p>Null với các OrderItem được tạo TRƯỚC khi tính năng nhập giá được bật — các bản
     * ghi này không có mặt trong biểu đồ biến động giá.
     */
    @Column(name = "unit_price", precision = 18, scale = 4)
    private BigDecimal unitPrice;
}
