package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BatchCreateResponse {
    private Long   id;
    private String batchCode;
    private String action;
    private int    totalItems;
    private String supplierRef;
    private String receiptImageUrl;
}