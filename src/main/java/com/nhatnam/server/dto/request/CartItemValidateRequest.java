package com.nhatnam.server.dto.request;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartItemValidateRequest {
    private Long productId;
    private String productName;
    private String unit;
    private String imageUrl;
    private Long priceId;
    private String priceName;
    private BigDecimal price;
    private Long variantId;     // ← Thêm
    private String variantName; // ← Thêm
    private String saleType;    // "BOX" | "RETAIL"
    private Integer unitsPerBox;
}