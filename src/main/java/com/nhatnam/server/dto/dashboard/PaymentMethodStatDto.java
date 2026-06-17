package com.nhatnam.server.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodStatDto {
    private String method;
    private Long orderCount;
    private BigDecimal revenue;
}
