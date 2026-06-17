package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.ingredient.IngredientStockRowDto;
import com.nhatnam.server.service.admin.IngredientAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ingredients")
@RequiredArgsConstructor
public class IngredientAdminController {

    private final IngredientAdminService ingredientAdminService;

    /**
     * Danh sách nguyên liệu theo kho (mặc định kho đầu tiên nếu không truyền).
     * Response đã có expiryBadge (NONE | WARNING | DANGER) để FE vẽ badge màu.
     */
    @GetMapping
    public ApiResponse<List<IngredientStockRowDto>> listByWarehouse(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(ingredientAdminService.getStockRows(warehouseId, q));
    }

    @GetMapping("/default-warehouse")
    public ApiResponse<Map<String, Long>> defaultWarehouse() {
        return ApiResponse.ok(Map.of("warehouseId", ingredientAdminService.getDefaultWarehouseId()));
    }
}
