package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class InventoryBatchDetailResponse {
    private Long   id;
    private String batchCode;
    private String action;
    private String supplierRef;
    private String receiptImageUrl;
    private List<String> imageUrls;
    private String createdByName;
    private Long   createdAt;

    private List<LogLineResponse> lines;

    @Data
    @Builder
    public static class LogLineResponse {
        private Long       ingredientId;
        private String     ingredientName;
        private String     unit;
        /** Dương = nhập/dư, Âm = xuất/thiếu, Zero = khớp */
        private BigDecimal quantity;
        private BigDecimal quantityBefore;
        private BigDecimal quantityAfter;
        /**
         * Chỉ dùng cho ADJUST:
         * "MATCH" | "SHORTAGE" | "SURPLUS"
         */
        private String     adjustStatus;
    }
}