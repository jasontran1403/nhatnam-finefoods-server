package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 1 dòng sản phẩm trong Phiếu chuyển kho (SemiFinishedTransferNote).
 * Mỗi dòng: tổng kg chuyển của 1 sản phẩm (trừ FIFO từ nhiều batch nguồn ở Kho
 * bán thành phẩm — xem SemiFinishedTransferSourceBatch), và sau khi kế toán kho
 * xác nhận nhận (Bước 3) sẽ có thêm: số lượng đóng gói thực tế + tổng trọng
 * lượng thực cân + hao hụt tính ra cho riêng dòng này.
 */
@Entity
@Table(name = "semi_finished_transfer_note_line")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SemiFinishedTransferNoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_note_id", nullable = false)
    private SemiFinishedTransferNote transferNote;

    @Column(name = "factory_product_id")
    private Long factoryProductId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** Luôn "Kg" — tổng kg chuyển của sản phẩm này (trừ từ kho bán thành phẩm) */
    @Column(nullable = false, length = 50)
    private String unit;

    @Column(name = "transferred_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal transferredQty;

    // ── Sau khi kế toán kho xác nhận nhận (Bước 3 + 4) ───────────────────────

    /** Số lượng đóng gói thực tế đếm được (VD: 59 túi) */
    @Column(name = "packaged_qty", precision = 12, scale = 3)
    private BigDecimal packagedQty;

    /** Đơn vị đóng gói (VD: "túi") — snapshot từ recipe lúc xác nhận, có thể null nếu recipe chưa cấu hình */
    @Column(name = "packaged_unit", length = 50)
    private String packagedUnit;

    /** Tổng trọng lượng thực cân của tất cả gói đã đóng (kg) — kế toán kho nhập tay */
    @Column(name = "actual_received_weight", precision = 12, scale = 3)
    private BigDecimal actualReceivedWeight;

    /** Hao hụt đóng gói (kg) = transferredQty − actualReceivedWeight, chỉ tính khi > 0 */
    @Column(name = "loss_qty", precision = 12, scale = 3)
    private BigDecimal lossQty;

    @Builder.Default
    @OneToMany(mappedBy = "transferLine", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SemiFinishedTransferSourceBatch> sourceBatches = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist void onCreate() { createdAt = System.currentTimeMillis(); }
}
