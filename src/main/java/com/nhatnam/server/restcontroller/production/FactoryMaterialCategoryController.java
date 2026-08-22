package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.ProductionDtos.*;
import com.nhatnam.server.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Danh mục nguyên liệu xưởng: danh mục CHUNG ⟶ danh mục RIÊNG.
 * Nguyên liệu xưởng trực thuộc danh mục riêng.
 */
@RestController
@RequestMapping("/api/owner/factory/material-categories")
@RequiredArgsConstructor
public class FactoryMaterialCategoryController {

    private final ProductionService productionService;

    /** Danh sách danh mục chung, mỗi mục kèm danh mục riêng trực thuộc. */
    @GetMapping
    public ApiResponse<List<FactoryMaterialCategoryDto>> list() {
        return ApiResponse.ok(productionService.listCategoriesWithSub());
    }

    /** Tạo danh mục chung. */
    @PostMapping
    public ApiResponse<FactoryMaterialCategoryDto> createCategory(@RequestBody SaveCategoryRequest req) {
        return ApiResponse.ok(productionService.createCategory(req));
    }

    /** Tạo danh mục riêng (trực thuộc 1 danh mục chung). */
    @PostMapping("/sub")
    public ApiResponse<FactoryMaterialSubCategoryDto> createSubCategory(@RequestBody SaveSubCategoryRequest req) {
        return ApiResponse.ok(productionService.createSubCategory(req));
    }
}
