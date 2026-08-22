package com.nhatnam.server.dto.cashflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CashflowSummaryDto {
    private Long from;
    private Long to;             // đã clamp về hiện tại nếu chọn tương lai

    private CashPositionDto opening;

    private List<CashflowFlowDto> incomes;
    private List<CashflowFlowDto> expenses;
    private BigDecimal incomeCashTotal;
    private BigDecimal incomeBankTotal;
    private BigDecimal expenseCashTotal;
    private BigDecimal expenseBankTotal;

    private CashPositionDto closing;

    /** Các lần xác nhận nằm trong kỳ (marker "đã xác nhận dòng tiền"). */
    private List<CashflowConfirmationDto> confirmations;
}
