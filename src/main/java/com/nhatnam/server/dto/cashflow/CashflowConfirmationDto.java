package com.nhatnam.server.dto.cashflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CashflowConfirmationDto {
    private Long id;
    private Long confirmedAt;
    private String confirmedByName;
    private String confirmedByRole;
    private Boolean matched;
    private String reason;
    private BigDecimal cashCounted;
    /** Chi tiết kiểm đếm theo mệnh giá — rỗng nếu phiếu cũ nhập tổng trực tiếp. */
    private List<CashDenominationDto> cashDenominations;
    private List<BankBalanceDto> banks;         // số kiểm đếm mỗi TK
    private BigDecimal expectedCash;
    private List<BankBalanceDto> expectedBanks; // số hệ thống tại thời điểm đó
}
