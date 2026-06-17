package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ManualImportResponse {

    /** Mã batch: IS-20260307-0000000001 */
    private String batchCode;

    private int totalItems;
    private String supplierRef;

    /** URL ảnh phiếu giao hàng — optional */
    private String receiptImageUrl;

    private List<ImportItemResult> items;

    @Data
    @Builder
    public static class ImportItemResult {
        private Long ingredientId;
        private String ingredientName;
        private String unit;
        private BigDecimal quantityAdded;
        private BigDecimal quantityBefore;
        private BigDecimal quantityAfter;
        /** Hạn dùng hiển thị sau logic so sánh (epoch-millis) */
        private String logReason;
    }
}