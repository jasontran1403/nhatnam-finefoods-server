package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Một dòng THUẾ/PHÍ của {@link SupplyOrderGroup} — label do kế toán tự nhập
 * (VD "VAT 10%", "Phí vận chuyển"), giống form xử lý phiếu đặt hàng nguyên liệu.
 *
 * <p>Số tiền được PHÂN BỔ NGƯỢC vào từng mặt hàng của nhóm theo
 * <b>tỷ trọng giá trị</b> ({@code qty × unitPrice}) — xem
 * {@code com.nhatnam.server.utils.CostAllocation}.
 */
@Entity
@Table(name = "supply_order_group_fee")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplyOrderGroupFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private SupplyOrderGroup group;

    @Column(nullable = false, length = 200)
    private String label;

    /** Cho phép 3 số thập phân như mọi số tiền trong luồng này. */
    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal amount;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;
}
