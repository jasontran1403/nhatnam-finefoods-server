package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class OrderListResponse {
    private Long id;
    private String orderCode;
    private String customerName;      // Người đặt
    private String customerPhone;
    private String receiverName;      // Người nhận (nếu khác)
    private String receiverPhone;
    private BigDecimal finalAmount;
    private Long createdAt;
    private String status;            // PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
}