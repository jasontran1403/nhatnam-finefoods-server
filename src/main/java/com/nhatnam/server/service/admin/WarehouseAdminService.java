package com.nhatnam.server.service.admin;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.warehouse.CreateWarehouseRequest;
import com.nhatnam.server.dto.warehouse.UpdateWarehouseRequest;
import com.nhatnam.server.dto.warehouse.WarehouseDto;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.IngredientStock;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.IngredientStockRepository;
import com.nhatnam.server.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WarehouseAdminService {

    private final WarehouseRepository warehouseRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockRepository ingredientStockRepository;

    @Transactional(readOnly = true)
    public List<WarehouseDto> listAll() {
        return warehouseRepository.findAllByOrderByIdAsc().stream()
                .map(w -> {
                    WarehouseDto dto = toDto(w);
                    dto.setIngredientCount(ingredientStockRepository.findByWarehouseId(w.getId()).size());
                    return dto;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public WarehouseDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    /**
     * Tạo kho mới — tự động thêm tất cả nguyên liệu active vào kho với stockQuantity = 0.
     */
    @Transactional
    public WarehouseDto create(CreateWarehouseRequest req) {
        if (warehouseRepository.existsByName(req.getName())) {
            throw new BusinessException("Tên kho đã tồn tại");
        }

        long now = System.currentTimeMillis();
        Warehouse w = Warehouse.builder()
                .name(req.getName())
                .address(req.getAddress())
                .type(req.getType())
                .active(req.getActive() == null || req.getActive())
                .createdAt(now)
                .updatedAt(now)
                .build();
        w = warehouseRepository.save(w);

        // Auto add all active ingredients into this warehouse with quantity = 0
        List<Ingredient> ingredients = ingredientRepository.findByIsActiveTrueOrderByNameAsc();
        List<IngredientStock> stocks = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            stocks.add(IngredientStock.builder()
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .warehouse(w)
                    .stockQuantity(BigDecimal.ZERO)
                    .updatedAt(now)
                    .build());
        }
        if (!stocks.isEmpty()) {
            ingredientStockRepository.saveAll(stocks);
        }

        WarehouseDto dto = toDto(w);
        dto.setIngredientCount(stocks.size());
        return dto;
    }

    @Transactional
    public WarehouseDto update(Long id, UpdateWarehouseRequest req) {
        Warehouse w = findOrThrow(id);
        if (req.getName() != null && !req.getName().equals(w.getName())) {
            if (warehouseRepository.existsByName(req.getName())) {
                throw new BusinessException("Tên kho đã tồn tại");
            }
            w.setName(req.getName());
        }
        if (req.getAddress() != null) w.setAddress(req.getAddress());
        if (req.getType() != null) w.setType(req.getType());
        if (req.getActive() != null) w.setActive(req.getActive());
        w.setUpdatedAt(System.currentTimeMillis());
        return toDto(warehouseRepository.save(w));
    }

    @Transactional
    public WarehouseDto setActive(Long id, boolean active) {
        Warehouse w = findOrThrow(id);
        w.setActive(active);
        w.setUpdatedAt(System.currentTimeMillis());
        return toDto(warehouseRepository.save(w));
    }

    private Warehouse findOrThrow(Long id) {
        return warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Kho không tồn tại: " + id));
    }

    private WarehouseDto toDto(Warehouse w) {
        return WarehouseDto.builder()
                .id(w.getId())
                .name(w.getName())
                .address(w.getAddress())
                .type(w.getType() == null ? null : w.getType().name())
                .active(w.isActive())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }
}
