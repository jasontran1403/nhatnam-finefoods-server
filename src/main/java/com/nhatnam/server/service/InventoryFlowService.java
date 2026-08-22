package com.nhatnam.server.service;

import com.nhatnam.server.dto.WarehouseDTO;
import com.nhatnam.server.dto.inventory.*;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.IngredientStock;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.IngredientStockRepository;
import com.nhatnam.server.repository.WarehouseReceiptItemRepository;
import com.nhatnam.server.repository.WarehouseRepository;
import com.nhatnam.server.service.serviceimpl.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Tồn kho theo kỳ (đầu kỳ · phát sinh · cuối kỳ), giống trang Dòng tiền.
 * <ul>
 *   <li>Tồn hiện tại: {@link IngredientStock#getStockQuantity()} (nguồn chuẩn của app).</li>
 *   <li>Phát sinh: tái dựng từ sổ phiếu kho ({@code WarehouseReceiptItem.difference}).</li>
 *   <li>Đầu kỳ = tồn hiện tại − tổng phát sinh từ mốc đầu kỳ tới nay.</li>
 *   <li>Cuối kỳ = tồn hiện tại − tổng phát sinh sau mốc cuối kỳ.</li>
 * </ul>
 * Phân loại: Nhập = IMPORT + TRANSFER_IN + ADJUST(+); Bán = EXPORT_ORDER;
 * Xuất = EXPORT_OTHER + TRANSFER_OUT + ADJUST(−).
 */
@Service
@RequiredArgsConstructor
public class InventoryFlowService {

    private final IngredientRepository ingredientRepository;
    private final WarehouseRepository warehouseRepository;
    private final IngredientStockRepository ingredientStockRepository;
    private final WarehouseReceiptItemRepository receiptItemRepository;
    private final WarehouseService warehouseService;
    private final com.nhatnam.server.repository.CategoryRepository categoryRepository;
    private final com.nhatnam.server.repository.SubCategoryRepository subCategoryRepository;
    private final com.nhatnam.server.repository.ProductionFactoryRepository productionFactoryRepository;
    private final com.nhatnam.server.repository.FactoryMaterialStockRepository factoryMaterialStockRepository;
    private final com.nhatnam.server.repository.FinishedGoodsStockRepository finishedGoodsStockRepository;

    @Transactional(readOnly = true)
    public InventorySummaryDto summary(long from, long to, String q) {
        long now = System.currentTimeMillis();
        long toClamped = Math.min(to, now);
        if (toClamped < from) toClamped = from;

        // Kho đang hoạt động
        List<Warehouse> warehouses = warehouseRepository.findByActiveTrueOrderByIdAsc();
        List<WarehouseLiteDto> whDtos = warehouses.stream()
                .map(w -> WarehouseLiteDto.builder().id(w.getId()).name(w.getName()).build())
                .toList();

        // Map danh mục / danh mục con → tên
        java.util.Map<Long, String> catNames = new java.util.HashMap<>();
        categoryRepository.findAll().forEach(c -> catNames.put(c.getId(), c.getName()));
        java.util.Map<Long, String> subNames = new java.util.HashMap<>();
        subCategoryRepository.findAll().forEach(sc -> subNames.put(sc.getId(), sc.getName()));

        // Nguyên liệu đang hoạt động (+ lọc theo search)
        String qn = q == null ? "" : q.trim().toLowerCase();
        List<Ingredient> ingredients = ingredientRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .filter(i -> qn.isEmpty() || (i.getName() != null && i.getName().toLowerCase().contains(qn)))
                .toList();

        // Tồn hiện tại: (ing, wh) → qty
        Map<Long, Map<Long, BigDecimal>> current = new HashMap<>();
        for (IngredientStock s : ingredientStockRepository.findAll()) {
            if (s.getWarehouse() == null) continue;
            current.computeIfAbsent(s.getIngredientId(), k -> new HashMap<>())
                    .merge(s.getWarehouse().getId(), nz(s.getStockQuantity()), BigDecimal::add);
        }

        // Tổng phát sinh từ đầu kỳ tới nay & sau cuối kỳ
        Map<Long, Map<Long, BigDecimal>> sinceFrom = toNestedMap(receiptItemRepository.sumDiffSince(from));
        Map<Long, Map<Long, BigDecimal>> afterTo = (toClamped < now)
                ? toNestedMap(receiptItemRepository.sumDiffAfter(toClamped))
                : new HashMap<>();

        // Phát sinh trong kỳ: (ing, wh) → [nhap, ban, xuat]
        Map<Long, Map<Long, BigDecimal[]>> period = new HashMap<>();
        for (Object[] row : receiptItemRepository.periodBreakdown(from, toClamped)) {
            Long ingId = (Long) row[0];
            Long whId = (Long) row[1];
            ReceiptType type = (ReceiptType) row[2];
            BigDecimal pos = nz((BigDecimal) row[3]);
            BigDecimal neg = nz((BigDecimal) row[4]); // <= 0
            BigDecimal[] cell = period.computeIfAbsent(ingId, k -> new HashMap<>())
                    .computeIfAbsent(whId, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            switch (type) {
                case IMPORT, TRANSFER_IN -> cell[0] = cell[0].add(pos);
                case EXPORT_ORDER        -> cell[1] = cell[1].add(neg.negate());
                case EXPORT_OTHER, TRANSFER_OUT -> cell[2] = cell[2].add(neg.negate());
                case ADJUST -> { cell[0] = cell[0].add(pos); cell[2] = cell[2].add(neg.negate()); }
            }
        }

        // Dựng DTO theo từng nguyên liệu
        List<InventoryIngredientDto> ingDtos = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            Long ingId = ing.getId();
            List<InventoryCellDto> cells = new ArrayList<>();
            BigDecimal tOpen = BigDecimal.ZERO, tNhap = BigDecimal.ZERO, tBan = BigDecimal.ZERO,
                    tXuat = BigDecimal.ZERO, tClose = BigDecimal.ZERO;

            for (Warehouse w : warehouses) {
                Long whId = w.getId();
                BigDecimal cur = get(current, ingId, whId);
                BigDecimal opening = cur.subtract(get(sinceFrom, ingId, whId));
                BigDecimal closing = cur.subtract(get(afterTo, ingId, whId));
                BigDecimal[] pv = period.getOrDefault(ingId, Map.of())
                        .getOrDefault(whId, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                BigDecimal nhap = pv[0], ban = pv[1], xuat = pv[2];

                if (isZero(opening) && isZero(closing) && isZero(nhap) && isZero(ban) && isZero(xuat))
                    continue; // bỏ ô rỗng hoàn toàn

                cells.add(InventoryCellDto.builder()
                        .warehouseId(whId).opening(opening).nhap(nhap).ban(ban).xuat(xuat).closing(closing)
                        .build());
                tOpen = tOpen.add(opening); tNhap = tNhap.add(nhap); tBan = tBan.add(ban);
                tXuat = tXuat.add(xuat); tClose = tClose.add(closing);
            }

            if (cells.isEmpty()) continue; // nguyên liệu không có gì trong kỳ

            ingDtos.add(InventoryIngredientDto.builder()
                    .ingredientId(ingId).name(ing.getName()).unit(ing.getUnit()).imageUrl(ing.getImageUrl())
                    .categoryId(ing.getCategoryId())
                    .categoryName(ing.getCategoryId() != null ? catNames.get(ing.getCategoryId()) : null)
                    .subCategoryId(ing.getSubCategoryId())
                    .subCategoryName(ing.getSubCategoryId() != null ? subNames.get(ing.getSubCategoryId()) : null)
                    .totalOpening(tOpen).totalNhap(tNhap).totalBan(tBan).totalXuat(tXuat).totalClosing(tClose)
                    .byWarehouse(cells)
                    .build());
        }

        return InventorySummaryDto.builder()
                .from(from).to(toClamped).warehouses(whDtos).ingredients(ingDtos)
                .build();
    }

    /**
     * Tồn kho theo XƯỞNG (bảng riêng cho trang Kho hàng của Owner). Gồm kho nguyên
     * liệu + kho thành phẩm của từng xưởng đang hoạt động, gộp theo tên||đơn vị.
     * Không phụ thuộc kỳ from/to — chỉ hiển thị tồn hiện tại.
     */
    @Transactional(readOnly = true)
    public List<com.nhatnam.server.dto.inventory.FactoryInventoryDto.FactoryBlock> factoryStockOverview(String q) {
        String qn = q == null ? "" : q.trim().toLowerCase();
        List<com.nhatnam.server.dto.inventory.FactoryInventoryDto.FactoryBlock> blocks = new java.util.ArrayList<>();

        for (com.nhatnam.server.entity.ProductionFactory f : productionFactoryRepository
                .findByStatusOrderByNameAsc(com.nhatnam.server.entity.ProductionFactory.FactoryStatus.ACTIVE)) {

            // Kho nguyên liệu xưởng
            Map<String, BigDecimal[]> matAgg = new java.util.LinkedHashMap<>(); // key → [qty, lotCount]
            Map<String, String> matUnit = new java.util.HashMap<>();
            for (com.nhatnam.server.entity.FactoryMaterialStock s : factoryMaterialStockRepository
                    .findByProductionFactory_IdAndIsActiveTrueOrderByCreatedAtAsc(f.getId())) {
                if (s.getQuantity() == null || s.getQuantity().compareTo(BigDecimal.ZERO) <= 0) continue;
                if (!qn.isEmpty() && (s.getMaterialName() == null || !s.getMaterialName().toLowerCase().contains(qn))) continue;
                String key = s.getMaterialName() + "||" + s.getUnit();
                BigDecimal[] agg = matAgg.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                agg[0] = agg[0].add(s.getQuantity());
                agg[1] = agg[1].add(BigDecimal.ONE);
                matUnit.put(key, s.getUnit());
            }

            // Kho thành phẩm xưởng
            Map<String, BigDecimal[]> fgAgg = new java.util.LinkedHashMap<>();
            Map<String, String> fgUnit = new java.util.HashMap<>();
            for (com.nhatnam.server.entity.FinishedGoodsStock s : finishedGoodsStockRepository
                    .searchActiveLotsByFactory(qn.isEmpty() ? null : qn, f.getId())) {
                if (s.getQuantity() == null || s.getQuantity().compareTo(BigDecimal.ZERO) <= 0) continue;
                String key = s.getProductName() + "||" + s.getUnit();
                BigDecimal[] agg = fgAgg.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                agg[0] = agg[0].add(s.getQuantity());
                agg[1] = agg[1].add(BigDecimal.ONE);
                fgUnit.put(key, s.getUnit());
            }

            // bỏ qua xưởng rỗng hoàn toàn khi đang tìm kiếm
            if (!qn.isEmpty() && matAgg.isEmpty() && fgAgg.isEmpty()) continue;

            blocks.add(com.nhatnam.server.dto.inventory.FactoryInventoryDto.FactoryBlock.builder()
                    .factoryId(f.getId()).factoryName(f.getName())
                    .materials(toRows(matAgg, matUnit))
                    .finishedGoods(toRows(fgAgg, fgUnit))
                    .build());
        }
        return blocks;
    }

    private List<com.nhatnam.server.dto.inventory.FactoryInventoryDto.StockRow> toRows(
            Map<String, BigDecimal[]> agg, Map<String, String> unitMap) {
        List<com.nhatnam.server.dto.inventory.FactoryInventoryDto.StockRow> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : agg.entrySet()) {
            String name = e.getKey().split("\\|\\|", 2)[0];
            rows.add(com.nhatnam.server.dto.inventory.FactoryInventoryDto.StockRow.builder()
                    .name(name).unit(unitMap.get(e.getKey()))
                    .quantity(e.getValue()[0]).lotCount(e.getValue()[1].intValue())
                    .build());
        }
        rows.sort(java.util.Comparator.comparing(
                com.nhatnam.server.dto.inventory.FactoryInventoryDto.StockRow::getName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Danh sách nguyên liệu (cho modal xác nhận). */
    @Transactional(readOnly = true)
    public List<IngredientLiteDto> listIngredients(String q) {
        String qn = q == null ? "" : q.trim().toLowerCase();
        return ingredientRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .filter(i -> qn.isEmpty() || (i.getName() != null && i.getName().toLowerCase().contains(qn)))
                .map(i -> IngredientLiteDto.builder()
                        .id(i.getId()).name(i.getName()).unit(i.getUnit()).imageUrl(i.getImageUrl()).build())
                .toList();
    }

    /** Xác nhận số lượng thực tế cho NHIỀU nguyên liệu → 1 phiếu ĐIỀU CHỈNH nhiều dòng. */
    @Transactional
    public void confirm(User user, ConfirmInventoryRequest req) {
        if (req.getWarehouseId() == null)
            throw new com.nhatnam.server.common.BusinessException("Chưa chọn kho");
        if (req.getItems() == null || req.getItems().isEmpty())
            throw new com.nhatnam.server.common.BusinessException("Chưa chọn nguyên liệu");

        List<WarehouseDTO.AdjustItemRequest> items = new ArrayList<>();
        for (ConfirmInventoryRequest.Item it : req.getItems()) {
            if (it.getIngredientId() == null)
                throw new com.nhatnam.server.common.BusinessException("Thiếu nguyên liệu");
            if (it.getCountedQuantity() == null)
                throw new com.nhatnam.server.common.BusinessException("Thiếu số lượng cho một nguyên liệu");
            WarehouseDTO.AdjustItemRequest ai = new WarehouseDTO.AdjustItemRequest();
            ai.setIngredientId(it.getIngredientId());
            ai.setPhysicalQty(it.getCountedQuantity());
            items.add(ai);
        }

        WarehouseDTO.AdjustRequest adjust = new WarehouseDTO.AdjustRequest();
        adjust.setWarehouseId(req.getWarehouseId());
        adjust.setReason("Xác nhận kiểm kê tồn"
                + (req.getNote() != null && !req.getNote().isBlank() ? " — " + req.getNote().trim() : ""));
        adjust.setItems(items);

        warehouseService.adjustStock(adjust, user.getId());
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private Map<Long, Map<Long, BigDecimal>> toNestedMap(List<Object[]> rows) {
        Map<Long, Map<Long, BigDecimal>> m = new HashMap<>();
        for (Object[] r : rows) {
            Long ingId = (Long) r[0];
            Long whId = (Long) r[1];
            BigDecimal v = nz((BigDecimal) r[2]);
            m.computeIfAbsent(ingId, k -> new HashMap<>()).merge(whId, v, BigDecimal::add);
        }
        return m;
    }

    private BigDecimal get(Map<Long, Map<Long, BigDecimal>> m, Long ing, Long wh) {
        return m.getOrDefault(ing, Map.of()).getOrDefault(wh, BigDecimal.ZERO);
    }

    private BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private boolean isZero(BigDecimal v) { return v == null || v.compareTo(BigDecimal.ZERO) == 0; }
}
