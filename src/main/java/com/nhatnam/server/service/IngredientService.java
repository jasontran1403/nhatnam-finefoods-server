package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.CreateIngredientRequest;
import com.nhatnam.server.dto.response.IngredientResponse;

import java.util.List;

public interface IngredientService {
    /**
     * Tạo nguyên liệu mới
     */
    IngredientResponse createIngredient(CreateIngredientRequest request);

    /**
     * Cập nhật nguyên liệu
     */
    IngredientResponse updateIngredient(Long id, CreateIngredientRequest request);

    /**
     * Xóa nguyên liệu (soft delete)
     */
    void deleteIngredient(Long id);

    /**
     * Lấy tất cả nguyên liệu
     */

    List<IngredientResponse> getAllIngredients();
    List<IngredientResponse> getPaginationIngredients(int page, int size);

    /**
     * Lấy nguyên liệu theo ID
     */
    IngredientResponse getIngredientById(Long id);
}