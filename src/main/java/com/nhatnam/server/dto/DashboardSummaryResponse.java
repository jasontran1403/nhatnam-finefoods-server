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
    /** Đang xử lý = Σ round(final) của PREPARING + DELIVERING. */
    private BigDecimal processingAmount;
    /** Đã hủy = Σ round(final) của CANCELLED + FAILED. */
    private BigDecimal cancelledAmount;

    // ── Card 3 & 4: Công nợ ───────────────────────────────────────────────
    private BigDecimal nearingDeadlineAmount;
    private BigDecimal overdueAmount;

    // ── Hàng card mới: phân tuổi nợ (aging) theo ngày kể từ khi tạo đơn ────
    private BigDecimal aging0to30;
    private BigDecimal aging31to60;
    private BigDecimal aging61to90;
    private BigDecimal aging90plus;

    // ── Backward-compat fields ────────────────────────────────────────────
    private long       successOrders;
    private BigDecimal totalPaid;
    private BigDecimal totalDebt;
}