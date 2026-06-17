package com.nhatnam.server.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

public class PosDashboardDto {

    // ══════════════════════════════════════════════════════════
    // POS DASHBOARD (PosOrder entity)
    // ══════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PosDashboard {
        private PosOrderSummary      orderSummary;
        private PosRevenueSummary    revenueSummary;
        private PosPaymentBreakdown  paymentBreakdown;
        private List<PosTopProduct>  topProducts;
        private List<PosOrderByTime> ordersByTime;
        private List<PosRecentOrder> recentOrders;
        private List<PosSourceStat> sourceStats;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PosOrderSummary {
        private long offlineOrders;
        private long shopeeFoodOrders;
        private long grabFoodOrders;
        private long totalOrders;
        private long completedOrders;
        private long cancelledOrders;
        private long pendingOrders;

        // Breakdown theo source × status (không tính CANCELLED)
        private long offlineOrdersCompleted;
        private long offlineOrdersPending;
        private long shopeeFoodOrdersCompleted;
        private long shopeeFoodOrdersPending;
        private long grabFoodOrdersCompleted;
        private long grabFoodOrdersPending;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PosRevenueSummary {
        private BigDecimal totalRevenue;
        private BigDecimal offlineRevenue;
        private BigDecimal shopeeFoodRevenue;
        private BigDecimal grabFoodRevenue;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PosPaymentBreakdown {
        private List<PosPaymentMethodItem> methods;
        // Pie: đơn hàng theo Nguồn (orderSource)
        private List<PosPieItem> sourcePieItems;
        // Pie: đơn hàng theo Loại (category: Nóng / Lạnh / Combo)
        private List<PosPieItem> categoryPieItems;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PosSourceStat {
        private String     source;        // "TAKE_AWAY" | "DINE_IN" | "SHOPEE_FOOD" | "GRAB_FOOD"
        private long       totalItems;    // tổng số sản phẩm (sum quantity từ PosOrderItem)
        private long       totalOrders;   // tổng số đơn hàng
        private BigDecimal totalRevenue;  // tổng tiền (tất cả trạng thái, hoặc tuỳ bạn — xem note)
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PosPieItem {
        private String label;   // "Offline" / "ShopeeFood" / "GrabFood" / "Nóng" / "Lạnh" / "Combo"
        private String key;     // "OFFLINE" / "SHOPEE_FOOD" / "GRAB_FOOD" / "HOT" / "COLD" / "COMBO"
        private long       count;      // số đơn hàng
        private long       itemCount;  // số sản phẩm (SUM quantity)
        private BigDecimal amount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PosPaymentMethodItem {
        private String     method;
        private String     label;
        private BigDecimal amount;
        private long       count;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PosTopProduct {
        private Long       productId;
        private String     productName;
        private String     productImageUrl;
        private BigDecimal totalQuantity;
        private BigDecimal totalRevenue;
        private long       orderCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PosOrderByTime {
        private String     timeBucket;
        private long       orderCount;
        private BigDecimal revenue;
        private BigDecimal aov;
        private long       itemCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PosRecentOrder {
        private Long       orderId;
        private String     orderCode;
        private String     orderSource;
        private Long       createdAt;
        private BigDecimal totalAmount;
        private BigDecimal finalAmount;
        private String     status;
        private String     paymentMethod;
        private String     paymentStatus;
    }

    // ══════════════════════════════════════════════════════════
    // SELLER DASHBOARD (Order entity — Wholesale + Retail)
    // Endpoint: GET /api/admin/dashboard/seller?type=WHOLESALE|RETAIL|ALL
    // ══════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SellerDashboard {
        private SellerOrderSummary        orderSummary;
        private SellerRevenueSummary      revenueSummary;
        private SellerPaymentBreakdown    paymentBreakdown;
        private List<SellerTopProduct>    topProducts;
        private List<SellerOrderByTime>   ordersByTime;
        private List<SellerRecentOrder>   recentOrders;
    }

    /**
     * Đếm đơn theo trạng thái + phân loại WHOLESALE/RETAIL
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SellerOrderSummary {
        private long totalOrders;
        private long completedOrders;
        private long cancelledOrders;
        private long pendingOrders;
        private long wholesaleOrders;   // type = WHOLESALE
        private long retailOrders;      // type = RETAIL
    }

    /**
     * Doanh thu = SUM(finalAmount) của đơn COMPLETED.
     * finalAmount = totalAmount - discountAmount + vatAmount
     * → đây là số tiền khách thực sự trả.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SellerRevenueSummary {
        private BigDecimal totalRevenue;       // tất cả type
        private BigDecimal wholesaleRevenue;   // type = WHOLESALE
        private BigDecimal retailRevenue;      // type = RETAIL
        private BigDecimal totalDiscount;      // tổng discount đã giảm
        private BigDecimal totalVat;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SellerPaymentBreakdown {
        private List<SellerPaymentMethodItem> methods;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SellerPaymentMethodItem {
        private String     method;
        private String     label;
        private long       count;
        private BigDecimal amount;
    }

    /**
     * Top sản phẩm:
     * totalRevenue = SUM(oi.subtotal)
     *              = SUM(unitPrice × quantity)
     *              = giá thực sau discount × số lượng
     * Ví dụ: basePrice=45000, discount=10% → unitPrice=40500
     *        subtotal = 40500 × 1 = 40500 ✓
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SellerTopProduct {
        private Long       productId;
        private String     productName;
        private String     productImageUrl;
        private BigDecimal totalQuantity;
        private BigDecimal totalRevenue;   // SUM(oi.subtotal) — giá sau discount
        private long       orderCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SellerOrderByTime {
        private String     timeBucket;
        private long       orderCount;
        private BigDecimal revenue;        // SUM(finalAmount) — giá sau cùng
    }

    /**
     * Đơn hàng gần đây:
     * - totalAmount  = tổng trước discount (hiển thị gạch ngang nếu có discount)
     * - discountAmount = số tiền đã giảm
     * - finalAmount  = số tiền thực trả ← hiển thị cột "Tổng tiền"
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SellerRecentOrder {
        private Long       orderId;
        private String     orderCode;
        private String     customerName;
        private Long       createdAt;
        private BigDecimal totalAmount;     // trước discount
        private BigDecimal discountAmount;  // số tiền giảm
        private BigDecimal finalAmount;     // sau discount+VAT ← dùng cái này
        private String     status;
        private String     paymentStatus;
        private String     type;            // WHOLESALE | RETAIL
    }

    // ══════════════════════════════════════════════════════════
    // DATE RANGE FILTER
    // ══════════════════════════════════════════════════════════

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class DateRangeFilter {
        private Long fromTs;
        private Long toTs;
    }
}