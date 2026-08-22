package com.nhatnam.server.service.admin;

import com.nhatnam.server.dto.ingredient.IngredientStockRowDto;
import com.nhatnam.server.entity.Category;
import com.nhatnam.server.entity.IngredientExpiry;
import com.nhatnam.server.entity.IngredientStock;
import com.nhatnam.server.entity.SubCategory;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.repository.CategoryRepository;
import com.nhatnam.server.repository.IngredientExpiryRepository;
import com.nhatnam.server.repository.IngredientStockRepository;
import com.nhatnam.server.repository.SubCategoryRepository;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.repository.WarehouseRepository;
import com.nhatnam.server.common.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IngredientAdminService {

    private final IngredientStockRepository  ingredientStockRepository;
    private final IngredientExpiryRepository ingredientExpiryRepository;
    private final WarehouseRepository        warehouseRepository;
    private final CategoryRepository         categoryRepository;
    private final SubCategoryRepository      subCategoryRepository;
    private final IngredientRepository       ingredientRepository;

    private static final int DAYS_DANGER  = 30;
    private static final int DAYS_WARNING = 90;

    /** Dưới 7 ngày tới hạn = cực gấp (đỏ cam). */
    private static final int DAYS_CRITICAL = 7;
    /** Nhập trong vòng 30 ngày = hàng mới (xanh dương nhạt). */
    private static final int DAYS_NEWLY_STOCKED = 30;

    @Transactional(readOnly = true)
    public List<IngredientStockRowDto> getStockRows(Long warehouseId, String q) {
        LocalDate today            = LocalDate.now();
        LocalDate threshold1Month  = today.plusDays(DAYS_DANGER);
        LocalDate threshold3Months = today.plusDays(DAYS_WARNING);

        // ── Lookup maps cho category / subCategory ────────────────────────────
        Map<Long, String> catMap = categoryRepository.findByIsActiveTrueOrderByNameAsc()
                .stream().collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Long, String> subCatMap = subCategoryRepository.findByIsActiveTrueOrderByNameAsc()
                .stream().collect(Collectors.toMap(SubCategory::getId, SubCategory::getName));

        // Lấy tất cả lô HSD của kho này (quantity > 0) — phân nhóm theo ingredientId
        List<IngredientExpiry> allLots = ingredientExpiryRepository.findByWarehouseId(warehouseId)
                .stream()
                .filter(e -> e.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        Map<Long, List<IngredientExpiry>> lotsByIngredient = allLots.stream()
                .collect(Collectors.groupingBy(e -> e.getIngredientId()));

        // Lấy stock rows
        List<IngredientStock> stocks = ingredientStockRepository.findByWarehouse(warehouseId, q);

        List<IngredientStockRowDto> result = new ArrayList<>();
        // Load ingredient metadata để lấy categoryId/subCategoryId
        Set<Long> allIngIds = stocks.stream().map(IngredientStock::getIngredientId).collect(java.util.stream.Collectors.toSet());
        Map<Long, Ingredient> ingMap = ingredientRepository.findAllById(allIngIds).stream().collect(Collectors.toMap(Ingredient::getId, i -> i));
        for (IngredientStock s : stocks) {
            Long ingId = s.getIngredientId();

            // --- Lots sorted FIFO (expiryDate ASC, null cuối) ---
            List<IngredientExpiry> lots = lotsByIngredient.getOrDefault(ingId, List.of())
                    .stream()
                    .sorted(Comparator.comparing(
                            (IngredientExpiry e) -> e.getExpiryDate(),
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());

            // --- Expiry summary ---
            LocalDate earliest = lots.stream()
                    .map(IngredientExpiry::getExpiryDate)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);

            BigDecimal nearQty = lots.stream()
                    .filter(e -> e.getExpiryDate() != null && !e.getExpiryDate().isAfter(threshold3Months))
                    .map(IngredientExpiry::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Integer daysUntil = null;
            String badge = "NONE";
            if (earliest != null) {
                long days = ChronoUnit.DAYS.between(today, earliest);
                daysUntil = (int) days;
                if (!earliest.isAfter(threshold1Month)) badge = "DANGER";
                else if (!earliest.isAfter(threshold3Months)) badge = "WARNING";
            }

            // --- Tính totalCostValue từ lô thực tế (FIFO-accurate) ---
            BigDecimal totalCostValue = lots.stream()
                    .filter(e -> e.getCostPrice() != null)
                    .map(e -> e.getQuantity().multiply(e.getCostPrice()).setScale(2, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Nếu không có lô nào có costPrice, fallback về totalCostValue trong IngredientStock
            if (totalCostValue.compareTo(BigDecimal.ZERO) == 0 && s.getTotalCostValue() != null) {
                totalCostValue = s.getTotalCostValue();
            }

            // --- Build LotDto list ---
            List<IngredientStockRowDto.LotDto> lotDtos = lots.stream().map(e -> {
                BigDecimal lotCost = (e.getCostPrice() != null)
                        ? e.getQuantity().multiply(e.getCostPrice()).setScale(2, RoundingMode.HALF_UP)
                        : null;
                return IngredientStockRowDto.LotDto.builder()
                        .expiryDate(e.getExpiryDate())
                        .quantity(e.getQuantity())
                        .costPrice(e.getCostPrice())
                        .totalCost(lotCost)
                        .importedAt(e.getCreatedAt())
                        .build();
            }).collect(Collectors.toList());

            // --- Màu tình trạng lô, ưu tiên gắt nhất trước ---
            //   Chỉ xét các lô CÒN HÀNG (đã lọc quantity > 0 ở trên). Khi lô đỏ
            //   xuất hết thì vòng sau nó không còn trong danh sách nữa, badge tự
            //   rớt xuống mức thấp hơn — đúng như mô tả "xuất hết lô 1 thì thành
            //   vàng, xuất hết lô 2 thì thành xanh".
            String freshnessBadge = computeFreshnessBadge(lots, today);

            Ingredient _ing = ingMap.get(s.getIngredientId());
            Long catId    = _ing != null ? _ing.getCategoryId() : null;
            Long subCatId = _ing != null ? _ing.getSubCategoryId() : null;

            result.add(IngredientStockRowDto.builder()
                    .ingredientId(s.getIngredientId())
                    .ingredientName(s.resolvedName())
                    .ingredientImageUrl(null)
                    .unit(s.resolvedUnit())
                    .categoryId(catId)
                    .categoryName(catId != null ? catMap.getOrDefault(catId, "Chưa phân loại") : "Chưa phân loại")
                    .subCategoryId(subCatId)
                    .subCategoryName(subCatId != null ? subCatMap.getOrDefault(subCatId, "Chưa phân loại") : null)
                    .stockQuantity(s.getStockQuantity())
                    .nearestExpiryDate(earliest)
                    .nearExpiryQuantity(nearQty)
                    .daysUntilExpiry(daysUntil)
                    .expiryBadge(badge)
                    .freshnessBadge(freshnessBadge)
                    .totalCostValue(totalCostValue)
                    .lots(lotDtos)
                    .updatedAt(s.getUpdatedAt())
                    .build());
        }
        return result;
    }

    /**
     * MÀU TÌNH TRẠNG của một nguyên liệu, xét trên các lô CÒN HÀNG.
     *
     * <p>Ưu tiên gắt nhất thắng — một nguyên liệu vừa có lô hết hạn vừa có lô mới
     * nhập thì hiện đỏ cam, vì rủi ro hết hạn cần xử lý trước. Khi lô gắt được
     * xuất hết, lần tải sau nó không còn trong danh sách và badge tự rớt xuống
     * mức nhẹ hơn.
     *
     * @param lots  các lô còn hàng (quantity &gt; 0) của nguyên liệu
     * @param today mốc so sánh
     */
    private String computeFreshnessBadge(List<IngredientExpiry> lots, LocalDate today) {
        boolean hasCritical = false;   // đã hết hạn hoặc < 7 ngày
        boolean hasNear     = false;   // < 1 tháng
        boolean hasNew      = false;   // mới nhập < 1 tháng

        long newlyStockedFloorMs = today.minusDays(DAYS_NEWLY_STOCKED)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

        for (IngredientExpiry lot : lots) {
            LocalDate exp = lot.getExpiryDate();
            if (exp != null) {
                long days = ChronoUnit.DAYS.between(today, exp);
                // days < 0 = đã hết hạn; 0..6 = cực gấp. Cả hai vào mức đỏ cam.
                if (days < DAYS_CRITICAL) hasCritical = true;
                else if (days <= DAYS_DANGER) hasNear = true;
            }
            // "Mới nhập" xét theo thời điểm nhập lô, độc lập với hạn dùng.
            if (lot.getCreatedAt() != null && lot.getCreatedAt() >= newlyStockedFloorMs) {
                hasNew = true;
            }
        }

        if (hasCritical) return "EXPIRED_OR_CRITICAL";
        if (hasNear)     return "NEAR_EXPIRY";
        if (hasNew)      return "NEWLY_STOCKED";
        return "NONE";
    }

    public Long getDefaultWarehouseId() {
        List<Warehouse> list = warehouseRepository.findAllByOrderByIdAsc();
        if (list.isEmpty()) throw new ResourceNotFoundException("Chưa có kho nào trong hệ thống");
        return list.get(0).getId();
    }
}