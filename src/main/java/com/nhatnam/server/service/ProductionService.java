package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.production.ProductionDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductionService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final FactoryMaterialRepository  materialRepo;
    private final FactoryProductRepository   productRepo;
    private final ProductionRecipeRepository recipeRepo;
    private final ProductionBatchRepository  batchRepo;
    private final UserRepository             userRepo;
    private final NotificationService        notificationService;

    // ─── FactoryMaterial ──────────────────────────────────────────────────────

    public List<FactoryMaterialDto> listMaterials(boolean activeOnly) {
        var list = activeOnly ? materialRepo.findByIsActiveTrueOrderByNameAsc()
                : materialRepo.findAllByOrderByNameAsc();
        return list.stream().map(this::toMaterialDto).collect(Collectors.toList());
    }

    public FactoryMaterialDto saveMaterial(Long id, SaveFactoryMaterialRequest req) {
        FactoryMaterial m = id == null ? new FactoryMaterial()
                : materialRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy NVL"));
        m.setName(req.getName());
        m.setUnit(req.getUnit());
        m.setDescription(req.getDescription());
        if (m.getIsActive() == null) m.setIsActive(true);
        return toMaterialDto(materialRepo.save(m));
    }

    public void toggleMaterial(Long id, boolean active) {
        FactoryMaterial m = materialRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy NVL"));
        m.setIsActive(active);
        materialRepo.save(m);
    }

    // ─── FactoryProduct ───────────────────────────────────────────────────────

    public List<FactoryProductDto> listProducts(boolean activeOnly) {
        var list = activeOnly ? productRepo.findByIsActiveTrueOrderByNameAsc()
                : productRepo.findAllByOrderByNameAsc();
        return list.stream().map(this::toProductDto).collect(Collectors.toList());
    }

    public FactoryProductDto saveProduct(Long id, SaveFactoryProductRequest req) {
        FactoryProduct p = id == null ? new FactoryProduct()
                : productRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành phẩm"));
        p.setName(req.getName());
        p.setUnit(req.getUnit());
        p.setDescription(req.getDescription());
        if (p.getIsActive() == null) p.setIsActive(true);
        return toProductDto(productRepo.save(p));
    }

    // ─── ProductionRecipe (chỉ Owner) ─────────────────────────────────────────

    public List<ProductionRecipeDto> listRecipes(Long productId) {
        var list = productId != null
                ? recipeRepo.findByFactoryProduct_IdAndIsActiveTrueOrderByCreatedAtDesc(productId)
                : recipeRepo.findByIsActiveTrueOrderByCreatedAtDesc();
        return list.stream().map(this::toRecipeDto).collect(Collectors.toList());
    }

    public ProductionRecipeDto getRecipe(Long id) {
        return toRecipeDto(recipeRepo.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy công thức")));
    }

    public ProductionRecipeDto saveRecipe(Long id, SaveRecipeRequest req, String username) {
        User owner = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        FactoryProduct fp = productRepo.findById(req.getFactoryProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành phẩm"));

        ProductionRecipe recipe = id == null ? new ProductionRecipe()
                : recipeRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy công thức"));

        recipe.setFactoryProduct(fp);
        recipe.setName(req.getName());
        recipe.setStandardOutputQty(req.getStandardOutputQty());
        recipe.setOutputUnit(req.getOutputUnit());
        recipe.setNotes(req.getNotes());
        recipe.setCreatedBy(owner);
        recipe.setCreatedByName(owner.getFullName());
        if (recipe.getIsActive() == null) recipe.setIsActive(true);

        // Clear + rebuild items
        recipe.getItems().clear();
        if (req.getItems() != null) {
            int order = 0;
            for (RecipeItemRequest itemReq : req.getItems()) {
                FactoryMaterial mat = materialRepo.findById(itemReq.getFactoryMaterialId())
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy NVL"));
                recipe.getItems().add(ProductionRecipeItem.builder()
                        .recipe(recipe)
                        .factoryMaterial(mat)
                        .standardQty(itemReq.getStandardQty())
                        .unit(itemReq.getUnit() != null ? itemReq.getUnit() : mat.getUnit())
                        .sortOrder(itemReq.getSortOrder() != null ? itemReq.getSortOrder() : order)
                        .build());
                order++;
            }
        }
        return toRecipeDto(recipeRepo.save(recipe));
    }

    public void toggleRecipe(Long id, boolean active) {
        ProductionRecipe r = recipeRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy công thức"));
        r.setIsActive(active);
        recipeRepo.save(r);
    }

    // ─── ProductionBatch (Factory Worker tạo) ────────────────────────────────

    public Page<ProductionBatchDto> listBatches(int page, int size, Long userId) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ProductionBatch> raw = userId != null
                ? batchRepo.findByCreatedBy_IdOrderByCreatedAtDesc(userId, pageable)
                : batchRepo.findAllByOrderByCreatedAtDesc(pageable);
        return raw.map(this::toBatchDto);
    }

    public ProductionBatchDto getBatch(Long id) {
        return toBatchDto(batchRepo.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mẻ sản xuất")));
    }

    public ProductionBatchDto createBatch(CreateBatchRequest req, String username) {
        User worker = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        ProductionRecipe recipe = recipeRepo.findByIdWithItems(req.getRecipeId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy công thức"));

        String code = generateBatchCode();
        long producedAt = req.getProducedAt() != null ? req.getProducedAt() : System.currentTimeMillis();

        // Tính % hao hụt thành phẩm
        BigDecimal stdOutput = recipe.getStandardOutputQty();
        BigDecimal actOutput = req.getActualOutputQty();
        BigDecimal outputVariancePct = calcVariancePct(actOutput, stdOutput);

        ProductionBatch batch = ProductionBatch.builder()
                .batchCode(code)
                .recipe(recipe)
                .productName(recipe.getFactoryProduct().getName())
                .recipeName(recipe.getName())
                .actualOutputQty(actOutput)
                .outputUnit(recipe.getOutputUnit())
                .producedAt(producedAt)
                .notes(req.getNotes())
                .status(ProductionBatch.BatchStatus.SUBMITTED)
                .createdBy(worker)
                .createdByName(worker.getFullName())
                .build();

        // Build items với variance
        if (req.getItems() != null) {
            // Map standard qty by material ID
            java.util.Map<Long, BigDecimal> stdQtyMap = recipe.getItems().stream()
                    .collect(Collectors.toMap(
                            i -> i.getFactoryMaterial().getId(),
                            ProductionRecipeItem::getStandardQty));

            int order = 0;
            for (BatchItemRequest itemReq : req.getItems()) {
                FactoryMaterial mat = materialRepo.findById(itemReq.getFactoryMaterialId())
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy NVL"));
                BigDecimal stdQty = stdQtyMap.getOrDefault(mat.getId(), BigDecimal.ZERO);
                BigDecimal variance = calcVariancePct(itemReq.getActualQty(), stdQty);

                batch.getItems().add(ProductionBatchItem.builder()
                        .batch(batch)
                        .factoryMaterial(mat)
                        .materialName(mat.getName())
                        .actualQty(itemReq.getActualQty())
                        .unit(itemReq.getUnit() != null ? itemReq.getUnit() : mat.getUnit())
                        .standardQty(stdQty)
                        .variancePct(variance)
                        .sortOrder(itemReq.getSortOrder() != null ? itemReq.getSortOrder() : order)
                        .build());
                order++;
            }
        }

        ProductionBatch saved = batchRepo.save(batch);

        // Gửi thông báo cho OWNER
        String msg = String.format("Mẻ %s — %s vừa được nhập bởi %s (thực tế: %s %s)",
                saved.getBatchCode(),
                saved.getProductName(),
                saved.getCreatedByName(),
                saved.getActualOutputQty().stripTrailingZeros().toPlainString(),
                saved.getOutputUnit());
        String payload = String.format(
                "{\"batchId\":%d,\"batchCode\":\"%s\",\"productName\":\"%s\"}",
                saved.getId(), saved.getBatchCode(), saved.getProductName());
        notificationService.sendToRole("OWNER", "PRODUCTION_BATCH_SUBMITTED", msg, payload);

        return toBatchDto(saved);
    }

    public ProductionBatchDto markReviewed(Long id) {
        ProductionBatch batch = batchRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mẻ sản xuất"));
        batch.setStatus(ProductionBatch.BatchStatus.REVIEWED);
        return toBatchDto(batchRepo.save(batch));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Tính % lệch so với chuẩn.
     * = (actual - standard) / standard × 100
     * + = dùng nhiều hơn / ra nhiều hơn chuẩn
     * – = dùng ít hơn / ra ít hơn chuẩn
     */
    private BigDecimal calcVariancePct(BigDecimal actual, BigDecimal standard) {
        if (standard == null || standard.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return actual.subtract(standard)
                .divide(standard, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String generateBatchCode() {
        String date   = LocalDate.now(VN).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "BATCH-" + date + "-";
        long seq = batchRepo.countByBatchCodePrefix(prefix) + 1;
        return prefix + String.format("%04d", seq);
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private FactoryMaterialDto toMaterialDto(FactoryMaterial m) {
        return FactoryMaterialDto.builder()
                .id(m.getId()).name(m.getName()).unit(m.getUnit())
                .description(m.getDescription()).isActive(m.getIsActive())
                .createdAt(m.getCreatedAt()).build();
    }

    private FactoryProductDto toProductDto(FactoryProduct p) {
        return FactoryProductDto.builder()
                .id(p.getId()).name(p.getName()).unit(p.getUnit())
                .description(p.getDescription()).isActive(p.getIsActive())
                .createdAt(p.getCreatedAt()).build();
    }

    private ProductionRecipeDto toRecipeDto(ProductionRecipe r) {
        List<RecipeItemDto> items = r.getItems() == null ? List.of() : r.getItems().stream()
                .sorted(Comparator.comparingInt(i -> (i.getSortOrder() == null ? 0 : i.getSortOrder())))
                .map(i -> RecipeItemDto.builder()
                        .id(i.getId())
                        .factoryMaterialId(i.getFactoryMaterial().getId())
                        .materialName(i.getFactoryMaterial().getName())
                        .standardQty(i.getStandardQty())
                        .unit(i.getUnit())
                        .sortOrder(i.getSortOrder())
                        .build())
                .collect(Collectors.toList());

        return ProductionRecipeDto.builder()
                .id(r.getId())
                .factoryProductId(r.getFactoryProduct().getId())
                .factoryProductName(r.getFactoryProduct().getName())
                .name(r.getName())
                .standardOutputQty(r.getStandardOutputQty())
                .outputUnit(r.getOutputUnit())
                .notes(r.getNotes())
                .isActive(r.getIsActive())
                .createdByName(r.getCreatedByName())
                .createdAt(r.getCreatedAt())
                .items(items)
                .build();
    }

    private ProductionBatchDto toBatchDto(ProductionBatch b) {
        BigDecimal stdOutput = b.getRecipe() != null ? b.getRecipe().getStandardOutputQty() : BigDecimal.ZERO;
        BigDecimal outputVariancePct = calcVariancePct(b.getActualOutputQty(), stdOutput);

        List<BatchItemDto> items = b.getItems() == null ? List.of() : b.getItems().stream()
                .sorted(Comparator.comparingInt(i -> (i.getSortOrder() == null ? 0 : i.getSortOrder())))
                .map(i -> BatchItemDto.builder()
                        .id(i.getId())
                        .factoryMaterialId(i.getFactoryMaterial().getId())
                        .materialName(i.getMaterialName())
                        .actualQty(i.getActualQty())
                        .unit(i.getUnit())
                        .standardQty(i.getStandardQty())
                        .variancePct(i.getVariancePct())
                        .sortOrder(i.getSortOrder())
                        .build())
                .collect(Collectors.toList());

        return ProductionBatchDto.builder()
                .id(b.getId())
                .batchCode(b.getBatchCode())
                .recipeId(b.getRecipe() != null ? b.getRecipe().getId() : null)
                .productName(b.getProductName())
                .recipeName(b.getRecipeName())
                .actualOutputQty(b.getActualOutputQty())
                .standardOutputQty(stdOutput)
                .outputVariancePct(outputVariancePct)
                .outputUnit(b.getOutputUnit())
                .producedAt(b.getProducedAt())
                .notes(b.getNotes())
                .status(b.getStatus().name())
                .createdByName(b.getCreatedByName())
                .createdAt(b.getCreatedAt())
                .items(items)
                .build();
    }
}