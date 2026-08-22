package com.nhatnam.server.dto.cashflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Một dòng kiểm đếm tiền mặt theo mệnh giá.
 *
 * <p>{@code amount = denomination × quantity} — server tự tính, client không cần gửi.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CashDenominationDto {
    /** Mệnh giá (VNĐ): 200 … 500000 — phải thuộc {@code CashDenominations.ALLOWED}. */
    private Long denomination;
    /** Số tờ/số lượng đếm được (>= 0). */
    private Integer quantity;
    /** Thành tiền của mệnh giá này = denomination × quantity (server tính). */
    private BigDecimal amount;
}
