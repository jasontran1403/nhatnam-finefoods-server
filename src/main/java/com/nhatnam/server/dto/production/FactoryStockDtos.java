package com.nhatnam.server.dto.production;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTOs cho nghiệp vụ XUẤT / CHUYỂN kho nguyên liệu xưởng (Mục 2).
 */
public class FactoryStockDtos {

    // ─── Kho đích (dropdown hợp nhất "Tên kho — Loại kho") ────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockTargetDto {
        /**
         * Khoá đích mã hoá: "w:<warehouseId>" | "fm:<factoryId>" | "fg:<factoryId>".
         * w  = Warehouse (bán/trung chuyển)
         * fm = kho nguyên liệu xưởng
         * fg = kho thành phẩm xưởng
         */
        private String key;
        private String name;
        /** Nhãn loại kho: "Kho bán" | "Trung chuyển" | "Kho nguyên liệu xưởng" | "Kho xưởng" */
        private String typeLabel;
    }

    // ─── Nguyên liệu chuyển được sang 1 kho đích (giao theo TÊN) ──────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TransferableMaterialDto {
        private String materialName;
        private String unit;
        private BigDecimal availableQuantity;
    }

    // ─── Request xuất kho ────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExportFactoryMaterialRequest {
        private Long factoryId;              // kho nguồn
        private String reason;               // bắt buộc
        private List<String> documentImages; // optional
        private List<Line> items;

        @Data @NoArgsConstructor @AllArgsConstructor
        public static class Line {
            private String materialName;
            private String unit;
            private BigDecimal quantity;
        }
    }

    // ─── Request chuyển kho ──────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TransferFactoryMaterialRequest {
        private Long factoryId;              // kho nguồn (xưởng NL)
        private String targetKey;            // "w:<id>" | "fm:<id>" | "fg:<id>"
        private String note;
        private List<String> documentImages; // optional
        private List<Line> items;

        @Data @NoArgsConstructor @AllArgsConstructor
        public static class Line {
            private String materialName;
            private String unit;
            private BigDecimal quantity;
        }
    }

    // ─── Lịch sử phiếu ───────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryStockNoteDto {
        private Long id;
        private String noteCode;
        private String type;       // IMPORT | EXPORT | TRANSFER_OUT | TRANSFER_IN
        private String typeLabel;
        private Long factoryId;
        private String factoryName;
        private String targetName;
        private String targetTypeLabel;
        private String reason;
        private List<String> documentImages;
        private String createdByName;
        private Long createdAt;
        private List<LineDto> lines;

        @Data @Builder @NoArgsConstructor @AllArgsConstructor
        public static class LineDto {
            private String materialName;
            private String unit;
            private BigDecimal quantity;
            private Long expiryDate;
        }
    }
}
