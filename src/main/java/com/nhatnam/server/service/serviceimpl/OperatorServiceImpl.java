package com.nhatnam.server.service.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.request.CreateCategoryRequest;
import com.nhatnam.server.dto.request.CreateIngredientRequest;
import com.nhatnam.server.dto.request.CreateCompleteProductRequest;
import com.nhatnam.server.dto.response.CategoryResponse;
import com.nhatnam.server.dto.response.IngredientResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.dto.request.CreateCompleteProductRequest;
import com.nhatnam.server.enumtype.VatMode;
import com.nhatnam.server.enumtype.VatRate;
import com.nhatnam.server.service.CategoryService;
import com.nhatnam.server.service.IngredientService;
import com.nhatnam.server.service.NotificationService;
import com.nhatnam.server.service.OperatorService;
import com.nhatnam.server.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OperatorServiceImpl implements OperatorService {

    private final CategoryService categoryService;
    private final IngredientService ingredientService;
    private final ProductBatchRepository batchRepository;
    private final ProductBatchItemRepository batchItemRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final CategoryRepository categoryRepository;

    @Override
    public CategoryResponse createCategory(CreateCategoryRequest req) {
        return categoryService.createCategory(req);
    }

    @Override
    public CategoryResponse updateCategory(Long id, CreateCategoryRequest req) {
        return categoryService.updateCategory(id, req);
    }

    @Override
    public void deleteCategory(Long id) {
        categoryService.deleteCategory(id);
    }

    @Override
    public IngredientResponse createIngredient(CreateIngredientRequest req) {
        return ingredientService.createIngredient(req);
    }

    @Override
    public IngredientResponse updateIngredient(Long id, CreateIngredientRequest req) {
        return ingredientService.updateIngredient(id, req);
    }

    @Override
    @Transactional
    public ProductBatch submitBatch(Long operatorId, String operatorName,
                                    ProductBatch.BatchType type,
                                    String note,
                                    List<Map<String, Object>> items) {
        User operator = userRepository.findById(operatorId)
                .orElseThrow(() -> new RuntimeException("Operator not found"));

        String code = generateBatchCode();
        ProductBatch batch = ProductBatch.builder()
                .batchCode(code)
                .type(type)
                .status(ProductBatch.BatchStatus.APPROVED) // auto-approve
                .createdBy(operator)
                .createdByName(operatorName)
                .reviewedByName("System")
                .reviewedAt(System.currentTimeMillis())
                .note(note)
                .items(new ArrayList<>())
                .build();
        batch = batchRepository.save(batch);

        int approved = 0, failed = 0;
        for (Map<String, Object> item : items) {
            try {
                ProductBatchItem batchItem = mapToBatchItem(batch, item);
                batchItem.setStatus(ProductBatchItem.ItemStatus.APPROVED);
                batchItemRepository.save(batchItem);
                // Auto-apply immediately
                applyBatchItem(batchItem);
                approved++;
            } catch (Exception e) {
                log.error("[OPERATOR] Failed to save/apply batch item: {}", e.getMessage());
                failed++;
            }
        }

        return batchRepository.findById(batch.getId()).orElse(batch);
    }

    @Override
    public List<ProductBatch> getMyBatches(Long operatorId) {
        return batchRepository.findAll().stream()
                .filter(b -> b.getCreatedBy().getId() == operatorId)
                .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
                .toList();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ProductBatchItem mapToBatchItem(ProductBatch batch, Map<String, Object> item) {
        String tiersJson = null;
        String ingredientsJson = null;
        try {
            if (item.get("tiers") != null)
                tiersJson = objectMapper.writeValueAsString(item.get("tiers"));
            if (item.get("ingredients") != null)
                ingredientsJson = objectMapper.writeValueAsString(item.get("ingredients"));
        } catch (Exception e) {
            log.warn("[OPERATOR] JSON serialize error: {}", e.getMessage());
        }

        Number basePriceN = item.get("basePrice") instanceof Number n ? n : null;
        Number maxDiscN   = item.get("maxDiscountRate") instanceof Number n ? n : null;
        Number vatRateN   = item.get("vatRate") instanceof Number n ? n : null;
        Number unitsPerBoxN = item.get("unitsPerBox") instanceof Number n ? n : null;

        // ── Resolve existingProduct cho loại UPDATE ──────────────────
        Long existingProductId = null;
        if (item.get("existingProductId") instanceof Number n) {
            existingProductId = n.longValue();
        }

        return ProductBatchItem.builder()
                .batch(batch)
                .existingProductId(existingProductId)   // ← thêm dòng này
                .productName(item.get("name") instanceof String s ? s : "")
                .categoryName(item.get("categoryName") instanceof String s ? s : null)
                .imageUrl(item.get("imageUrl") instanceof String s ? s : null)
                .unit(item.get("unit") instanceof String s ? s : null)
                .basePrice(basePriceN != null ? new BigDecimal(basePriceN.toString()) : null)
                .maxDiscountRate(maxDiscN != null ? maxDiscN.intValue() : 0)
                .vatRate(vatRateN != null ? vatRateN.intValue() : 8)
                .vatMode(item.get("vatMode") instanceof String s ? s : "INCLUSIVE")
                .unitsPerBox(unitsPerBoxN != null && unitsPerBoxN.intValue() > 0
                        ? unitsPerBoxN.intValue() : null)
                .tiersJson(tiersJson)
                .ingredientsJson(ingredientsJson)
                .status(ProductBatchItem.ItemStatus.PENDING)
                .build();
    }

    private String generateBatchCode() {
        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String rand = String.format("%04d", new java.util.Random().nextInt(10000));
        String code = "PB-" + date + "-" + rand;
        while (batchRepository.existsByBatchCode(code)) {
            rand = String.format("%04d", new java.util.Random().nextInt(10000));
            code = "PB-" + date + "-" + rand;
        }
        return code;
    }

    /** Áp dụng 1 batch item ngay lập tức (auto-approve) */
    @Transactional
    public void applyBatchItem(ProductBatchItem item) throws Exception {
        CreateCompleteProductRequest req = buildRequest(item);
        if (item.getExistingProductId() != null) {
            productService.updateProduct(item.getExistingProductId(), req);
            Product p = productRepository.findById(item.getExistingProductId()).orElseThrow();
            if (item.getMaxDiscountRate() != null) p.setMaxDiscountRate(item.getMaxDiscountRate());
            productRepository.save(p);
        } else {
            var created = productService.createCompleteProduct(req);
            Product p = productRepository.findById(created.getId()).orElseThrow();
            if (item.getMaxDiscountRate() != null) p.setMaxDiscountRate(item.getMaxDiscountRate());
            productRepository.save(p);
        }
    }

    @SuppressWarnings("unchecked")
    private CreateCompleteProductRequest buildRequest(ProductBatchItem item) throws Exception {
        CreateCompleteProductRequest req = new CreateCompleteProductRequest();
        req.setName(item.getProductName());
        req.setCategory(item.getCategoryName());
        req.setUnit(item.getUnit());
        req.setImageUrl(item.getImageUrl());
        req.setBasePrice(item.getBasePrice() != null ? item.getBasePrice() : java.math.BigDecimal.ZERO);
        req.setVatRate(item.getVatRate() != null ? item.getVatRate() : 8);
        req.setVatMode(item.getVatMode() != null ? item.getVatMode() : "INCLUSIVE");
        req.setUnitsPerBox(item.getUnitsPerBox());

        if (item.getTiersJson() != null && !item.getTiersJson().isBlank()) {
            List<Map<String, Object>> tiersRaw = objectMapper.readValue(item.getTiersJson(), List.class);
            List<CreateCompleteProductRequest.TierItem> tiers = tiersRaw.stream().map(t -> {
                CreateCompleteProductRequest.TierItem ti = new CreateCompleteProductRequest.TierItem();
                ti.setTierName(t.get("tierName") instanceof String s ? s : "");
                ti.setMinQuantity(t.get("minQuantity") instanceof Number n
                        ? new java.math.BigDecimal(n.toString()) : java.math.BigDecimal.ZERO);
                if (t.get("maxQuantity") instanceof Number n)
                    ti.setMaxQuantity(new java.math.BigDecimal(n.toString()));
                ti.setPrice(t.get("price") instanceof Number n
                        ? new java.math.BigDecimal(n.toString()) : java.math.BigDecimal.ZERO);
                ti.setSortOrder(t.get("sortOrder") instanceof Number n ? n.intValue() : 0);
                return ti;
            }).toList();
            req.setTiers(tiers);
        }

        if (item.getIngredientsJson() != null && !item.getIngredientsJson().isBlank()) {
            List<Map<String, Object>> ingsRaw = objectMapper.readValue(item.getIngredientsJson(), List.class);
            List<CreateCompleteProductRequest.IngredientItem> ings = ingsRaw.stream().map(i -> {
                CreateCompleteProductRequest.IngredientItem ii = new CreateCompleteProductRequest.IngredientItem();
                ii.setIngredientId(i.get("ingredientId") instanceof Number n ? n.longValue() : null);
                ii.setQuantity(i.get("quantity") instanceof Number n
                        ? new java.math.BigDecimal(n.toString()) : java.math.BigDecimal.ONE);
                ii.setCanOverride(Boolean.TRUE.equals(i.get("canOverride")));
                return ii;
            }).toList();
            req.setIngredients(ings);
        }
        return req;
    }

}