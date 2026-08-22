package com.nhatnam.server.dto.cashflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BankAccountDto {
    private Long id;
    private String name;
    private String accountNumber;
}
