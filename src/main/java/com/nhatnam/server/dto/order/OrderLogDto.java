// src/main/java/com/nhatnam/server/dto/order/OrderLogDto.java
package com.nhatnam.server.dto.order;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderLogDto {
    private String action;
    private String actorName;
    private String actorRole;
    private String note;
    private Long   createdAt;
}