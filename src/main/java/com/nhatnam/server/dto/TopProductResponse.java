package com.nhatnam.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TopProductResponse {
    private Long productId;
    private String productName;
    private String imageUrl;
    @Builder.Default private BigDecimal totalQty = BigDecimal.ZERO;
    @Builder.Default private BigDecimal totalRevenue = BigDecimal.ZERO;
}
