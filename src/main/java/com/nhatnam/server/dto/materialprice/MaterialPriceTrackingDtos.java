package com.nhatnam.server.dto.materialprice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

public class MaterialPriceTrackingDtos {

    /** Một điểm dữ liệu trên chart */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PricePointDto {
        private Long id;
        private String materialName;
        private String unit;
        private BigDecimal unitPrice;
        private BigDecimal quantity;
        private BigDecimal totalAmount;
        private String supplierName;
        private Long createdAt;
    }

    /** Response cho chart data */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PriceChartResponse {
        private String materialName;
        private String unit;
        private List<PricePointDto> points;
    }

    /** Request tạo mới entry */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreatePriceEntryRequest {
        private String materialName;
        private String unit;
        /** Option 1: nhập số lượng + tổng tiền → tự chia */
        private BigDecimal quantity;
        private BigDecimal totalAmount;
        /** Option 2: nhập đơn giá trực tiếp */
        private BigDecimal unitPrice;
        /** Nhà cung cấp / đơn vị cung cấp */
        private String supplierName;
        /** Loại: MATERIAL hoặc CONSUMABLE */
        private String entryType;
    }

    /** Item trong danh sách nguyên liệu */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialItemDto {
        private Long id;
        private String name;
        private String unit;
        /** MATERIAL hoặc CONSUMABLE */
        private String type;
    }
}
