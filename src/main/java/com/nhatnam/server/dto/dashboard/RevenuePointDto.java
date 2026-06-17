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
public class RevenuePointDto {
    private String date;         // yyyy-MM-dd
    private BigDecimal revenue;
    private Long orderCount;
}
