package com.nhatnam.server.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryLogResponse {
    private Long id;
    private String ingredientName;
    private Long createdAt;           // timestamp
    private String purpose;           // "Xuất cho đơn hàng ORD-xxx" hoặc "Nhập kho thủ công"
    private BigDecimal quantity;      // luôn dương
    private String status;            // "Completed" hoặc "Modified" (tùy logic sau này)
    private String unit;  // ← THÊM FIELD NÀY
}