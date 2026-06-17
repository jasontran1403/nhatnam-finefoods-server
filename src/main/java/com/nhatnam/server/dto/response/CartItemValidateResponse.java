package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CartItemValidateResponse {
    private Long productId;
    private String status; // UNCHANGED, UPDATED, REMOVED
    private String message;
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