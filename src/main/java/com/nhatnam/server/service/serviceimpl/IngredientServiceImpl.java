package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.CreateIngredientRequest;
import com.nhatnam.server.dto.response.IngredientResponse;
import com.nhatnam.server.entity.FactoryProduct;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.IngredientStock;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.IngredientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class IngredientServiceImpl implements IngredientService {

    private final IngredientWarehouseRepository ingredientWarehouseRepo;
    private final IngredientRepository          ingredientRepository;
    private final IngredientStockRepository     ingredientStockRepository;
    private final WarehouseRepository           warehouseRepository;
    // Cầu nối Ingredient → FactoryProduct: đồng bộ tên/đơn vị khi Ingredient đổi
    // (xem comment ở FactoryProduct.ingredientId để biết lý do thêm liên kết này)
    private final FactoryProductRepository      factoryProductRepository;

    // ── CREATE ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public IngredientResponse createIngredient(CreateIngredientRequest request) {
        long now = System.currentTimeMillis();

        Ingredient ingredient = Ingredient.builder()
                .name(request.getName()).imageUrl(request.getImageUrl())
                .unit(request.getUnit()).itemCode(request.getItemCode())
                .categoryId(request.getCategoryId()).subCategoryId(request.getSubCategoryId())
                .isActive(true).createdAt(now).updatedAt(now)
                .build();
        Ingredient saved = ingredientRepository.save(ingredient);

        // Tạo IngredientStock cho mỗi warehouse — dùng plain id + snapshot
        List<Warehouse> saleWarehouses = warehouseRepository.findByActiveTrue().stream().toList();
        List<IngredientStock> stocks = saleWarehouses.stream()
                .map(w -> IngredientStock.builder()
                        .ingredientId(saved.getId())                   // ← plain id
                        .ingredientNameSnapshot(saved.getName())       // ← snapshot
                        .ingredientUnitSnapshot(saved.getUnit())       // ← snapshot
                        .warehouse(w)
                        .stockQuantity(BigDecimal.valueOf(0))
                        .updatedAt(now)
                        .build())
                .collect(Collectors.toList());
        ingredientStockRepository.saveAll(stocks);

        return mapToResponse(saved);
    }

    // ── UPDATE ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public IngredientResponse updateIngredient(Long id, CreateIngredientRequest request) {
        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found: " + id));

        ingredient.setName(request.getName());
        if (request.getImageUrl() != null) ingredient.setImageUrl(request.getImageUrl());
        ingredient.setUnit(request.getUnit());
        ingredient.setItemCode(request.getItemCode());
        ingredient.setCategoryId(request.getCategoryId());
        ingredient.setSubCategoryId(request.getSubCategoryId());
        ingredient.setUpdatedAt(System.currentTimeMillis());
        Ingredient saved = ingredientRepository.save(ingredient);

        // THÊM MỚI: cập nhật snapshot trong tất cả IngredientStock khi ingredient đổi tên/unit
        List<IngredientStock> stocks = ingredientStockRepository.findByIngredientId(id);
        for (IngredientStock s : stocks) {
            s.setIngredientNameSnapshot(saved.getName());
            s.setIngredientUnitSnapshot(saved.getUnit());
            s.setUpdatedAt(System.currentTimeMillis());
        }
        if (!stocks.isEmpty()) ingredientStockRepository.saveAll(stocks);

        // Đồng bộ tên/đơn vị xuống mọi FactoryProduct (sản phẩm xưởng) đang liên kết
        // tới Ingredient này — Ingredient là nguồn sự thật, FactoryProduct.name/unit
        // chỉ là snapshot hiển thị (xem comment ở FactoryProduct.ingredientId).
        List<FactoryProduct> linkedProducts = factoryProductRepository.findByIngredientId(id);
        for (FactoryProduct fp : linkedProducts) {
            fp.setName(saved.getName());
            fp.setUnit(saved.getUnit());
            fp.setUpdatedAt(System.currentTimeMillis());
        }
        if (!linkedProducts.isEmpty()) factoryProductRepository.saveAll(linkedProducts);

        return mapToResponse(saved);
    }

    // ── DELETE (soft) ────────────────────────────────────────────────

    @Override
    @Transactional
    public void deleteIngredient(Long id) {
        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found: " + id));

        // 1. Soft-delete
        ingredient.setIsActive(false);
        ingredient.setUpdatedAt(System.currentTimeMillis());
        ingredientRepository.save(ingredient);

        // 2. Xóa IngredientWarehouse mappings (không cần thiết nữa khi inactive)
        //    Snapshot trong IngredientStock vẫn giữ lại để lịch sử tồn kho không bị mất
        try {
            ingredientWarehouseRepo.deleteByIngredientId(id);
        } catch (Exception e) {
            log.warn("[DELETE_INGREDIENT] Could not remove warehouse mappings: {}", e.getMessage());
        }

        // 3. IngredientStock giữ nguyên (snapshot đã có, OrderStockDeduction vẫn tham chiếu)
        // 4. IngredientExpiry giữ nguyên (để hoàn kho khi hủy đơn cũ)
        // 5. ProductIngredient giữ nguyên (snapshot đã có, tự tắt khi product load)

    }

    // ── GET ──────────────────────────────────────────────────────────

    @Override
    public IngredientResponse getIngredientById(Long id) {
        Ingredient ingredient = ingredientRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found: " + id));
        return mapToResponseWithAllStock(ingredient);
    }

    @Override
    public List<IngredientResponse> getAllIngredients() {
        return ingredientRepository.findByIsActiveTrue().stream()
                .map(this::mapToResponseWithAllStock)
                .collect(Collectors.toList());
    }

    @Override
    public List<IngredientResponse> getAllIngredientsOfWarehouse(Long warehouseId) {
        if (warehouseId == null) return getAllIngredients();

        // Nguồn sự thật là bảng GÁN KHO, không phải bảng tồn kho: một nguyên liệu
        // bị gỡ khỏi kho vẫn còn dòng IngredientStock (tồn 0 hoặc số cũ do chuyển
        // kho), nên lọc theo tồn kho sẽ vẫn lọt.
        Set<Long> assignedIds = new java.util.HashSet<>(
                ingredientWarehouseRepo.findIngredientIdsByWarehouseId(warehouseId));

        return ingredientRepository.findByIsActiveTrue().stream()
                .filter(i -> assignedIds.contains(i.getId()))
                .map(this::mapToResponseWithAllStock)
                .collect(Collectors.toList());
    }

    public List<IngredientResponse> getAllIngredientsByWarehouse(Long warehouseId) {
        Map<Long, BigDecimal> stockMap = ingredientStockRepository
                .findByWarehouseIdWithIngredient(warehouseId)
                .stream()
                .collect(Collectors.toMap(
                        IngredientStock::getIngredientId,   // ← plain id
                        IngredientStock::getStockQuantity
                ));
        return ingredientRepository.findByIsActiveTrue().stream()
                .map(ing -> mapToResponseWithWarehouseStock(ing, stockMap))
                .collect(Collectors.toList());
    }

    @Override
    public List<IngredientResponse> getPaginationIngredients(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return ingredientRepository.findByIsActiveTrue(pageable).stream()
                .map(this::mapToResponseWithAllStock)
                .collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════
    // PRIVATE MAPPERS
    // ════════════════════════════════════════════════════════════════

    private IngredientResponse mapToResponseWithAllStock(Ingredient ingredient) {
        // IngredientStock.ingredientId là plain column
        Map<Long, BigDecimal> stockByWarehouse = ingredientStockRepository
                .findByIngredientId(ingredient.getId())
                .stream()
                .collect(Collectors.toMap(
                        s -> s.getWarehouse().getId(),
                        IngredientStock::getStockQuantity
                ));
        BigDecimal totalStock = stockByWarehouse.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return IngredientResponse.builder()
                .id(ingredient.getId()).name(ingredient.getName())
                .imageUrl(ingredient.getImageUrl()).unit(ingredient.getUnit())
                .itemCode(ingredient.getItemCode())
                .categoryId(ingredient.getCategoryId()).subCategoryId(ingredient.getSubCategoryId())
                .createdAt(ingredient.getCreatedAt()).updatedAt(ingredient.getUpdatedAt())
                .stockByWarehouse(stockByWarehouse).stockQuantity(totalStock)
                .warehouseIds(ingredientWarehouseRepo.findWarehouseIdsByIngredientId(ingredient.getId()))
                .build();
    }

    private IngredientResponse mapToResponseWithWarehouseStock(
            Ingredient ingredient, Map<Long, BigDecimal> stockMap) {
        BigDecimal stock = stockMap.getOrDefault(ingredient.getId(), BigDecimal.ZERO);
        return IngredientResponse.builder()
                .id(ingredient.getId()).name(ingredient.getName())
                .imageUrl(ingredient.getImageUrl()).unit(ingredient.getUnit())
                .itemCode(ingredient.getItemCode())
                .categoryId(ingredient.getCategoryId()).subCategoryId(ingredient.getSubCategoryId())
                .createdAt(ingredient.getCreatedAt()).updatedAt(ingredient.getUpdatedAt())
                .stockQuantity(stock)
                .warehouseIds(ingredientWarehouseRepo.findWarehouseIdsByIngredientId(ingredient.getId()))
                .build();
    }

    private IngredientResponse mapToResponse(Ingredient ingredient) {
        return IngredientResponse.builder()
                .id(ingredient.getId()).name(ingredient.getName())
                .imageUrl(ingredient.getImageUrl()).unit(ingredient.getUnit())
                .itemCode(ingredient.getItemCode())
                .categoryId(ingredient.getCategoryId()).subCategoryId(ingredient.getSubCategoryId())
                .createdAt(ingredient.getCreatedAt()).updatedAt(ingredient.getUpdatedAt())
                .stockQuantity(BigDecimal.ZERO)
                .warehouseIds(ingredientWarehouseRepo.findWarehouseIdsByIngredientId(ingredient.getId()))
                .build();
    }
}