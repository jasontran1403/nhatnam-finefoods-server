// src/main/java/com/nhatnam/server/dto/PosOrderExportDto.java
package com.nhatnam.server.dto;

import java.math.BigDecimal;

public record PosOrderExportDto(Long storeId, String storeName, String storeAddress, String storePhone, Long shiftId,
                                String shiftStaffName, Long shiftOpenTime, Long shiftCloseTime, Long orderId,
                                String orderCode, String customerName, String customerPhone, BigDecimal totalAmount,
                                BigDecimal finalAmount, String orderSource, String paymentMethod, Long createdAt,
                                String categoryName, String productName, BigDecimal basePrice, BigDecimal finalUnitPrice,
                                BigDecimal discountPercent, Integer quantity, BigDecimal vatAmount) {

    // ── Computed helpers ──────────────────────────────────────────
    public double getDiscount() {
        if (totalAmount == null || finalAmount == null) return 0;
        return totalAmount.subtract(finalAmount).doubleValue();
    }

    public double getVat() {
        return vatAmount != null ? vatAmount.doubleValue() : 0;
    }

    public boolean hasItem() {
        return productName != null;
    }
}