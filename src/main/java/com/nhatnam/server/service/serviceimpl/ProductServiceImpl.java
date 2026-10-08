package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.*;
import com.nhatnam.server.dto.response.ProductResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.VatMode;
import com.nhatnam.server.enumtype.VatRate;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.CertificateService;
import com.nhatnam.server.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl implements ProductService {
    private final IngredientWarehouseRepository ingredientWarehouseRepo;
    private final ProductRepository             productRepository;
    private final ProductPriceTierRepository    priceTierRepository;
    private final IngredientRepository          ingredientRepository;
    private final ProductIngredientRepository   productIngredientRepository;
    private final CategoryRepository            categoryRepository;
    private final IngredientStockRepository     ingredientStockRepository;
    private final WarehouseRepository           warehouseRepository;
    private final CertificateService            certificateService;

    // ════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════

    private String resolveCategoryName(CreateCompleteProductRequest request) {
        if (request.getCategoryId() != null) {
            return categoryRepository.findById(request.getCategoryId())
                    .map(Category::getName)
                    .orElse(request.getCategory());
        }
        return request.getCategory();
    }

    private Long resolveCategoryId(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return null;
        return categoryRepository.findByName(categoryName)
                .map(Category::getId)
                .orElse(null);
    }

    // ════════════════════════════════════════════════════════════════
    // TIER CRUD
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ProductResponse addTier(Long productId, TierRequest req) {
        long now = System.currentTimeMillis();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
        priceTierRepository.save(ProductPriceTier.builder()
                .product(product)
                .tierName(req.getTierName()).minQuantity(req.getMinQuantity())
                .maxQuantity(req.getMaxQuantity()).price(req.getPrice())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .isActive(true).createdAt(now).updatedAt(now).build());
        product.setUpdatedAt(now);
        return mapToResponse(productRepository.save(product), null);
    }

    @Override
    @Transactional
    public ProductResponse updateTier(Long productId, Long tierId, TierRequest req) {
        long now = System.currentTimeMillis();
        ProductPriceTier tier = priceTierRepository.findByIdAndProductId(tierId, productId)
                .orElseThrow(() -> new RuntimeException("Tier not found: " + tierId));
        tier.setTierName(req.getTierName()); tier.setMinQuantity(req.getMinQuantity());
        tier.setMaxQuantity(req.getMaxQuantity()); tier.setPrice(req.getPrice());
        if (req.getSortOrder() != null) tier.setSortOrder(req.getSortOrder());
        tier.setUpdatedAt(now);
        priceTierRepository.save(tier);
        return mapToResponse(productRepository.findById(productId).orElseThrow(), null);
    }

    @Override
    @Transactional
    public ProductResponse deleteTier(Long productId, Long tierId) {
        ProductPriceTier tier = priceTierRepository.findByIdAndProductId(tierId, productId)
                .orElseThrow(() -> new RuntimeException("Tier not found: " + tierId));
        priceTierRepository.delete(tier);
        return mapToResponse(productRepository.findById(productId).orElseThrow(), null);
    }

    // ════════════════════════════════════════════════════════════════
    // CREATE
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ProductResponse createCompleteProduct(CreateCompleteProductRequest request) {
        long now = System.currentTimeMillis();
        String categoryName = resolveCategoryName(request);

        Product product = Product.builder()
                .name(request.getName()).imageUrl(request.getImageUrl())
                .unit(request.getUnit()).isActive(true)
                .createdAt(now).updatedAt(now).category(categoryName)
                .vatRate(VatRate.fromPercentage(request.getVatRate()))
                .vatMode(request.getVatMode() != null
                        ? VatMode.valueOf(request.getVatMode().toUpperCase()) : VatMode.INCLUSIVE)
                .basePrice(request.getBasePrice() != null ? request.getBasePrice() : BigDecimal.ZERO)
                .maxDiscountRate(request.getMaxDiscountRate() != null ? request.getMaxDiscountRate() : 0)
                .unitsPerBox(request.getUnitsPerBox() != null && request.getUnitsPerBox() > 0
                        ? request.getUnitsPerBox() : null)
                .specification(request.getSpecification())
                .misaCategory(request.getMisaCategory())
                .priceTiers(new ArrayList<>())
                .build();
        product = productRepository.save(product);

        if (request.getTiers() != null && !request.getTiers().isEmpty()) {
            int idx = 0;
            for (var ti : request.getTiers()) {
                priceTierRepository.save(ProductPriceTier.builder()
                        .product(product).tierName(ti.getTierName())
                        .minQuantity(ti.getMinQuantity()).maxQuantity(ti.getMaxQuantity())
                        .price(ti.getPrice()).sortOrder(idx++).isActive(true)
                        .createdAt(now).updatedAt(now).build());
            }
        }

        if (request.getIngredients() != null && !request.getIngredients().isEmpty()) {
            final Long productId = product.getId();
            for (var ii : request.getIngredients()) {
                Ingredient ing = ingredientRepository.findById(ii.getIngredientId())
                        .orElseThrow(() -> new RuntimeException(
                                "Ingredient not found: " + ii.getIngredientId()));
                // THAY ĐỔI: dùng plain id + snapshot thay vì @ManyToOne object
                productIngredientRepository.save(ProductIngredient.builder()
                        .productId(productId)
                        .ingredientId(ing.getId())
                        .ingredientNameSnapshot(ing.getName())       // ← snapshot
                        .ingredientUnitSnapshot(ing.getUnit())       // ← snapshot
                        .ingredientImageUrlSnapshot(ing.getImageUrl())// ← snapshot
                        .qty(ii.getQuantity() != null ? ii.getQuantity() : BigDecimal.ONE)
                        .canOverride(Boolean.TRUE.equals(ii.getCanOverride()))
                        .build());
            }
        }
        return mapToResponse(productRepository.findById(product.getId()).orElseThrow(), null);
    }

    // ════════════════════════════════════════════════════════════════
    // UPDATE
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ProductResponse updateProduct(Long id, CreateCompleteProductRequest request) {
        Product product = productRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
        long now = System.currentTimeMillis();

        if (request.getUnit() != null) product.setUnit(request.getUnit());
        product.setName(request.getName());
        if (request.getImageUrl() != null) product.setImageUrl(request.getImageUrl());
        if (request.getBasePrice() != null) product.setBasePrice(request.getBasePrice());
        product.setVatRate(VatRate.fromPercentage(request.getVatRate()));
        product.setUpdatedAt(now);
        product.setVatMode(request.getVatMode() != null
                ? VatMode.valueOf(request.getVatMode().toUpperCase()) : VatMode.INCLUSIVE);
        product.setUnitsPerBox(request.getUnitsPerBox() != null && request.getUnitsPerBox() > 0
                ? request.getUnitsPerBox() : null);
        product.setSpecification(request.getSpecification());
        product.setMisaCategory(request.getMisaCategory());

        String categoryName = resolveCategoryName(request);
        if (categoryName != null && !categoryName.isBlank()) product.setCategory(categoryName);

        product = productRepository.save(product);

        if (request.getTiers() != null) {
            priceTierRepository.deleteByProductId(product.getId());
            priceTierRepository.flush();
            int idx = 0;
            for (var ti : request.getTiers()) {
                priceTierRepository.save(ProductPriceTier.builder()
                        .product(product).tierName(ti.getTierName())
                        .minQuantity(ti.getMinQuantity()).maxQuantity(ti.getMaxQuantity())
                        .price(ti.getPrice()).sortOrder(idx++).isActive(true)
                        .createdAt(now).updatedAt(now).build());
            }
        }

        if (request.getIngredients() != null) {
            productIngredientRepository.deleteByProductId(product.getId());
            productIngredientRepository.flush();
            final Long productId = product.getId();
            for (var ii : request.getIngredients()) {
                Ingredient ing = ingredientRepository.findById(ii.getIngredientId())
                        .orElseThrow(() -> new RuntimeException(
                                "Ingredient not found: " + ii.getIngredientId()));
                productIngredientRepository.save(ProductIngredient.builder()
                        .productId(productId)
                        .ingredientId(ing.getId())
                        .ingredientNameSnapshot(ing.getName())
                        .ingredientUnitSnapshot(ing.getUnit())
                        .ingredientImageUrlSnapshot(ing.getImageUrl())
                        .qty(ii.getQuantity() != null ? ii.getQuantity() : BigDecimal.ONE)
                        .canOverride(Boolean.TRUE.equals(ii.getCanOverride()))
                        .build());
            }
        }
        return mapToResponse(productRepository.findById(product.getId()).orElseThrow(), null);
    }

    // ════════════════════════════════════════════════════════════════
    // DELETE
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found"));
        product.setIsActive(false);
        product.setUpdatedAt(System.currentTimeMillis());
        productRepository.save(product);
        // Xóa toàn bộ chứng nhận (và file vật lý) của sản phẩm
        certificateService.deleteAllByProductId(id);
        // ProductIngredient vẫn giữ lại (dùng productId plain column)
        // ProductPriceTier vẫn giữ lại (cascade không ảnh hưởng vì chỉ soft-delete)
    }

    // ════════════════════════════════════════════════════════════════
    // GET
    // ════════════════════════════════════════════════════════════════

    @Override
    public ProductResponse getProductById(Long id) {
        return mapToResponse(productRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Product not found")), null);
    }

    @Override
    public List<ProductResponse> getAllProducts(Long warehouseId) {
        return productRepository.findByIsActiveTrue().stream()
                .filter(p -> isProductAvailableInWarehouse(p.getId(), warehouseId))
                .filter(p -> !productIngredientRepository.findByProductId(p.getId()).isEmpty())
                .map(p -> mapToResponse(p, warehouseId))
                .toList();
    }

    @Override
    public List<ProductResponse> getProductsByCategory(String category, Long warehouseId) {
        return productRepository.findByCategoryAndIsActiveTrue(category).stream()
                .filter(p -> isProductAvailableInWarehouse(p.getId(), warehouseId))
                .filter(p -> !productIngredientRepository.findByProductId(p.getId()).isEmpty())
                .map(p -> mapToResponse(p, warehouseId))
                .toList();
    }

    private boolean isProductAvailableInWarehouse(Long productId, Long warehouseId) {
        if (warehouseId == null) return true;
        List<ProductIngredient> pings = productIngredientRepository.findByProductId(productId);
        if (pings.isEmpty()) return true;
        Set<Long> warehouseIngIds = new HashSet<>(
                ingredientWarehouseRepo.findIngredientIdsByWarehouseId(warehouseId));
        // THAY ĐỔI: dùng pi.getIngredientId() thay vì pi.getIngredient().getId()
        return pings.stream().allMatch(pi -> warehouseIngIds.contains(pi.getIngredientId()));
    }

    // ════════════════════════════════════════════════════════════════
    // mapToResponse
    // ════════════════════════════════════════════════════════════════

    private ProductResponse mapToResponse(Product product, Long warehouseId) {
        List<ProductResponse.PriceTierResponse> tierResponses = product.getPriceTiers().stream()
                .map(t -> ProductResponse.PriceTierResponse.builder()
                        .id(t.getId()).tierName(t.getTierName())
                        .minQuantity(t.getMinQuantity()).maxQuantity(t.getMaxQuantity())
                        .price(t.getPrice()).sortOrder(t.getSortOrder()).isActive(t.getIsActive())
                        .build())
                .collect(Collectors.toList());

        List<ProductIngredient> directIngs =
                productIngredientRepository.findByProductId(product.getId());

        // THAY ĐỔI: load Ingredient objects để lấy stock — nhưng fallback về snapshot nếu deleted
        Map<Long, Ingredient> ingMap = new HashMap<>();
        if (!directIngs.isEmpty()) {
            List<Long> ingIds = directIngs.stream()
                    .map(ProductIngredient::getIngredientId).collect(Collectors.toList());
            ingredientRepository.findAllById(ingIds)
                    .forEach(i -> ingMap.put(i.getId(), i));
        }

        Map<Long, BigDecimal> ingStockMap = new HashMap<>();
        if (warehouseId != null) {
            for (ProductIngredient pi : directIngs) {
                ingredientStockRepository
                        .findByIngredientIdAndWarehouseId(pi.getIngredientId(), warehouseId)
                        .ifPresent(s -> ingStockMap.put(pi.getIngredientId(), s.getStockQuantity()));
            }
        }

        List<ProductResponse.IngredientItem> ingredientItems = directIngs.stream()
                .map(pi -> {
                    // Ưu tiên live data nếu ingredient còn active, fallback snapshot
                    Ingredient ing = ingMap.get(pi.getIngredientId());
                    String name     = ing != null ? ing.getName()     : pi.getIngredientNameSnapshot();
                    String unit     = ing != null ? ing.getUnit()     : pi.getIngredientUnitSnapshot();
                    String imageUrl = ing != null ? ing.getImageUrl() : pi.getIngredientImageUrlSnapshot();

                    return ProductResponse.IngredientItem.builder()
                            .ingredientId(pi.getIngredientId())  // ← plain id
                            .ingredientName(name)
                            .ingredientImageUrl(imageUrl)
                            .unit(unit)
                            .quantity(pi.getQty())
                            .canOverride(Boolean.TRUE.equals(pi.getCanOverride()))
                            .stockQuantity(ingStockMap.getOrDefault(pi.getIngredientId(), BigDecimal.ZERO))
                            .build();
                })
                .collect(Collectors.toList());

        // Tính stockQuantity từ nguyên liệu
        BigDecimal computedStock = null;
        if (!directIngs.isEmpty() && warehouseId != null) {
            for (ProductIngredient pi : directIngs) {
                IngredientStock stock = ingredientStockRepository
                        .findByIngredientIdAndWarehouseId(pi.getIngredientId(), warehouseId)
                        .orElse(null);
                BigDecimal ingStock = stock != null ? stock.getStockQuantity() : BigDecimal.ZERO;
                BigDecimal qtyPerProduct = (pi.getQty() != null && pi.getQty().compareTo(BigDecimal.ZERO) > 0)
                        ? pi.getQty() : BigDecimal.ONE;
                BigDecimal canMake = ingStock.divide(qtyPerProduct, 0, RoundingMode.FLOOR);
                if (computedStock == null || canMake.compareTo(computedStock) < 0)
                    computedStock = canMake;
            }
        }

        Long categoryId = resolveCategoryId(product.getCategory());

        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName()).unit(product.getUnit())
                .categoryId(categoryId).categoryName(product.getCategory())
                .imageUrl(product.getImageUrl()).isActive(product.getIsActive())
                .basePrice(product.getBasePrice()).stockQuantity(computedStock)
                .vatMode(product.getVatMode() != null ? product.getVatMode().name() : VatMode.INCLUSIVE.name())
                .vatRate(product.getVatRate() != null ? product.getVatRate().getPercentage() : 0)
                .maxDiscountRate(product.getMaxDiscountRate()).unitsPerBox(product.getUnitsPerBox())
                .specification(product.getSpecification())
                .misaCategory(product.getMisaCategory())
                .createdAt(product.getCreatedAt()).updatedAt(product.getUpdatedAt())
                .priceTiers(tierResponses).ingredients(ingredientItems)
                .build();
    }
}
