package com.nhatnam.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Tất cả DTO cho Restaurant Dashboard.
 * Dùng projection / JPQL để tối ưu query, không load entity.
 */
public class DashboardDto {

    // ══════════════════════════════════════════════════════════
    // TOP-LEVEL RESPONSE
    // ══════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RestaurantDashboard {
        private OrderSummary          orderSummary;
        private RevenueSummary        revenueSummary;
        private CustomerSummary       customerSummary;
        private PaymentBreakdown      paymentBreakdown;
        private List<TopProduct>      topProducts;
        private List<TopIngredient>   topIngredients;
        private List<TopCustomer>     topCustomers;
        private List<TopUser>         topUsers;
        private List<OrderByTime>     ordersByTime;
        private List<RegionBreakdown> regionBreakdown;
        private List<RecentOrder>     recentOrders;
    }

    // ══════════════════════════════════════════════════════════
    // CARDS
    // ══════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderSummary {
        /** Tổng đơn bất kể trạng thái */
        private long totalOrders;
        private long pendingOrders;
        private long confirmedOrders;
        private long preparingOrders;
        private long readyOrders;
        private long deliveringOrders;
        private long completedOrders;
        private long cancelledOrders;
        private long failedOrders;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RevenueSummary {
        /** Doanh thu đơn COMPLETED */
        private BigDecimal completedRevenue;
        /** Doanh thu đơn chưa xong (PENDING/CONFIRMED/PREPARING/READY/DELIVERING) */
        private BigDecimal pendingRevenue;
        /** Tổng discount đã áp dụng */
        private BigDecimal totalDiscount;
        /** Tổng VAT */
        private BigDecimal totalVat;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CustomerSummary {
        /** Số khách mua lần đầu (1 đơn) */
        private long newCustomers;
        /** Số khách mua từ lần 2 trở lên */
        private long returningCustomers;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PaymentBreakdown {
        /** Dynamic list — thêm payment method mới chỉ cần thêm item, UI tự render */
        private List<PaymentMethodItem> methods;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PaymentMethodItem {
        /** Tên kỹ thuật: CASH, BANK_TRANSFER, MOMO, VNPAY, ... */
        private String     method;
        /** Tên hiển thị tiếng Việt */
        private String     label;
        private BigDecimal amount;
        private long       count;
    }

    // ══════════════════════════════════════════════════════════
    // TOP LISTS
    // ══════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopProduct {
        private Long       productId;
        private String     productName;
        private String     productImageUrl;
        /** Tổng số lượng đã bán */
        private BigDecimal totalQuantity;
        /** Tổng doanh thu từ sản phẩm này (đơn COMPLETED) */
        private BigDecimal totalRevenue;
        private long       orderCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopIngredient {
        private Long       ingredientId;
        private String     ingredientName;
        private String     unit;
        /** Tổng số lượng nguyên liệu đã được dùng */
        private BigDecimal totalQuantityUsed;
        private long       usageCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopCustomer {
        private Long       customerId;
        private String     customerName;
        private String     customerPhone;
        private long       orderCount;
        private BigDecimal totalSpent;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopUser {
        private Long   userId;
        private String userName;
        private String fullName;
        private long   orderCount;
        private BigDecimal totalRevenue;
    }

    // ══════════════════════════════════════════════════════════
    // TIME SERIES
    // ══════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderByTime {
        /** Label: "2024-01-15" / "2024-W03" / "2024-01" / "2024" */
        private String     timeBucket;
        private long       orderCount;
        private BigDecimal revenue;
    }

    // ══════════════════════════════════════════════════════════
    // REGION
    // ══════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RegionBreakdown {
        /** Tỉnh / thành phố trích từ shippingAddress */
        private String region;
        private long   orderCount;
        private BigDecimal revenue;
    }

    // ══════════════════════════════════════════════════════════
    // RECENT ORDERS
    // ══════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecentOrder {
        private Long       orderId;
        private String     orderCode;
        private String     customerName;
        private Long       createdAt;
        private BigDecimal totalAmount;
        private BigDecimal discountAmount;
        private BigDecimal vatAmount;
        private BigDecimal finalAmount;
        private String     status;
        private String     paymentStatus;
    }

    // ══════════════════════════════════════════════════════════
    // REQUEST (filter)
    // ══════════════════════════════════════════════════════════

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DateRangeFilter {
        /** epoch-ms */
        private Long fromTs;
        /** epoch-ms, inclusive */
        private Long toTs;
    }
}