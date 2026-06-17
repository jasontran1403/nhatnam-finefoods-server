package com.nhatnam.server.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * Phiếu kiểm kho: user nhập số lượng thực tế từng nguyên liệu.
 * Service sẽ so sánh với stockQuantity hiện tại và tạo log ADJUST nếu lệch.
 */
@Data
public class StockCheckRequest {

    private List<CheckItem> items;

    @Data
    public static class CheckItem {
        private Long ingredientId;
        /** Số lượng thực tế user đếm được */
        private BigDecimal actualQuantity;
    }
}