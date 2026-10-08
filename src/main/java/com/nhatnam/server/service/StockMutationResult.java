package com.nhatnam.server.service;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Kết quả 1 thao tác đổi tồn kho. Các giá trị before/after được đọc TỪ DB sau
 * khi UPDATE atomic đã commit (không phải RAM snapshot của caller) → an toàn
 * dùng làm quantityBefore/quantityAfter trên WarehouseReceiptItem.
 */
@Data
@AllArgsConstructor
public class StockMutationResult {
    private Long ingredientId;
    private Long warehouseId;
    private BigDecimal before;   // tồn TRƯỚC khi thao tác
    private BigDecimal after;    // tồn SAU khi thao tác
    private BigDecimal delta;    // = after - before (dương = tăng, âm = giảm)
}