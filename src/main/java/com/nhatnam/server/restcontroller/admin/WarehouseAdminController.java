package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.warehouse.CreateWarehouseRequest;
import com.nhatnam.server.dto.warehouse.UpdateWarehouseRequest;
import com.nhatnam.server.dto.warehouse.WarehouseDto;
import com.nhatnam.server.entity.Category;
import com.nhatnam.server.entity.IngredientExpiry;
import com.nhatnam.server.entity.IngredientStock;
import com.nhatnam.server.entity.SubCategory;
import com.nhatnam.server.repository.CategoryRepository;
import com.nhatnam.server.repository.IngredientExpiryRepository;
import com.nhatnam.server.repository.IngredientStockRepository;
import com.nhatnam.server.repository.SubCategoryRepository;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.service.admin.WarehouseAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/warehouses")
@RequiredArgsConstructor
public class WarehouseAdminController {

    private final WarehouseAdminService        warehouseService;
    private final IngredientStockRepository    ingredientStockRepository;
    private final IngredientExpiryRepository   ingredientExpiryRepository;
    private final CategoryRepository           categoryRepository;
    private final SubCategoryRepository        subCategoryRepository;
    private final IngredientRepository         ingredientRepository;
    private final com.nhatnam.server.repository.IngredientWarehouseRepository ingredientWarehouseRepository;

    @GetMapping
    public ApiResponse<List<WarehouseDto>> listAll() {
        return ApiResponse.ok(warehouseService.listAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<WarehouseDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(warehouseService.getById(id));
    }

    /**
     * Chi tiết tồn kho của 1 kho — tính totalCostValue từ lô thực tế (IngredientExpiry),
     * không dùng cached field trong IngredientStock (vì không được cập nhật khi chuyển kho).
     */
    @GetMapping("/{id}/stock")
    public ApiResponse<Map<String, Object>> getWarehouseStock(@PathVariable Long id) {

        // Lookup category / subCategory
        Map<Long, String> catMap = categoryRepository.findByIsActiveTrueOrderByNameAsc()
                .stream().collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Long, String> subCatMap = subCategoryRepository.findByIsActiveTrueOrderByNameAsc()
                .stream().collect(Collectors.toMap(SubCategory::getId, SubCategory::getName));

        // Tất cả lô HSD còn hàng của kho này — nhóm theo ingredientId
        Map<Long, List<IngredientExpiry>> lotsByIngredient =
                ingredientExpiryRepository.findByWarehouseId(id)
                        .stream()
                        .filter(e -> e.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                        .collect(Collectors.groupingBy(e -> e.getIngredientId()));

        List<IngredientStock> stocks = ingredientStockRepository.findByWarehouseId(id)
                .stream()
                .sorted(Comparator.comparing(IngredientStock::getStockQuantity).reversed())
                .toList();

        // Chỉ giữ các nguyên liệu đã đăng ký cho kho này (ingredient_warehouse)
        Set<Long> registeredIngIds = ingredientWarehouseRepository.findByWarehouseId(id)
                .stream()
                .map(com.nhatnam.server.entity.IngredientWarehouse::getIngredientId)
                .collect(java.util.stream.Collectors.toSet());
        stocks = stocks.stream()
                .filter(s -> registeredIngIds.contains(s.getIngredientId()))
                .toList();

        // Batch load ingredient metadata cho categoryId/subCategoryId
        Set<Long> _allIngIds = stocks.stream().map(IngredientStock::getIngredientId).collect(java.util.stream.Collectors.toSet());
        Map<Long, Ingredient> _ingMap = ingredientRepository.findAllById(_allIngIds).stream().collect(Collectors.toMap(Ingredient::getId, i -> i));

        List<Map<String, Object>> rows = stocks.stream().map(s -> {
            Long ingId = s.getIngredientId();

            // Tính totalCostValue từ lô thực tế
            List<IngredientExpiry> lots = lotsByIngredient.getOrDefault(ingId, List.of());
            BigDecimal totalCostValue = lots.stream()
                    .filter(e -> e.getCostPrice() != null)
                    .map(e -> e.getQuantity()
                            .multiply(e.getCostPrice())
                            .setScale(2, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Fallback: nếu không có lô nào có costPrice → dùng cached field
            if (totalCostValue.compareTo(BigDecimal.ZERO) == 0
                    && s.getTotalCostValue() != null
                    && s.getTotalCostValue().compareTo(BigDecimal.ZERO) > 0) {
                totalCostValue = s.getTotalCostValue();
            }

            Ingredient _s = _ingMap.get(s.getIngredientId());
            Long catId    = _s != null ? _s.getCategoryId() : null;
            Long subCatId = _s != null ? _s.getSubCategoryId() : null;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ingredientId",    ingId);
            row.put("ingredientName",  s.resolvedName());
            row.put("unit",            s.resolvedUnit());
            row.put("imageUrl",        null);
            row.put("stockQuantity",   s.getStockQuantity());
            row.put("totalCostValue",  totalCostValue);
            row.put("categoryId",      catId);
            row.put("categoryName",    catId    != null ? catMap.getOrDefault(catId, "Chưa phân loại") : "Chưa phân loại");
            row.put("subCategoryId",   subCatId);
            row.put("subCategoryName", subCatId != null ? subCatMap.getOrDefault(subCatId, null) : null);
            row.put("updatedAt",       s.getUpdatedAt());
            return row;
        }).toList();

        BigDecimal grandTotal = rows.stream()
                .map(r -> (BigDecimal) r.get("totalCostValue"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warehouseId",       id);
        result.put("items",             rows);
        result.put("grandTotalCostValue", grandTotal);
        return ApiResponse.ok(result);
    }

    @PostMapping
    public ApiResponse<WarehouseDto> create(@Valid @RequestBody CreateWarehouseRequest req) {
        return ApiResponse.ok("Tạo kho thành công (đã tự động thêm nguyên liệu)",
                warehouseService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<WarehouseDto> update(@PathVariable Long id,
                                            @Valid @RequestBody UpdateWarehouseRequest req) {
        return ApiResponse.ok("Cập nhật kho thành công", warehouseService.update(id, req));
    }

    @PutMapping("/{id}/active")
    public ApiResponse<WarehouseDto> setActive(@PathVariable Long id,
                                               @RequestParam boolean value) {
        return ApiResponse.ok(value ? "Mở kho" : "Đóng kho", warehouseService.setActive(id, value));
    }
}