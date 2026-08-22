package com.nhatnam.server.dto.cashflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ConfirmResultDto {
    private Boolean matched;
    private CashPositionDto expected;   // số hệ thống
    private CashPositionDto counted;    // số kiểm đếm
    private CashflowConfirmationDto confirmation;
}
