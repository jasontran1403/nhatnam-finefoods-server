package com.nhatnam.server.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO tổng hợp dữ liệu Dashboard cho SELLER.
 * Mỗi inner-class tương ứng 1 query tối ưu.
 */
public class SellerDashboardDTO {

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Thẻ tóm tắt tổng quan — 4 card mới
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Summary {

        // ── Card 1: Tổng đơn hàng ─────────────────────────────────────────
        /** Tổng đơn (không tính CANCELLED) */
        private long totalOrders;
        /** Đang chuẩn bị (PREPARING) */
        private long preparingOrders;
        /** Đang giao hàng (DELIVERING) */
        private long deliveringOrders;
        /** Chờ thanh toán (PENDING_PAYMENT) */
        private long pendingPaymentOrders;
        /** Đã hoàn thành (COMPLETED) */
        private long completedOrders;

        // ── Card 2: Tổng doanh thu ────────────────────────────────────────
        /**
         * Tổng doanh thu = finalAmount của TẤT CẢ đơn không bị hủy
         * (PREPARING + DELIVERING + PENDING_PAYMENT + COMPLETED)
         */
        private BigDecimal totalRevenue;
        /**
         * Doanh thu đã thu = finalAmount của đơn COMPLETED
         * (giả định COMPLETED = đã thu đủ tiền)
         */
        private BigDecimal collectedRevenue;
        /**
         * Doanh thu chưa thu = totalRevenue - collectedRevenue
         * (PREPARING + DELIVERING + PENDING_PAYMENT)
         */
        private BigDecimal uncollectedRevenue;

        // ── Card 3 & 4: Công nợ — placeholder, xử lý sau ─────────────────
        /** Tổng tiền công nợ gần đến hạn (placeholder) */
        private BigDecimal nearingDeadlineAmount;
        /** Tổng tiền công nợ quá hạn (placeholder) */
        private BigDecimal overdueAmount;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Dữ liệu chart (theo HOUR / DAY / MONTH)
    //    Chỉ tính PREPARING + DELIVERING + PENDING_PAYMENT + COMPLETED
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ChartPoint {
        /** Nhãn trục X: "08:00", "15/06", "T6/2025" */
        private String     label;
        /** Số đơn chưa hoàn thành (PREPARING + DELIVERING + PENDING_PAYMENT) */
        private long       pendingCount;
        /** Số đơn hoàn thành (COMPLETED) */
        private long       successCount;
        /** Doanh thu đơn chưa hoàn thành */
        private BigDecimal pendingRevenue;
        /** Doanh thu đơn hoàn thành */
        private BigDecimal successRevenue;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Top sản phẩm bán chạy
    //    Tính từ PREPARING + DELIVERING + PENDING_PAYMENT + COMPLETED
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopProduct {
        private Long       productId;
        private String     productName;
        private String     imageUrl;
        /** Tổng số lượng bán được */
        private BigDecimal totalQty;
        /** Tổng doanh thu từ sản phẩm này */
        private BigDecimal totalRevenue;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Top khách hàng chi tiêu nhiều nhất
    //    Tính từ PREPARING + DELIVERING + PENDING_PAYMENT + COMPLETED
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopCustomer {
        private Long       customerId;
        private String     customerName;
        private String     customerPhone;
        /** Tổng số đơn (không tính CANCELLED) */
        private long       totalOrders;
        /** Số đơn hoàn thành (trạng thái COMPLETED) */
        private long       completedOrders;
        /**
         * Số đơn ĐÃ THANH TOÁN ĐỦ (paymentStatus = PAID).
         *
         * <p>Khác {@link #completedOrders}: đơn có thể đã thu đủ tiền nhưng chưa
         * giao xong, hoặc đã giao xong mà còn nợ. Cột "Đơn" trên dashboard hiển
         * thị tổng/đã thanh toán nên phải dùng con số này.
         */
        private long       paidOrders;
        /** Tổng chi tiêu = finalAmount của tất cả đơn không bị hủy */
        private BigDecimal totalSpent;
        /**
         * TỔNG TIỀN ĐÃ THU THỰC TẾ, cộng cả phần thu một phần.
         *
         * <p>Lấy từ {@code order.paidAmount} chứ không suy ra từ trạng thái: đơn
         * PARTIAL đã thu 3/10 triệu thì phải cộng đúng 3 triệu, còn nếu chỉ đếm
         * đơn PAID thì khoản đó biến mất khỏi báo cáo.
         */
        private BigDecimal collectedAmount;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Wrapper response duy nhất (gọi 1 lần /api/seller/dashboard)
    // ─────────────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DashboardResponse {
        private Summary           summary;
        private List<ChartPoint>  chart;
        private List<TopProduct>  topProducts;
        private List<TopCustomer> topCustomers;
    }
}