package com.nhatnam.server.dto.order;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request trả hàng do feedback xấu.
 * Trường hợp 1: trả phần chưa sử dụng, phần đã dùng note "Tặng khách hàng" (0 đ).
 */
@Data
public class OrderReturnRequest {

    /** Lý do trả hàng */
    private String reason;

    /** Danh sách item trả */
    private List<ReturnItemRequest> items;

    @Data
    public static class ReturnItemRequest {
        /** ID của order item */
        private Long orderItemId;
        /** Số lượng đã sử dụng (phần tặng khách, ko tính tiền) */
        private BigDecimal usedQuantity;
        /** Số lượng trả lại kho */
        private BigDecimal returnQuantity;
    }
}
