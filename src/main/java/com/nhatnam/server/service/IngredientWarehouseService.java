// IngredientWarehouseService.java
package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.AssignIngredientWarehouseRequest;
import com.nhatnam.server.dto.response.IngredientWarehouseResponse;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.IngredientWarehouse;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.IngredientWarehouseRepository;
import com.nhatnam.server.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IngredientWarehouseService {

    private final IngredientWarehouseRepository repo;
    private final IngredientRepository          ingredientRepo;
    private final WarehouseRepository           warehouseRepo;

    /** Lấy danh sách kho của 1 ingredient */
    public List<Long> getWarehouseIdsByIngredient(Long ingredientId) {
        return repo.findWarehouseIdsByIngredientId(ingredientId);
    }

    /** Lấy danh sách ingredient_id có trong 1 kho */
    public List<Long> getIngredientIdsByWarehouse(Long warehouseId) {
        return repo.findIngredientIdsByWarehouseId(warehouseId);
    }

    /** Assign ingredient vào danh sách kho (replace-all) */
    @Transactional
    public void assignWarehouses(Long ingredientId, List<Long> warehouseIds) {
        Ingredient ing = ingredientRepo.findById(ingredientId)
                .orElseThrow(() -> new IllegalArgumentException("Ingredient không tồn tại: " + ingredientId));

        // Xóa tất cả mapping cũ
        repo.deleteByIngredientId(ingredientId);
        repo.flush(); // ← BẮT BUỘC flush trước khi insert để tránh duplicate

        long now = System.currentTimeMillis();
        for (Long wid : warehouseIds) {
            Warehouse wh = warehouseRepo.findById(wid)
                    .orElseThrow(() -> new IllegalArgumentException("Kho không tồn tại: " + wid));
            repo.save(IngredientWarehouse.builder()
                    .ingredientId(ing.getId())
                    .warehouse(wh)
                    .createdAt(now)
                    .build());
        }
    }

    /** Thêm 1 kho cho ingredient */
    @Transactional
    public void addWarehouse(Long ingredientId, Long warehouseId) {
        if (repo.existsByIngredientIdAndWarehouseId(ingredientId, warehouseId)) return;
        Ingredient ing = ingredientRepo.findById(ingredientId)
                .orElseThrow(() -> new IllegalArgumentException("Ingredient không tồn tại"));
        Warehouse wh = warehouseRepo.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("Kho không tồn tại"));
        repo.save(IngredientWarehouse.builder()
                .ingredientId(ing.getId()).warehouse(wh)
                .createdAt(System.currentTimeMillis())
                .build());
    }

    /** Xóa 1 kho khỏi ingredient */
    @Transactional
    public void removeWarehouse(Long ingredientId, Long warehouseId) {
        repo.deleteByIngredientIdAndWarehouseId(ingredientId, warehouseId);
    }

    /** Lấy tất cả assignments dạng response */
    public List<IngredientWarehouseResponse> getAll() {
        return repo.findAll().stream().map(iw -> IngredientWarehouseResponse.builder()
                .id(iw.getId())
                .ingredientId(iw.getIngredientId())
                .ingredientName("")
                .warehouseId(iw.getWarehouse().getId())
                .warehouseName(iw.getWarehouse().getName())
                .createdAt(iw.getCreatedAt())
                .build()).toList();
    }
}