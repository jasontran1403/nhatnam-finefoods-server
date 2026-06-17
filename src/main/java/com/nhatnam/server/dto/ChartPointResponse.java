package com.nhatnam.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChartPointResponse {
    private String label;
    private long bucketFrom;
    private long bucketTo;
    private long pendingCount;
    private long successCount;
    private BigDecimal pendingRevenue;
    private BigDecimal successRevenue;
}
