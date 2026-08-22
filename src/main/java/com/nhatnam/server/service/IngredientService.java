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

    /**
     * Danh mục nguyên liệu ĐÃ ĐƯỢC GÁN cho một kho (bảng ingredient_warehouse).
     *
     * <p>Khác {@link #getAllIngredients()} vốn trả về toàn bộ danh mục dùng chung.
     * Màn Quản lý kho chỉ được thấy nguyên liệu thuộc phạm vi kho đang đứng —
     * bản ghi tồn kho cũ còn sót lại sau khi gỡ gán không được coi là đã gán.
     */
    List<IngredientResponse> getAllIngredientsOfWarehouse(Long warehouseId);
    List<IngredientResponse> getPaginationIngredients(int page, int size);

    /**
     * Lấy nguyên liệu theo ID
     */
    IngredientResponse getIngredientById(Long id);
}