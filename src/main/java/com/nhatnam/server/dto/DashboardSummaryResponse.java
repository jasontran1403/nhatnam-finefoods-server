package com.nhatnam.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO summary cho Accountant Dashboard — 4 card mới.
 * Giữ lại các field cũ (successOrders, totalPaid, totalDebt) để không break
 * các endpoint / màn hình khác đang dùng.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryResponse {

    // ── Card 1: Tổng đơn hàng ─────────────────────────────────────────────
    private long       totalOrders;
    private long       preparingOrders;
    private long       deliveringOrders;
    private long       pendingPaymentOrders;
    private long       completedOrders;

    // ── Card 2: Doanh thu ─────────────────────────────────────────────────
    private BigDecimal totalRevenue;
    private BigDecimal collectedRevenue;
    private BigDecimal uncollectedRevenue;

    // ── Card 3 & 4: Công nợ placeholder ──────────────────────────────────
    private BigDecimal nearingDeadlineAmount;
    private BigDecimal overdueAmount;

    // ── Backward-compat fields ────────────────────────────────────────────
    private long       successOrders;
    private BigDecimal totalPaid;
    private BigDecimal totalDebt;
}