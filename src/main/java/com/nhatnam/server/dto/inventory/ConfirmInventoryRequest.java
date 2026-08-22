package com.nhatnam.server.dto.inventory;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * Xác nhận (kiểm kê) số lượng thực tế của NHIỀU nguyên liệu trong 1 kho → tạo
 * một phiếu điều chỉnh gồm nhiều dòng.
 */
@Data
public class ConfirmInventoryRequest {
    private Long warehouseId;
    private String note;
    private List<Item> items;

    @Data
    public static class Item {
        private Long ingredientId;
        private BigDecimal countedQuantity;
    }
}
