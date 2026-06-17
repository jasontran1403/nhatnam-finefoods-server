package com.nhatnam.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSubCategoryRequest {

    @NotBlank(message = "Tên danh mục con không được để trống")
    @Size(max = 100, message = "Tên tối đa 100 ký tự")
    private String name;

    @NotNull(message = "categoryId không được để trống")
    private Long categoryId;

    private String imageUrl;
}