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
public class TopProductDto {
    private Long productId;
    private String productName;
    private String productImageUrl;
    private String unit;
    private BigDecimal totalQuantitySold;
    private Long totalOrders;
    private BigDecimal totalRevenue;
}
