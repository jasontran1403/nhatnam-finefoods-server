package com.nhatnam.server.dto.production;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTOs cho Kho bán thành phẩm (SemiFinishedGoodsStock) + Kho Scrap (ScrapStock).
 *
 * Luồng tổng quát (4 bước theo yêu cầu chủ dự án):
 *  1. completeBatch → tách sản lượng đạt (kg, vào Kho bán thành phẩm) +
 *     sản lượng lỗi (kg, vào Kho Scrap), theo batch.
 *  2. Trưởng xưởng/NV xưởng lập Phiếu chuyển kho (SemiFinishedTransferNote) từ
 *     Kho bán thành phẩm → Kho thành phẩm, ghi rõ kg + các batch nguồn.
 *  3. Kế toán kho xưởng (FACTORY_ACCOUNTANT) xác nhận nhận: nhập số lượng đóng
 *     gói thực tế (túi/hộp) + tổng trọng lượng thực cân → ghi vào kho thành phẩm.
 *  4. Hệ thống tự tính hao hụt = kg chuyển − kg thực nhận → Biên bản hao hụt
 *     đóng gói (PackagingLossReport), gắn theo phiếu chuyển + các batch nguồn.
 */
public class SemiFinishedGoodsDtos {

    // ─── Lô kho bán thành phẩm (1 dòng, gắn 1 batch) ───────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SemiFinishedLotDto {
        private Long id;
        private String productName;
        private String unit;            // luôn "Kg"
        private BigDecimal quantity;     // còn lại
        private BigDecimal initialQuantity;
        private Long manufactureDate;
        private Long expiryDate;
        private String batchCode;
        private Long batchId;
        private Long factoryId;
        private String factoryName;
        private Long createdAt;
    }

    // ─── Dòng tổng hợp theo Tên thành phẩm (UI chính kho bán thành phẩm) ───────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SemiFinishedSummaryDto {
        private String productName;
        private String unit;
        private BigDecimal totalQuantity; // tổng kg còn lại, chưa chuyển kho thành phẩm
        private int lotCount;
        private List<SemiFinishedLotDto> lots; // theo thứ tự FIFO (batch cũ nhất trước)
    }

    // ─── Lô kho Scrap (1 dòng, gắn 1 batch) ────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ScrapLotDto {
        private Long id;
        private String productName;
        private String unit;
        private BigDecimal quantity;
        private String reason;
        private String batchCode;
        private Long batchId;
        private Long factoryId;
        private String factoryName;
        private Long createdAt;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bước 2: Phiếu chuyển kho (Kho bán thành phẩm → Kho thành phẩm)
    // ═══════════════════════════════════════════════════════════════════════

    /** 1 dòng sản phẩm muốn chuyển — chỉ cần tên + tổng kg, hệ thống tự trừ FIFO theo batch */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TransferLineRequest {
        private String productName;
        private BigDecimal quantity; // kg muốn chuyển
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateTransferNoteRequest {
        private String notes;
        private List<TransferLineRequest> lines;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TransferSourceBatchDto {
        private Long batchId;
        private String batchCode;
        private BigDecimal quantity; // kg lấy từ batch này
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TransferNoteLineDto {
        private Long id;
        private String productName;
        private String unit;
        private BigDecimal transferredQty;
        // Đã xác nhận nhận (Bước 3+4) — null nếu phiếu còn PENDING
        private BigDecimal packagedQty;
        private String packagedUnit;
        private BigDecimal actualReceivedWeight;
        private BigDecimal lossQty;
        /** ID biên bản hao hụt tương ứng (nếu lossQty > 0 và đã được lập) — dùng để FE gọi export trực tiếp */
        private Long lossReportId;
        // Ước tính số gói dự kiến theo định lượng đóng gói chuẩn của recipe (nếu có) — chỉ tham khảo
        private BigDecimal estimatedPackagedQty;
        private List<TransferSourceBatchDto> sourceBatches;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TransferNoteDto {
        private Long id;
        private String noteCode;
        private String status; // PENDING | RECEIVED
        private String createdByName;
        private Long createdAt;
        private String notes;
        private String receivedByName;
        private Long receivedAt;
        private String receiveNotes;
        private Long factoryId;
        private String factoryName;
        private List<TransferNoteLineDto> lines;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bước 3: Kế toán kho xác nhận nhận — nhập số lượng đóng gói + cân thực tế
    // ═══════════════════════════════════════════════════════════════════════

    /** Xác nhận nhận cho 1 dòng trong phiếu — theo đúng thứ tự/id dòng trong phiếu */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ReceiveTransferLineRequest {
        private Long lineId;
        /** Số lượng đóng gói thực tế đếm được (VD: 59 túi) */
        private BigDecimal packagedQty;
        /** Tổng trọng lượng thực cân của tất cả gói đã đóng (kg) — BẮT BUỘC, dùng để tính hao hụt */
        private BigDecimal actualReceivedWeight;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ReceiveTransferNoteRequest {
        private String notes;
        private List<ReceiveTransferLineRequest> lines;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bước 4: Biên bản hao hụt đóng gói
    // ═══════════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PackagingLossReportDto {
        private Long id;
        private String reportCode;
        private Long transferNoteId;
        private String transferNoteCode;
        private String productName;
        private BigDecimal transferredQty;
        private BigDecimal actualReceivedWeight;
        private BigDecimal lossQty;
        private BigDecimal packagedQty;
        private String packagedUnit;
        private String sourceBatchesSnapshot;
        private String recordedByName;
        private Long createdAt;
    }
}
