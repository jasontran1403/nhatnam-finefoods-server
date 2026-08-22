package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Một dòng THUẾ / PHÍ của phiếu nhập kho (IMPORT).
 *
 * <p>Mỗi dòng là 1 loại thuế/phí riêng, label do kế toán trưởng tự nhập
 * (VD: "Thuế nhập khẩu", "Phí vận chuyển", "Phí lưu kho"...).
 *
 * <p>Số tiền của dòng được PHÂN BỔ cho từng nguyên liệu trong phạm vi áp dụng
 * ({@link #appliesToItemIds}) theo TỶ TRỌNG GIÁ TRỊ của nguyên liệu đó
 * (giá trị dòng = đơn giá × số lượng) trên tổng giá trị của các dòng trong phạm vi.
 *
 * <p>Nếu {@code appliesToItemIds} rỗng/null → áp dụng cho TẤT CẢ dòng của phiếu.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "warehouse_receipt_cost_entry")
public class WarehouseReceiptCostEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private WarehouseReceipt receipt;

    /** Tên loại thuế/phí — kế toán trưởng tự nhập */
    @Column(nullable = false, length = 255)
    private String label;

    /** Tổng số tiền của khoản thuế/phí này (cho phép 3 số thập phân) */
    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal amount;

    /** JSON danh sách WarehouseReceiptItem.id được áp dụng. Rỗng "[]" = áp dụng cho tất cả. */
    @Column(name = "applies_to_item_ids", columnDefinition = "TEXT")
    private String appliesToItemIds;

    @Column(name = "sort_order")
    @Builder.Default
    private int sortOrder = 0;
}
