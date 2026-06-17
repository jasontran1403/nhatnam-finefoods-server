package com.nhatnam.server.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopSellerDto {
    private Long userId;
    private String username;
    private String fullName;
    private Long totalCompletedOrders;
    private BigDecimal totalRevenue;
}
