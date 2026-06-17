package com.nhatnam.server.dto.analytics;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

public class AnalyticsDtos {

    public enum AnalyticsPeriod { WEEK, MONTH, QUARTER, YEAR }

    // ── Một điểm dữ liệu trên biểu đồ ──────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DataPoint {
        private String label;
        private Long   fromMs;
        private Long   toMs;
        private BigDecimal revenue;
        private BigDecimal income;
        private BigDecimal expense;
        private BigDecimal profit;
        private Long   orderCount;
    }

    // ── Dự đoán kỳ tiếp theo ────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ForecastDto {
        private String periodLabel;
        private Long   fromMs;
        private Long   toMs;
        private BigDecimal forecastRevenue;
        private BigDecimal forecastIncome;
        private BigDecimal forecastExpense;
        private BigDecimal forecastProfit;
        private Long   forecastOrderCount;
        private String method;
        private Double confidence;
    }

    // ── Dự đoán nguyên liệu cần đặt ─────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class IngredientForecastDto {
        private Long   ingredientId;
        private String ingredientName;
        private String unit;
        private BigDecimal forecastQty;
        private BigDecimal avgQtyPerPeriod;
        private BigDecimal trendFactor;
    }

    // ── Response tổng hợp ───────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BusinessAnalyticsResponse {
        private AnalyticsPeriod period;
        private List<DataPoint> historical;
        private ForecastDto     forecast;
        private List<IngredientForecastDto> ingredientForecast;

        private BigDecimal currentRevenue;
        private BigDecimal currentIncome;
        private BigDecimal currentExpense;
        private BigDecimal currentProfit;
        private Long       currentOrderCount;

        private Double revenuePctChange;
        private Double profitPctChange;
        private Double orderCountPctChange;
    }
}