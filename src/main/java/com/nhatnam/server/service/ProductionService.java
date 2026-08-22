package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.production.ProductionDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductionService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final FactoryMaterialRepository  materialRepo;
    private final FactoryProductRepository   productRepo;
    private final ProductionRecipeRepository recipeRepo;
    private final BatchStepTemplateRepository stepTemplateRepo;
    private final MachineRepository          machineRepo;
    private final ProductionBatchRepository  batchRepo;
    private final UserRepository             userRepo;
    // Cầu nối FactoryProduct → Ingredient (kho bán hàng) — xem comment ở FactoryProduct.ingredientId
    private final IngredientRepository       ingredientRepo;
    private final NotificationService        notificationService;
    private final FactoryMaterialStockRepository factoryMaterialStockRepository;
    private final FactoryMaterialCategoryRepository factoryMaterialCategoryRepo;
    private final FactoryMaterialSubCategoryRepository factoryMaterialSubCategoryRepo;
    private final ProductionFactoryRepository productionFactoryRepo;
    private static final Long SMALL_GOODS_CATEGORY_ID = 2L;

    // ─── FactoryMaterial ──────────────────────────────────────────────────────

    public List<FactoryMaterialDto> listMaterials(boolean activeOnly) {
        var list = activeOnly ? materialRepo.findByIsActiveTrueOrderByNameAsc()
                : materialRepo.findAllByOrderByNameAsc();
        return list.stream().map(this::toMaterialDto).collect(Collectors.toList());
    }

    public FactoryMaterialDto createMaterial(SaveFactoryMaterialRequest req) {
        return saveMaterial(null, req);
    }

    public FactoryMaterialDto saveMaterial(Long id, SaveFactoryMaterialRequest req) {
        boolean isNew = (id == null);
        FactoryMaterial m = isNew ? new FactoryMaterial()
                : materialRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy NVL"));

        // Snapshot tên+đơn vị CŨ (trước khi ghi đè) để cập nhật các lô tồn kho — Mục 6
        String oldName = isNew ? null : m.getName();
        String oldUnit = isNew ? null : m.getUnit();

        // ── Validate ──────────────────────────────────────────────────────────
        if (req.getName() == null || req.getName().isBlank())
            throw new BusinessException("Tên nguyên liệu là bắt buộc");
        if (req.getUnit() == null || req.getUnit().isBlank())
            throw new BusinessException("Đơn vị lưu kho là bắt buộc");
        if (req.getSubCategoryId() == null)
            throw new BusinessException("Vui lòng chọn danh mục chung và danh mục riêng");
        FactoryMaterialSubCategory sub = factoryMaterialSubCategoryRepo.findById(req.getSubCategoryId())
                .orElseThrow(() -> new BusinessException("Danh mục riêng không tồn tại"));

        // HSD & số ngày NCC giao: nếu có phải là số nguyên > 0 (kiểu Integer đã là nguyên)
        if (req.getShelfLifeDays() != null && req.getShelfLifeDays() <= 0)
            throw new BusinessException("Hạn sử dụng (số ngày) phải là số nguyên lớn hơn 0");
        if (req.getSupplierLeadDays() != null && req.getSupplierLeadDays() <= 0)
            throw new BusinessException("Số ngày NCC giao phải là số nguyên lớn hơn 0");

        // Đơn vị đặt hàng + tỷ lệ quy đổi
        String orderUnit = trimOrNull(req.getOrderUnit());
        BigDecimal ratio = req.getConversionRatio();
        if (orderUnit != null && !orderUnit.equalsIgnoreCase(req.getUnit().trim())) {
            if (ratio == null || ratio.compareTo(BigDecimal.ZERO) <= 0)
                throw new BusinessException("Tỷ lệ quy đổi phải > 0 khi đơn vị đặt hàng khác đơn vị lưu kho");
        }

        m.setName(req.getName().trim());
        m.setUnit(req.getUnit().trim());
        m.setOrderUnit(orderUnit);
        m.setConversionRatio(orderUnit == null ? null : ratio);
        m.setShelfLifeDays(req.getShelfLifeDays());
        m.setSupplierLeadDays(req.getSupplierLeadDays());
        m.setStorageCondition(trimOrNull(req.getStorageCondition()));
        m.setSubCategory(sub);
        m.setDescription(req.getDescription());
        m.setIsMixable(Boolean.TRUE.equals(req.getIsMixable()));
        if (m.getIsActive() == null) m.setIsActive(true);

        // Xưởng có nguyên liệu:
        //   - TẠO MỚI  → LUÔN gắn vào TẤT CẢ xưởng đang hoạt động (mô hình danh sách
        //     nguyên liệu dùng chung: nguyên liệu mới xuất hiện ở mọi kho xưởng).
        //   - SỬA      → dùng danh sách gửi lên (nếu có), nếu không gửi thì giữ nguyên.
        List<ProductionFactory> factories;
        if (isNew) {
            factories = productionFactoryRepo.findByStatusOrderByNameAsc(ProductionFactory.FactoryStatus.ACTIVE);
        } else if (req.getFactoryIds() != null && !req.getFactoryIds().isEmpty()) {
            factories = productionFactoryRepo.findAllById(req.getFactoryIds());
        } else {
            factories = m.getFactories() != null ? m.getFactories() : new ArrayList<>();
        }
        m.setFactories(new ArrayList<>(factories));

        FactoryMaterial saved = materialRepo.save(m);

        // Mục 6: khi SỬA tên/đơn vị → cập nhật snapshot ở TẤT CẢ lô tồn kho cũ
        // (materialName/unit được snapshot vào FactoryMaterialStock). Các xưởng dùng
        // chung 1 nguyên liệu nên sửa 1 lần áp cho mọi xưởng.
        if (!isNew && oldName != null && oldUnit != null
                && (!oldName.equals(saved.getName()) || !oldUnit.equals(saved.getUnit()))) {
            long ts = System.currentTimeMillis();
            List<FactoryMaterialStock> lots = factoryMaterialStockRepository
                    .findByMaterialNameAndUnit(oldName, oldUnit);
            for (FactoryMaterialStock lot : lots) {
                lot.setMaterialName(saved.getName());
                lot.setUnit(saved.getUnit());
                lot.setUpdatedAt(ts);
            }
            factoryMaterialStockRepository.saveAll(lots);
        }

        // Khi TẠO MỚI: tạo bản ghi tồn kho = 0 cho MỖI xưởng đã chọn để nguyên liệu
        // xuất hiện ngay ở đúng kho xưởng đó (dù chưa nhập hàng lần nào).
        if (isNew) {
            long now = System.currentTimeMillis();
            if (factories.isEmpty()) {
                factoryMaterialStockRepository.save(zeroStock(saved, null, now));
            } else {
                for (ProductionFactory f : factories) {
                    factoryMaterialStockRepository.save(zeroStock(saved, f, now));
                }
            }
        }

        return toMaterialDto(saved);
    }

    private FactoryMaterialStock zeroStock(FactoryMaterial m, ProductionFactory f, long now) {
        FactoryMaterialStock fms = new FactoryMaterialStock();
        fms.setCreatedAt(now);
        fms.setUpdatedAt(now);
        fms.setQuantity(BigDecimal.ZERO);
        fms.setInitialQuantity(BigDecimal.ZERO);
        fms.setUnit(m.getUnit());
        fms.setMaterialName(m.getName());
        fms.setIsActive(true);
        fms.setProductionFactory(f);
        return fms;
    }

    private String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ─── Danh mục nguyên liệu xưởng (chung / riêng) ───────────────────────────

    public List<FactoryMaterialCategoryDto> listCategoriesWithSub() {
        return factoryMaterialCategoryRepo.findByIsActiveTrueOrderByNameAsc().stream()
                .map(c -> FactoryMaterialCategoryDto.builder()
                        .id(c.getId()).name(c.getName()).description(c.getDescription())
                        .isActive(c.getIsActive())
                        .subCategories(factoryMaterialSubCategoryRepo
                                .findByCategory_IdAndIsActiveTrueOrderByNameAsc(c.getId())
                                .stream().map(this::toSubDto).collect(Collectors.toList()))
                        .build())
                .collect(Collectors.toList());
    }

    public FactoryMaterialCategoryDto createCategory(SaveCategoryRequest req) {
        if (req.getName() == null || req.getName().isBlank())
            throw new BusinessException("Tên danh mục chung là bắt buộc");
        String name = req.getName().trim();
        factoryMaterialCategoryRepo.findByNameIgnoreCase(name).ifPresent(x -> {
            throw new BusinessException("Danh mục chung \"" + name + "\" đã tồn tại");
        });
        FactoryMaterialCategory c = factoryMaterialCategoryRepo.save(FactoryMaterialCategory.builder()
                .name(name).description(trimOrNull(req.getDescription())).isActive(true).build());
        return FactoryMaterialCategoryDto.builder()
                .id(c.getId()).name(c.getName()).description(c.getDescription())
                .isActive(c.getIsActive()).subCategories(new ArrayList<>()).build();
    }

    public FactoryMaterialSubCategoryDto createSubCategory(SaveSubCategoryRequest req) {
        if (req.getName() == null || req.getName().isBlank())
            throw new BusinessException("Tên danh mục riêng là bắt buộc");
        if (req.getCategoryId() == null)
            throw new BusinessException("Vui lòng chọn danh mục chung cha");
        FactoryMaterialCategory cat = factoryMaterialCategoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new BusinessException("Danh mục chung không tồn tại"));
        FactoryMaterialSubCategory s = factoryMaterialSubCategoryRepo.save(FactoryMaterialSubCategory.builder()
                .name(req.getName().trim()).category(cat)
                .description(trimOrNull(req.getDescription())).isActive(true).build());
        return toSubDto(s);
    }

    private FactoryMaterialSubCategoryDto toSubDto(FactoryMaterialSubCategory s) {
        return FactoryMaterialSubCategoryDto.builder()
                .id(s.getId()).name(s.getName())
                .categoryId(s.getCategory() != null ? s.getCategory().getId() : null)
                .categoryName(s.getCategory() != null ? s.getCategory().getName() : null)
                .description(s.getDescription()).isActive(s.getIsActive()).build();
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
        return list.stream()
                .map(this::toProductDto)
                .filter(Objects::nonNull) // loại các product không thuộc Small Goods (hoặc chưa liên kết Ingredient)
                .collect(Collectors.toList());
    }

    public FactoryProductDto saveProduct(Long id, SaveFactoryProductRequest req) {
        FactoryProduct p = id == null ? new FactoryProduct()
                : productRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành phẩm"));

        if (req.getIngredientId() != null) {
            // Liên kết Ingredient → đồng bộ name/unit từ Ingredient
            Ingredient ing = ingredientRepo.findById(req.getIngredientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nguyên liệu/hàng hoá gốc"));
            p.setIngredientId(ing.getId());
            p.setName(ing.getName());
            p.setUnit(ing.getUnit());
        } else if (id == null) {
            // Tạo mới không liên kết ingredient → yêu cầu nhập tên + đơn vị
            if (req.getName() == null || req.getName().isBlank())
                throw new BusinessException("Vui lòng nhập tên sản phẩm hoặc chọn nguyên liệu gốc");
            if (req.getUnit() == null || req.getUnit().isBlank())
                throw new BusinessException("Vui lòng nhập đơn vị sản phẩm");
            p.setName(req.getName().trim());
            p.setUnit(req.getUnit().trim());
        } else if (req.getName() != null) {
            // Sửa: cập nhật tên/đơn vị trực tiếp
            p.setName(req.getName().trim());
            if (req.getUnit() != null) p.setUnit(req.getUnit().trim());
        }

        p.setDescription(req.getDescription());
        if (p.getIsActive() == null) p.setIsActive(true);
        return toProductDto(productRepo.save(p));
    }

    // ─── ProductionRecipe = Biến thể sản xuất (do FACTORY_WORKER tạo/sửa) ─────

    public List<ProductionRecipeDto> listRecipes(Long productId) {
        var list = productId != null
                ? recipeRepo.findByFactoryProduct_IdAndIsActiveTrueOrderByCreatedAtDesc(productId)
                : recipeRepo.findByIsActiveTrueOrderByCreatedAtDesc();
        return list.stream().map(this::toRecipeDto).collect(Collectors.toList());
    }

    public ProductionRecipeDto getRecipe(Long id) {
        // Không dùng findByIdWithItemsAndSteps — JOIN FETCH đồng thời 2 collection
        // (items + steps) gây MultipleBagFetchException hoặc nhân dòng kết quả sai
        // (cartesian product). Fetch riêng "items" qua query, rồi trigger lazy-load
        // "steps" tách biệt (cùng transaction nên không lỗi LazyInitializationException).
        ProductionRecipe recipe = recipeRepo.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy biến thể sản xuất"));
        recipe.getSteps().size(); // trigger lazy load
        return toRecipeDto(recipe);
    }

    public ProductionRecipeDto saveRecipe(Long id, SaveRecipeRequest req, String username) {
        User creator = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        FactoryProduct fp = productRepo.findById(req.getFactoryProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành phẩm"));

        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("Tên biến thể không được để trống");
        }

        // ── Kiểm tra trùng tên biến thể trong cùng 1 FactoryProduct ─────────
        boolean nameTaken = id == null
                ? recipeRepo.existsByFactoryProduct_IdAndNameIgnoreCase(fp.getId(), req.getName().trim())
                : recipeRepo.existsByFactoryProduct_IdAndNameIgnoreCaseAndIdNot(fp.getId(), req.getName().trim(), id);
        if (nameTaken) {
            throw new IllegalArgumentException(
                    "Tên biến thể \"" + req.getName().trim() + "\" đã được dùng cho sản phẩm này, vui lòng chọn tên khác");
        }

        ProductionRecipe recipe = id == null ? new ProductionRecipe()
                : recipeRepo.findByIdWithItems(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy biến thể sản xuất"));
        // Lưu ý: KHÔNG dùng findByIdWithItemsAndSteps ở đây — JOIN FETCH đồng thời 2
        // collection (items + steps) trong CÙNG 1 câu query sẽ bị Hibernate báo lỗi
        // MultipleBagFetchException (cartesian product giữa 2 bag), hoặc nếu không lỗi
        // thì số dòng bị nhân lên (VD: 1 item × 2 step ra 2 dòng SQL → khi đọc lại có
        // thể hiển thị sai số lượng step). Ở đây fetch riêng "items" qua query, rồi
        // chủ động trigger lazy-load "steps" bằng 1 câu lệnh riêng (vẫn trong cùng
        // transaction/session nên không lỗi LazyInitializationException).
        if (id != null) recipe.getSteps().size(); // trigger lazy load steps tách biệt khỏi items

        recipe.setFactoryProduct(fp);
        recipe.setName(req.getName().trim());
        recipe.setStandardOutputQty(req.getStandardOutputQty());
        recipe.setOutputUnit(req.getOutputUnit() != null ? req.getOutputUnit() : fp.getUnit());
        // Định lượng đóng gói chuẩn — nullable, chỉ dùng để ước tính số gói dự kiến
        if (req.getPackagingQty() != null && req.getPackagingQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Định lượng đóng gói chuẩn phải lớn hơn 0");
        }
        recipe.setPackagingQty(req.getPackagingQty());
        recipe.setPackagingUnit(req.getPackagingQty() != null
                ? (req.getPackagingUnit() != null && !req.getPackagingUnit().isBlank() ? req.getPackagingUnit().trim() : "túi")
                : null);
        recipe.setNotes(req.getNotes());
        recipe.setCreatedBy(creator);
        recipe.setCreatedByName(creator.getFullName());
        if (recipe.getIsActive() == null) recipe.setIsActive(true);

        // Clear cả 2 collection rồi flush ngay — đảm bảo Hibernate thực thi DELETE
        // (orphanRemoval) cho các item/step cũ TRƯỚC KHI insert phần tử mới ở dưới.
        // Nếu không flush ở đây, trong một số trường hợp DELETE có thể bị trì hoãn
        // tới cuối transaction và chạy SAU INSERT, khiến dữ liệu cũ + mới cùng tồn
        // tại tạm thời (hoặc vĩnh viễn nếu sortOrder/id trùng) → hiển thị trùng bước.
        recipe.getItems().clear();
        recipe.getSteps().clear();
        recipeRepo.saveAndFlush(recipe);

        // Rebuild items (nguyên liệu)
        if (req.getItems() != null) {
            int order = 0;
            for (RecipeItemRequest itemReq : req.getItems()) {
                FactoryMaterial mat = materialRepo.findById(itemReq.getFactoryMaterialId())
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nguyên liệu"));
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

        // Rebuild steps (các bước xử lý) — đã clear() + flush ở trên
        if (req.getSteps() != null) {
            int order = 0;
            for (RecipeStepRequest stepReq : req.getSteps()) {
                BatchStepTemplate template = null;
                String stepName = stepReq.getStepName();
                if (stepReq.getStepTemplateId() != null) {
                    template = stepTemplateRepo.findById(stepReq.getStepTemplateId())
                            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy mẫu bước"));
                    if (stepName == null || stepName.isBlank()) stepName = template.getName();
                }
                if (stepName == null || stepName.isBlank()) {
                    throw new IllegalArgumentException("Mỗi bước cần có tên hoặc chọn từ mẫu bước");
                }
                if (stepReq.getDurationMinutes() == null || stepReq.getDurationMinutes() <= 0) {
                    throw new IllegalArgumentException("Bước \"" + stepName + "\" cần khai báo thời gian hoàn thành (phút) lớn hơn 0");
                }

                Machine machine = null;
                if (stepReq.getMachineId() != null) {
                    machine = machineRepo.findById(stepReq.getMachineId())
                            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy máy móc"));
                }

                // Loại kiểm soát: NONE | VISUAL | PHOTO_WEIGHT — fallback từ requiresQc cũ nếu FE chưa gửi controlType
                ProductionRecipeStep.ControlType controlType;
                try {
                    controlType = stepReq.getControlType() != null && !stepReq.getControlType().isBlank()
                            ? ProductionRecipeStep.ControlType.valueOf(stepReq.getControlType().trim().toUpperCase())
                            : (stepReq.isRequiresQc() ? ProductionRecipeStep.ControlType.PHOTO_WEIGHT : ProductionRecipeStep.ControlType.NONE);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Loại kiểm soát không hợp lệ: " + stepReq.getControlType());
                }
                // requiresQc giữ lại để tương thích dữ liệu cũ: true khi có bất kỳ hình thức kiểm soát nào yêu cầu xác nhận
                boolean requiresQcLegacy = controlType != ProductionRecipeStep.ControlType.NONE;

                recipe.getSteps().add(ProductionRecipeStep.builder()
                        .recipe(recipe)
                        .stepTemplate(template)
                        .stepName(stepName.trim())
                        .sortOrder(stepReq.getSortOrder() != null ? stepReq.getSortOrder() : order)
                        .requiresQc(requiresQcLegacy)
                        .controlType(controlType)
                        .durationMinutes(stepReq.getDurationMinutes())
                        .machine(machine)
                        .machineName(machine != null ? machine.getName() : null)
                        .shared(stepReq.isShared())
                        .capacityPerRun(stepReq.isShared() ? stepReq.getCapacityPerRun() : null)
                        .build());
                order++;
            }
        }

        return toRecipeDto(recipeRepo.save(recipe));
    }

    private static final String SOFT_DELETE_PREFIX = "SOFTDELETED_";

    /**
     * Tắt/mở 1 biến thể sản xuất (soft delete — không xoá cứng vì các lệnh sản
     * xuất/mẻ đã dùng biến thể này vẫn cần giữ liên kết để xem lại lịch sử).
     *
     * Khi TẮT (active=false): đổi tên thêm tiền tố "SOFTDELETED_" để giải phóng
     * tên gốc — cho phép tạo biến thể MỚI với tên trùng tên đã bị tắt, vì check
     * trùng tên (existsByFactoryProduct_IdAndNameIgnoreCase...) chỉ tính theo
     * tên hiện tại trong DB, không loại trừ theo isActive.
     *
     * Khi MỞ LẠI (active=true): tự bỏ tiền tố để khôi phục tên gốc — NHƯNG chỉ
     * khi tên gốc đó chưa bị 1 biến thể khác (đang active) chiếm; nếu đã bị
     * chiếm, báo lỗi rõ ràng để người dùng tự đổi tên trước khi mở lại, tránh
     * tự động đổi sang một tên khác mà người dùng không lường trước.
     */
    public void toggleRecipe(Long id, boolean active) {
        ProductionRecipe r = recipeRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy biến thể sản xuất"));

        if (!active) {
            // Tắt: gắn tiền tố để giải phóng tên gốc (nếu chưa có tiền tố — tránh
            // tắt nhiều lần liên tiếp làm tên bị cộng dồn "SOFTDELETED_SOFTDELETED_...")
            if (r.getName() != null && !r.getName().startsWith(SOFT_DELETE_PREFIX)) {
                r.setName(SOFT_DELETE_PREFIX + r.getName());
            }
        } else {
            // Mở lại: bỏ tiền tố để khôi phục tên gốc, nếu có
            if (r.getName() != null && r.getName().startsWith(SOFT_DELETE_PREFIX)) {
                String originalName = r.getName().substring(SOFT_DELETE_PREFIX.length());
                boolean nameTaken = recipeRepo.existsByFactoryProduct_IdAndNameIgnoreCaseAndIdNot(
                        r.getFactoryProduct().getId(), originalName, r.getId());
                if (nameTaken) {
                    throw new IllegalArgumentException(
                            "Không thể mở lại: tên \"" + originalName + "\" đã được dùng cho một biến thể khác đang hoạt động. " +
                                    "Vui lòng đổi tên biến thể đó trước, hoặc liên hệ quản trị viên.");
                }
                r.setName(originalName);
            }
        }

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
        FactoryMaterialSubCategory sub = m.getSubCategory();
        FactoryMaterialCategory cat = sub != null ? sub.getCategory() : null;
        List<ProductionFactory> factories = m.getFactories() != null ? m.getFactories() : new ArrayList<>();
        return FactoryMaterialDto.builder()
                .id(m.getId()).name(m.getName())
                .unit(m.getUnit())
                .orderUnit(m.getOrderUnit())
                .conversionRatio(m.getConversionRatio())
                .shelfLifeDays(m.getShelfLifeDays())
                .supplierLeadDays(m.getSupplierLeadDays())
                .storageCondition(m.getStorageCondition())
                .description(m.getDescription())
                .isMixable(m.getIsMixable())
                .isActive(m.getIsActive())
                .createdAt(m.getCreatedAt())
                .subCategoryId(sub != null ? sub.getId() : null)
                .subCategoryName(sub != null ? sub.getName() : null)
                .categoryId(cat != null ? cat.getId() : null)
                .categoryName(cat != null ? cat.getName() : null)
                .factoryIds(factories.stream().map(ProductionFactory::getId).collect(Collectors.toList()))
                .factoryNames(factories.stream().map(ProductionFactory::getName).collect(Collectors.toList()))
                .build();
    }

    private FactoryProductDto toProductDto(FactoryProduct p) {
        String ingredientName = null;
        if (p.getIngredientId() != null) {
            Ingredient ingredient = ingredientRepo.findById(p.getIngredientId()).orElse(null);
            // Giữ điều kiện cũ: sản phẩm CÓ liên kết ingredient chỉ hiển thị
            // nếu ingredient thuộc danh mục Small Goods (category id = 2)
            if (ingredient == null || ingredient.getCategoryId() == null
                    || !SMALL_GOODS_CATEGORY_ID.equals(ingredient.getCategoryId())) {
                return null;
            }
            ingredientName = ingredient.getName();
        }
        // Sản phẩm KHÔNG có ingredientId → cho qua (tạo trực tiếp, không liên kết kho bán)

        return FactoryProductDto.builder()
                .id(p.getId()).name(p.getName()).unit(p.getUnit())
                .description(p.getDescription()).isActive(p.getIsActive())
                .createdAt(p.getCreatedAt())
                .ingredientId(p.getIngredientId())
                .ingredientName(ingredientName)
                .build();
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

        List<RecipeStepDto> steps = r.getSteps() == null ? List.of() : r.getSteps().stream()
                .sorted(Comparator.comparingInt(s -> (s.getSortOrder() == null ? 0 : s.getSortOrder())))
                .map(s -> RecipeStepDto.builder()
                        .id(s.getId())
                        .stepTemplateId(s.getStepTemplate() != null ? s.getStepTemplate().getId() : null)
                        .stepName(s.getStepName())
                        .sortOrder(s.getSortOrder())
                        .requiresQc(s.isRequiresQc())
                        .controlType((s.getControlType() != null ? s.getControlType()
                                : (s.isRequiresQc() ? ProductionRecipeStep.ControlType.PHOTO_WEIGHT : ProductionRecipeStep.ControlType.NONE)).name())
                        .durationMinutes(s.getDurationMinutes())
                        .machineId(s.getMachine() != null ? s.getMachine().getId() : null)
                        .machineName(s.getMachineName())
                        .shared(s.isShared())
                        .capacityPerRun(s.getCapacityPerRun())
                        .build())
                .collect(Collectors.toList());

        return ProductionRecipeDto.builder()
                .id(r.getId())
                .factoryProductId(r.getFactoryProduct().getId())
                .factoryProductName(r.getFactoryProduct().getName())
                .name(r.getName())
                .standardOutputQty(r.getStandardOutputQty())
                .outputUnit(r.getOutputUnit())
                .packagingQty(r.getPackagingQty())
                .packagingUnit(r.getPackagingUnit())
                .notes(r.getNotes())
                .isActive(r.getIsActive())
                .createdByName(r.getCreatedByName())
                .createdAt(r.getCreatedAt())
                .items(items)
                .steps(steps)
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