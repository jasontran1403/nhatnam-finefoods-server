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
    /** Tổng tiền ĐÃ thu trong kỳ (Σ round(paidAmount) của đơn PENDING_PAYMENT + PAID/PARTIAL). */
    private BigDecimal totalPaidAmount;
    /** Tổng tiền CHƯA thu trong kỳ (đơn PENDING_PAYMENT + UNPAID/PARTIAL; UNPAID→final, PARTIAL→final−paid). */
    private BigDecimal totalUnpaidAmount;
    /** Tổng tiền đơn ĐANG XỬ LÝ trong kỳ = Σ round(final) của PREPARING + DELIVERING. */
    private BigDecimal processingAmount;
    /** Tổng tiền đơn ĐÃ HỦY trong kỳ = Σ round(final) của CANCELLED + FAILED. */
    private BigDecimal cancelledAmount;

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

    // ── Tồn kho nguyên liệu (tổng tất cả các kho) ───────────────────────────────
    /** Tổng tồn kho Kem (Non-Dairy Creams), đơn vị hộp */
    private BigDecimal creamStockQty;
    private BigDecimal creamStockValue;
    /** Tổng tồn kho Gia vị (Herbs, Spices & Condiments), đơn vị kg */
    private BigDecimal spiceStockQty;
    private BigDecimal spiceStockValue;
    /** Tổng tồn kho Xúc xích (Small Goods), đơn vị kg */
    private BigDecimal sausageStockQty;
    private BigDecimal sausageStockValue;
}
