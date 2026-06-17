package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.CreateCategoryRequest;
import com.nhatnam.server.dto.response.CategoryResponse;

import java.util.List;

public interface CategoryService {
    CategoryResponse createCategory(CreateCategoryRequest request);
    CategoryResponse updateCategory(Long id, CreateCategoryRequest request);
    void deleteCategory(Long id);
    CategoryResponse getCategoryById(Long id);
    List<CategoryResponse> getAllCategories();
    List<CategoryResponse> getPaginationCategories(int page, int size);
}