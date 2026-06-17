package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class OrderItemDetail {
    private Long productId;
    private String productName;
    private String productImageUrl;
    private Long variantId;
    private String variantName;
    private String priceName;
    private BigDecimal unitPrice;
    private BigDecimal quantity;
    private BigDecimal subtotal;
    private String unit;               // Đơn vị (kg, cái,...)
    private List<IngredientSnapshot> ingredients;
}
