package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Biên bản hao hụt đóng gói (Bước 4) — tự động sinh ra khi kế toán kho xác
 * nhận nhận 1 Phiếu chuyển kho (SemiFinishedTransferNote) mà tổng trọng lượng
 * thực cân (actualReceivedWeight) NHỎ HƠN tổng kg đã chuyển (transferredQty)
 * của dòng đó.
 *
 * Hao hụt = transferredQty (kg phiếu chuyển) − actualReceivedWeight (kg kế
 * toán kho cân thực tế khi nhận) — không liên quan đến định lượng đóng gói
 * chuẩn (packagingQty trên Recipe chỉ dùng để ước tính số gói dự kiến).
 */
@Entity
@Table(name = "packaging_loss_report")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PackagingLossReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_code", nullable = false, unique = true, length = 100)
    private String reportCode;

    /** Phiếu chuyển kho nguồn gốc */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_note_id", nullable = false)
    private SemiFinishedTransferNote transferNote;

    @Column(name = "transfer_note_code_snapshot", length = 100)
    private String transferNoteCodeSnapshot;

    /** Dòng sản phẩm tương ứng trong phiếu chuyển */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_line_id", nullable = false)
    private SemiFinishedTransferNoteLine transferLine;

    @Column(name = "factory_product_id")
    private Long factoryProductId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** Tổng kg đã chuyển (từ phiếu chuyển) */
    @Column(name = "transferred_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal transferredQty;

    /** Tổng kg thực cân khi nhận (kế toán kho nhập) */
    @Column(name = "actual_received_weight", nullable = false, precision = 12, scale = 3)
    private BigDecimal actualReceivedWeight;

    /** Hao hụt (kg) = transferredQty − actualReceivedWeight, luôn > 0 */
    @Column(name = "loss_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal lossQty;

    /** Số lượng đóng gói thực tế (để tham chiếu, không dùng để tính hao hụt) */
    @Column(name = "packaged_qty", precision = 12, scale = 3)
    private BigDecimal packagedQty;

    @Column(name = "packaged_unit", length = 50)
    private String packagedUnit;

    /** Các batch nguồn (snapshot dạng text, VD: "B001 (20kg), B002 (10kg)") — để hiển thị nhanh, chi tiết xem qua transferLine.sourceBatches */
    @Column(name = "source_batches_snapshot", columnDefinition = "TEXT")
    private String sourceBatchesSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_id")
    private User recordedBy;

    @Column(name = "recorded_by_name", length = 200)
    private String recordedByName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist void onCreate() { createdAt = System.currentTimeMillis(); }
}
