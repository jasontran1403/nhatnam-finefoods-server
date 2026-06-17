package com.nhatnam.server.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDto {
    // Today
    private BigDecimal revenueToday;
    private Long ordersToday;
    private Long newCustomersToday;
    private Long completedOrdersToday;

    // Totals
    private Long totalProducts;
    private Long totalUsers;
    private Long totalCustomers;
    private Long totalActiveOrders;

    // Compare (vs yesterday)
    private BigDecimal revenueYesterday;
    private Double revenueChangePercent;   // (+/-%) so với hôm qua
    private Long ordersYesterday;
    private Double ordersChangePercent;
    private BigDecimal totalPaidAmount;

    // Totals by status
    private Long pendingOrders;
    private Long completedOrdersAllTime;
    private Long cancelledOrdersAllTime;

    // Revenue chart (last 30 days)
    private List<RevenuePointDto> revenueLast30Days;

    // Order status distribution (all time)
    private List<StatusCountDto> ordersByStatus;

    // Revenue by payment method (last 30 days)
    private List<PaymentMethodStatDto> revenueByPaymentMethod;

    // Change 5: Chi phí đã duyệt trong kỳ
    private BigDecimal totalExpenses;
}
