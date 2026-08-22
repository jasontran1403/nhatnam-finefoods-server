package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Ghi lại CHÍNH XÁC batch nào, lấy bao nhiêu kg, trong 1 dòng phiếu chuyển kho
 * (SemiFinishedTransferNoteLine) — để truy vết ngược: lệnh sản xuất ban đầu →
 * mẻ nào → bán thành phẩm → đã chuyển bao nhiêu kg sang đóng gói.
 */
@Entity
@Table(name = "semi_finished_transfer_source_batch")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SemiFinishedTransferSourceBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_line_id", nullable = false)
    private SemiFinishedTransferNoteLine transferLine;

    /** Lô nguồn ở Kho bán thành phẩm đã bị trừ (FIFO) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "semi_finished_stock_id")
    private SemiFinishedGoodsStock semiFinishedStock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private ProductionBatch batch;

    @Column(name = "batch_code_snapshot", length = 100)
    private String batchCodeSnapshot;

    /** Số kg lấy từ batch này trong dòng chuyển kho này */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;
}
