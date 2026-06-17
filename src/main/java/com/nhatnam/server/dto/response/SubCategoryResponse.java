package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SubCategoryResponse {
    private Long id;
    private String name;
    private String imageUrl;
    private Long categoryId;
    private String categoryName;   // populated when fetching with join
    private Boolean isActive;
    private Long createdAt;
    private Long updatedAt;
}