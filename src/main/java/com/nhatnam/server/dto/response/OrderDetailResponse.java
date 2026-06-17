package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class OrderDetailResponse {
    private Long id;
    private String orderCode;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String shippingAddress;   // Địa chỉ giao (có thể bao gồm tên người nhận)
    private String notes;
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal finalAmount;
    private String status;
    private String paymentStatus;
    private String paymentMethod;
    private Long createdAt;
    private Long updatedAt;

    private List<OrderItemDetail> items;
}
