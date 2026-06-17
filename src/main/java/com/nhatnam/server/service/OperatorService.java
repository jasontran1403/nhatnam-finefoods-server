package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.CreateCategoryRequest;
import com.nhatnam.server.dto.request.CreateIngredientRequest;
import com.nhatnam.server.dto.request.CreateCompleteProductRequest;
import com.nhatnam.server.dto.response.CategoryResponse;
import com.nhatnam.server.dto.response.IngredientResponse;
import com.nhatnam.server.entity.ProductBatch;

import java.util.List;
import java.util.Map;

public interface OperatorService {
    // Category CRUD (trực tiếp, không cần duyệt)
    CategoryResponse createCategory(CreateCategoryRequest req);
    CategoryResponse updateCategory(Long id, CreateCategoryRequest req);
    void deleteCategory(Long id);

    // Ingredient CRUD (trực tiếp, không cần duyệt)
    IngredientResponse createIngredient(CreateIngredientRequest req);
    IngredientResponse updateIngredient(Long id, CreateIngredientRequest req);

    // Product: tạo/sửa thông qua batch chờ Admin duyệt
    ProductBatch submitBatch(Long operatorId, String operatorName,
                             ProductBatch.BatchType type,
                             String note,
                             List<Map<String, Object>> items);

    List<ProductBatch> getMyBatches(Long operatorId);
}
