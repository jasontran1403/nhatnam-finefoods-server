package com.nhatnam.server.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/** Số liệu nguyên liệu tại 1 kho: đầu kỳ · nhập · bán · xuất · cuối kỳ. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InventoryCellDto {
    private Long warehouseId;
    private BigDecimal opening;
    private BigDecimal nhap;   // IMPORT + TRANSFER_IN + ADJUST(+)
    private BigDecimal ban;    // EXPORT_ORDER
    private BigDecimal xuat;   // EXPORT_OTHER + TRANSFER_OUT + ADJUST(-)
    private BigDecimal closing;
}
