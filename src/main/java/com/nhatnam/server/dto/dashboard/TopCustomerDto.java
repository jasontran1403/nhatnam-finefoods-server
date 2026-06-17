package com.nhatnam.server.dto.dashboard;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class TopCustomerDto {
    private Long       customerId;
    private String     customerName;
    private Long       totalOrders;      // tổng đơn trong kỳ (mọi trạng thái)
    private Long       completedOrders;  // số đơn COMPLETED
    private BigDecimal totalSpent;       // tổng final_amount chỉ đơn COMPLETED
}