package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Phiếu kho.
 *
 * THAY ĐỔI (Feature 2):
 * - Thêm status: PENDING_COST (chờ kế toán nhập giá vốn) | CONFIRMED (đã xác nhận)
 *   Khi WAREHOUSE tạo phiếu IMPORT → status = PENDING_COST, chưa cộng tồn kho.
 *   Khi ACCOUNTANT nhập giá vốn đủ và xác nhận → status = CONFIRMED, cộng tồn kho.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "warehouse_receipt")
public class WarehouseReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String receiptCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReceiptType receiptType;

    /**
     * Trạng thái phiếu nhập:
     * - PENDING_COST: WAREHOUSE đã tạo, chờ ACCOUNTANT nhập giá vốn (chưa cộng tồn kho).
     * - CONFIRMED: ACCOUNTANT đã nhập giá vốn và xác nhận, tồn kho đã được cộng.
     * - NULL (hoặc các loại khác IMPORT): không áp dụng workflow này.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_status")
    @Builder.Default
    private CostStatus costStatus = CostStatus.CONFIRMED; // Mặc định cho các loại không phải IMPORT

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_warehouse_id")
    private Warehouse partnerWarehouse;

    @Column
    private String referenceCode;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "image_urls", columnDefinition = "TEXT")
    private String imageUrls;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User createdBy;

    @Column(nullable = false)
    private String createdByName;

    @Column
    private Long linkedReceiptId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    /** Người kế toán xác nhận giá vốn */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_confirmed_by_id")
    private User costConfirmedBy;

    @Column(name = "cost_confirmed_by_name", length = 200)
    private String costConfirmedByName;

    @Column(name = "cost_confirmed_at")
    private Long costConfirmedAt;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WarehouseReceiptItem> items = new ArrayList<>();

    private Long createdAt;
    private Long updatedAt;

    public enum ReceiptType {
        IMPORT,
        EXPORT_ORDER,
        EXPORT_OTHER,
        ADJUST,
        TRANSFER_OUT,
        TRANSFER_IN
    }

    public enum CostStatus {
        PENDING_COST,  // Chờ kế toán nhập giá vốn
        CONFIRMED      // Đã xác nhận, tồn kho đã cộng
    }
}
