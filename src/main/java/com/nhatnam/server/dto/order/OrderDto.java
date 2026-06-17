package com.nhatnam.server.dto.order;

import com.nhatnam.server.dto.response.OrderResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDto {
    private Long id;
    private String orderCode;

    // Seller (user)
    private Long userId;
    private String userName;
    private String fullName;

    // Customer
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String customerType;

    private BigDecimal paidAmount;

    // Company info
    private String companyName;
    private String shortName;
    private String taxCode;
    private String contactName;
    private String companyPhone;
    private String companyAddress;

    private List<OrderLogDto> logs;

    // Address
    private String shippingAddress;
    private String deliveryAddress;
    private String orderedByName;
    @Builder.Default
    private List<DriverInfo> drivers = new java.util.ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> deliveryInfo = new java.util.ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriverInfo {
        private Long   id;
        private String name;
    }

    // Amounts
    private BigDecimal subtotal;
    private BigDecimal surcharge;
    private BigDecimal discountAmount;
    private Integer discountRate;
    private String vatRate;
    private BigDecimal vatAmount;
    private BigDecimal totalAmount;
    private BigDecimal finalAmount;

    // Status
    private String status;
    private String paymentStatus;
    private String paymentMethod;

    // Warehouse
    private String warehouseName;
    private Long warehouseId;

    // Meta
    private String type;
    private String notes;
    private Long createdAt;
    private Long updatedAt;

    // Items
    private List<OrderItemDto> items;
}
