package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.ProductionDtos.*;
import com.nhatnam.server.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Biến thể sản xuất (ProductionRecipe) — phía OWNER chỉ XEM và bật/tắt (active/inactive).
 * Việc TẠO/SỬA biến thể do FACTORY_WORKER thực hiện, xem ProductionBatchController
 * (route /api/factory/recipes).
 */
@RestController
@RequestMapping("/api/owner/factory/recipes")
@RequiredArgsConstructor
public class ProductionRecipeController {

    private final ProductionService productionService;

    @GetMapping
    public ApiResponse<List<ProductionRecipeDto>> list(
            @RequestParam(required = false) Long productId) {
        return ApiResponse.ok(productionService.listRecipes(productId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductionRecipeDto> get(@PathVariable Long id) {
        return ApiResponse.ok(productionService.getRecipe(id));
    }

    @PatchMapping("/{id}/toggle")
    public ApiResponse<Void> toggle(@PathVariable Long id, @RequestParam boolean active) {
        productionService.toggleRecipe(id, active);
        return ApiResponse.ok(null);
    }
}
