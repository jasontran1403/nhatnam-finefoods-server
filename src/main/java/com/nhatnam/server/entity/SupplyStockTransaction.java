package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * LỊCH SỬ NHẬP / RÚT kho VPP. Bất biến — mỗi thao tác sinh 1 dòng, không sửa/xoá.
 *
 * <ul>
 *   <li>{@code IN}  — phát sinh khi NGƯỜI TẠO PHIẾU xác nhận nhận hàng (theo số thực nhận).</li>
 *   <li>{@code OUT} — phát sinh khi người được gán kho "Rút sử dụng".</li>
 * </ul>
 */
@Entity
@Table(name = "supply_stock_transaction", indexes = {
        @Index(name = "idx_sst_wh_item", columnList = "warehouse_id,supply_item_id"),
        @Index(name = "idx_sst_created", columnList = "created_at")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplyStockTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "supply_item_id", nullable = false)
    private Long supplyItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TxType type;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal quantity;

    /** Tồn SAU giao dịch — snapshot để đối chiếu, không phải nguồn sự thật. */
    @Column(name = "balance_after", precision = 18, scale = 3)
    private BigDecimal balanceAfter;

    /** RECEIPT (đợt nhận hàng) | WITHDRAW (phiếu rút) | MERGE (gộp danh mục) */
    @Column(name = "ref_type", length = 30)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    /** Lý do rút / ghi chú đợt nhận. */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "performed_by_id")
    private Long performedById;

    @Column(name = "performed_by_name", length = 200)
    private String performedByName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }

    public enum TxType { IN, OUT }
}
