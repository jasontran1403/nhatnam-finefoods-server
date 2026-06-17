package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryResponse {
    private Long id;
    private String name;
    private String imageUrl;
    private Boolean isActive;
    private Long createdAt;
    private Long updatedAt;
}