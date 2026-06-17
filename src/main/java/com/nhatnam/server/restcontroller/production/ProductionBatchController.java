package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.ProductionDtos.*;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class ProductionBatchController {

    private final ProductionService productionService;

    // ── Owner: xem tất cả mẻ ─────────────────────────────────────────────────
    @GetMapping("/api/owner/factory/batches")
    public ApiResponse<Page<ProductionBatchDto>> listAllBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(productionService.listBatches(page, size, null));
    }

    @GetMapping("/api/owner/factory/batches/{id}")
    public ApiResponse<ProductionBatchDto> getBatchOwner(@PathVariable Long id) {
        return ApiResponse.ok(productionService.getBatch(id));
    }

    @PatchMapping("/api/owner/factory/batches/{id}/reviewed")
    public ApiResponse<ProductionBatchDto> markReviewed(@PathVariable Long id) {
        return ApiResponse.ok(productionService.markReviewed(id));
    }

    // ── Factory Worker: tạo mẻ ───────────────────────────────────────────────
    @PostMapping("/api/factory/batches")
    public ApiResponse<ProductionBatchDto> createBatch(@RequestBody CreateBatchRequest req,
                                                        Authentication auth) {
        return ApiResponse.ok(productionService.createBatch(req, auth.getName()));
    }

    @GetMapping("/api/factory/batches")
    public ApiResponse<Page<ProductionBatchDto>> listMyBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        Long userId = getUserId(auth);
        return ApiResponse.ok(productionService.listBatches(page, size, userId));
    }

    // ── Factory Worker: read-only data ───────────────────────────────────────
    @GetMapping("/api/factory/recipes")
    public ApiResponse<List<ProductionRecipeDto>> listRecipesForWorker(
            @RequestParam(required = false) Long productId) {
        return ApiResponse.ok(productionService.listRecipes(productId));
    }

    @GetMapping("/api/factory/products")
    public ApiResponse<List<FactoryProductDto>> listProductsForWorker() {
        return ApiResponse.ok(productionService.listProducts(true));
    }

    @GetMapping("/api/factory/materials")
    public ApiResponse<List<FactoryMaterialDto>> listMaterialsForWorker() {
        return ApiResponse.ok(productionService.listMaterials(true));
    }

    private Long getUserId(Authentication auth) {
        try {
            if (auth.getPrincipal() instanceof User u) return u.getId();
        } catch (Exception ignored) {}
        return null;
    }
}
