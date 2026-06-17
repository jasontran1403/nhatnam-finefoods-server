package com.nhatnam.server.dto.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemDto {
    private Long id;
    private Long productId;
    private String productName;
    private String productImageUrl;
    private String unit;
    private BigDecimal quantity;
    private BigDecimal basePrice;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
    private BigDecimal vatAmount;
    private Integer vatRate;
    private String vatMode;
    private Integer discountPercent;
    private String tierName;
    private String notes;
}
