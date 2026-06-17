package com.nhatnam.server.dto.order;

import lombok.Data;

@Data
public class CancelOrderRequest {
    private String reason;
}
