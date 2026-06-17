package com.nhatnam.server.dto.income;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaleOrderConflictResponse {
    private String orderCode;
    private BigDecimal actualRemainingAmount;
    private BigDecimal paidAmount;
    private String message;
}
