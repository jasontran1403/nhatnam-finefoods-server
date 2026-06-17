package com.nhatnam.server.dto;

import java.util.List;

public class PosChartDto {

    // Chart 1 & 2 — period-based point (thay thế MonthlyShiftPoint)
    public record PeriodShiftPoint(
            String periodLabel,   // "14/04", "T3/26", "Kỳ 1"...
            long   periodFromTs,  // start của kỳ này (ms)
            long   periodToTs,    // end của kỳ này (ms)
            int    shift,         // 0=tất cả, 1,2,3
            String shiftLabel,
            double revenue,
            double orderCount
    ) {}

    // Chart 2 stacked — period-based
    public record PeriodStackedPoint(
            String periodLabel,
            long   periodFromTs,
            long   periodToTs,
            String groupKey,
            String groupType,     // "SHIFT" | "CATEGORY"
            double revenue,
            double orderCount
    ) {}

    // Heatmap — giữ nguyên
    public record HeatmapCell(
            String date,
            String dayLabel,
            int    hour,
            String hourLabel,
            double    orderCount,
            double totalRevenue
    ) {}

    public record CategoryItem(Long id, String name) {}

    // Enum đơn vị kỳ
    public enum PeriodUnit {
        DAY, WEEK, MONTH_30, MONTH_3, MONTH_6, YEAR, CUSTOM
    }
}