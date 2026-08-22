package com.nhatnam.server.dto.cashflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

/** Vị thế tiền tại một thời điểm: tiền mặt + tổng chuyển khoản + breakdown theo TK. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CashPositionDto {
    private BigDecimal cash;
    private BigDecimal bankTotal;
    private List<BankBalanceDto> banks;
}
