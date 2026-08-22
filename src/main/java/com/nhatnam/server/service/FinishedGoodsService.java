package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.nhatnam.server.dto.production.FinishedGoodsDtos.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Kho thành phẩm của xưởng — cơ chế RIÊNG (Issue #1 + #2).
 *
 * - Khi hoàn thành 1 mẻ sản xuất → tạo 1 lô mới (FinishedGoodsStock) theo ngày SX + HSD.
 * - Xuất kho (export): trừ theo FIFO (ưu tiên lô gần hết hạn nhất), cần lý do.
 * - Chuyển kho (transfer): trừ theo FIFO ở kho thành phẩm, sau đó CỘNG vào kho bán hàng
 *   (Warehouse — IngredientStock + IngredientExpiry), giữ nguyên ngày SX + HSD gốc.
 *   Nếu chưa có Ingredient cùng tên ở hệ thống bán hàng → tự tạo mới (đồng bộ tên + đơn vị).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FinishedGoodsService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final FinishedGoodsStockRepository stockRepo;
    private final FinishedGoodsTransactionRepository txRepo;
    private final WarehouseRepository warehouseRepo;
    private final IngredientRepository ingredientRepo;
    private final IngredientStockRepository ingredientStockRepo;
    private final IngredientExpiryRepository ingredientExpiryRepo;
    private final IngredientWarehouseRepository ingredientWarehouseRepo;
    private final ProductionFactoryRepository productionFactoryRepo;
    private final UserRepository userRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    // Cầu nối FinishedGoodsStock.factoryProductId → FactoryProduct.ingredientId
    // (xem comment ở FactoryProduct.ingredientId) — dùng để tìm đúng Ingredient
    // đích khi chuyển kho, không còn match theo tên (chuỗi) như trước.
    private final FactoryProductRepository factoryProductRepo;

    // ─── Nhập kho khi hoàn thành mẻ (gọi từ ProductionModuleService.completeBatch) ──

    public FinishedGoodsStock receiveFromBatch(ProductionBatch batch, BigDecimal qty,
                                               Long manufactureDate, Long expiryDate) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) return null;

        Long factoryProductId = batch.getWorkOrder() != null && batch.getWorkOrder().getFactoryProduct() != null
                ? batch.getWorkOrder().getFactoryProduct().getId() : null;
        Long factoryId = batch.getWorkOrder() != null && batch.getWorkOrder().getProductionFactory() != null
                ? batch.getWorkOrder().getProductionFactory().getId() : null;
        String factoryName = batch.getWorkOrder() != null ? batch.getWorkOrder().getProductionFactoryName() : null;

        FinishedGoodsStock lot = FinishedGoodsStock.builder()
                .factoryProductId(factoryProductId)
                .productName(batch.getProductName())
                .unit(batch.getOutputUnit())
                .quantity(qty)
                .initialQuantity(qty)
                .manufactureDate(manufactureDate)
                .expiryDate(expiryDate)
                .batch(batch)
                .batchCodeSnapshot(batch.getBatchCode())
                .factoryId(factoryId)
                .factoryNameSnapshot(factoryName)
                .isActive(true)
                .build();
        return stockRepo.save(lot);
    }

    // ─── Danh sách tổng hợp theo Tên thành phẩm (UI chính) ─────────────────────

    @Transactional(readOnly = true)
    public List<FinishedGoodsSummaryDto> listSummary(String q, Long expiryBeforeMs, Long factoryId) {
        List<FinishedGoodsStock> lots = stockRepo.searchActiveLotsByFactory(
                (q == null || q.isBlank()) ? null : q.trim(), factoryId);

        Map<String, List<FinishedGoodsStock>> byProduct = lots.stream()
                .collect(Collectors.groupingBy(FinishedGoodsStock::getProductName, LinkedHashMap::new, Collectors.toList()));

        List<FinishedGoodsSummaryDto> result = new ArrayList<>();
        for (Map.Entry<String, List<FinishedGoodsStock>> e : byProduct.entrySet()) {
            List<FinishedGoodsStock> productLots = e.getValue();

            // Filter cận date nếu có yêu cầu (chỉ lọc nhóm thành phẩm có ít nhất 1 lô cận date)
            if (expiryBeforeMs != null) {
                boolean hasNearExpiry = productLots.stream()
                        .anyMatch(l -> l.getExpiryDate() != null && l.getExpiryDate() <= expiryBeforeMs);
                if (!hasNearExpiry) continue;
            }

            BigDecimal total = productLots.stream().map(FinishedGoodsStock::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Long nearest = productLots.stream()
                    .map(FinishedGoodsStock::getExpiryDate).filter(Objects::nonNull)
                    .min(Long::compareTo).orElse(null);
            Long farthest = productLots.stream()
                    .map(FinishedGoodsStock::getExpiryDate).filter(Objects::nonNull)
                    .max(Long::compareTo).orElse(null);

            result.add(FinishedGoodsSummaryDto.builder()
                    .productName(e.getKey())
                    .unit(productLots.get(0).getUnit())
                    .totalQuantity(total)
                    .nearestExpiryDate(nearest)
                    .farthestExpiryDate(farthest)
                    .lotCount(productLots.size())
                    .lots(productLots.stream().sorted(Comparator.comparing(
                                    FinishedGoodsStock::getExpiryDate, Comparator.nullsLast(Long::compareTo)))
                            .map(this::toLotDto).collect(Collectors.toList()))
                    .build());
        }
        return result;
    }

    private FinishedGoodsLotDto toLotDto(FinishedGoodsStock l) {
        return FinishedGoodsLotDto.builder()
                .id(l.getId())
                .productName(l.getProductName())
                .unit(l.getUnit())
                .quantity(l.getQuantity())
                .initialQuantity(l.getInitialQuantity())
                .manufactureDate(l.getManufactureDate())
                .expiryDate(l.getExpiryDate())
                .batchCode(l.getBatchCodeSnapshot())
                .factoryId(l.getFactoryId())
                .factoryName(l.getFactoryNameSnapshot())
                .totalCost(l.getTotalCost())
                .unitCost(l.getUnitCost())
                .unitCostPerKg(l.getUnitCostPerKg())
                .netWeightKg(l.getNetWeightKg())
                .createdAt(l.getCreatedAt())
                .build();
    }

    // ─── Xuất kho (cần lý do) ───────────────────────────────────────────────────

    public FinishedGoodsTransactionDto exportGoods(ExportFinishedGoodsRequest req, String username) {
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do xuất kho");
        }
        if (req.getQuantity() == null || req.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số lượng xuất kho phải lớn hơn 0");
        }
        if (req.getFactoryId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn kho xưởng");
        }
        User actor = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        String unit = deductFifo(req.getProductName(), req.getQuantity(), req.getFactoryId());

        FinishedGoodsTransaction tx = FinishedGoodsTransaction.builder()
                .type(FinishedGoodsTransaction.TransactionType.EXPORT)
                .productName(req.getProductName())
                .unit(unit)
                .quantity(req.getQuantity())
                .reason(req.getReason())
                .documentImages(writeImages(req.getDocumentImages()))
                .factoryId(req.getFactoryId())
                .factoryName(resolveFactoryName(req.getFactoryId()))
                .performedBy(actor)
                .performedByName(actor.getFullName())
                .build();
        return toTxDto(txRepo.save(tx));
    }

    // ─── Chuyển kho (cần kho đích — kho bán hàng) ───────────────────────────────

    public FinishedGoodsTransactionDto transferGoods(TransferFinishedGoodsRequest req, String username) {
        if (req.getTargetWarehouseId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn kho đích");
        }
        if (req.getQuantity() == null || req.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số lượng chuyển kho phải lớn hơn 0");
        }
        if (req.getFactoryId() == null) {
            throw new IllegalArgumentException("Vui lòng chọn kho xưởng nguồn");
        }
        User actor = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        Warehouse targetWarehouse = warehouseRepo.findById(req.getTargetWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy kho đích"));
        if (!targetWarehouse.isActive()) {
            throw new IllegalArgumentException("Kho đích đang ngừng hoạt động");
        }

        // 1) Trừ kho thành phẩm CỦA XƯỞNG NGUỒN theo FIFO (ưu tiên lô gần hết hạn nhất)
        List<FinishedGoodsStock> sourceLots = stockRepo
                .findAvailableLotsByProductAndFactoryOrderByExpiryAsc(req.getProductName(), req.getFactoryId());
        BigDecimal totalAvailable = sourceLots.stream()
                .map(FinishedGoodsStock::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAvailable.compareTo(req.getQuantity()) < 0) {
            throw new IllegalStateException(
                    String.format("Kho thành phẩm không đủ '%s': cần %s, tồn %s",
                            req.getProductName(),
                            req.getQuantity().stripTrailingZeros().toPlainString(),
                            totalAvailable.stripTrailingZeros().toPlainString()));
        }

        String unit = sourceLots.get(0).getUnit();

        // 2) Ingredient đích — BẮT BUỘC kho đích đã có nguyên liệu TRÙNG TÊN.
        //    (Trước đây: tự tạo Ingredient mới nếu chưa có → sinh dữ liệu rác. Theo yêu cầu
        //    mới, dropdown FE chỉ liệt kê thành phẩm mà kho đích đang có, nên ở đây chỉ cần
        //    chốt chặn phía server.)
        Ingredient ingredient = findIngredientInWarehouseByName(targetWarehouse.getId(), req.getProductName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Kho đích \"" + targetWarehouse.getName() + "\" chưa có nguyên liệu \""
                                + req.getProductName() + "\". Vui lòng thêm nguyên liệu vào kho đích trước."));

        BigDecimal remaining = req.getQuantity();
        BigDecimal transferredCostValue = BigDecimal.ZERO;   // tổng giá trị vốn chuyển đi
        long now = System.currentTimeMillis();
        for (FinishedGoodsStock lot : sourceLots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = remaining.min(lot.getQuantity());

            lot.setQuantity(lot.getQuantity().subtract(take));
            if (lot.getQuantity().compareTo(BigDecimal.ZERO) == 0) lot.setIsActive(false);
            stockRepo.save(lot);

            // Cộng vào kho đích — giữ nguyên ngày SX + HSD + GIÁ VỐN của lô gốc.
            BigDecimal lotCost = lot.getUnitCost() != null ? lot.getUnitCost() : BigDecimal.ZERO;
            addToSaleWarehouse(targetWarehouse, ingredient, take,
                    lot.getManufactureDate(), lot.getExpiryDate(), lotCost, now);
            transferredCostValue = transferredCostValue.add(lotCost.multiply(take));

            remaining = remaining.subtract(take);
        }

        // 3) Cộng tổng vào IngredientStock (tồn kho gộp) của kho đích — kèm giá trị vốn
        upsertIngredientStockTotal(targetWarehouse, ingredient, req.getQuantity(), transferredCostValue, now);

        FinishedGoodsTransaction tx = FinishedGoodsTransaction.builder()
                .type(FinishedGoodsTransaction.TransactionType.TRANSFER)
                .productName(req.getProductName())
                .unit(unit)
                .quantity(req.getQuantity())
                .factoryId(req.getFactoryId())
                .factoryName(resolveFactoryName(req.getFactoryId()))
                .targetWarehouseId(targetWarehouse.getId())
                .targetWarehouseName(targetWarehouse.getName())
                .targetIngredientId(ingredient.getId())
                .performedBy(actor)
                .performedByName(actor.getFullName())
                .build();
        return toTxDto(txRepo.save(tx));
    }

    // ─── Kho đích + thành phẩm chuyển được (giao theo TÊN) ──────────────────────

    /** Danh sách kho đích: TẤT CẢ kho bán + trung chuyển đang hoạt động. */
    @Transactional(readOnly = true)
    public List<TransferTargetDto> listTransferTargets() {
        return warehouseRepo.findAll().stream()
                .filter(Warehouse::isActive)
                .sorted(Comparator.comparing(Warehouse::getName, String.CASE_INSENSITIVE_ORDER))
                .map(w -> TransferTargetDto.builder()
                        .id(w.getId())
                        .name(w.getName())
                        .type(w.getType().name())
                        .typeLabel(w.getType() == Warehouse.WarehouseType.SALE ? "Kho bán" : "Trung chuyển")
                        .address(w.getAddress())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Thành phẩm có thể chuyển từ kho thành phẩm của {@code factoryId} sang kho đích
     * {@code targetWarehouseId}: GIAO của 2 tập theo TÊN (không phân biệt hoa/thường).
     * Kho đích không có nguyên liệu trùng tên → không xuất hiện trong dropdown.
     */
    @Transactional(readOnly = true)
    public List<TransferableProductDto> listTransferableProducts(Long factoryId, Long targetWarehouseId) {
        if (factoryId == null || targetWarehouseId == null) return List.of();

        // Nguyên liệu kho đích đang có (đã đăng ký vào kho) → map theo tên viết thường
        Map<String, Ingredient> targetByName = new LinkedHashMap<>();
        for (Long ingId : ingredientWarehouseRepo.findIngredientIdsByWarehouseId(targetWarehouseId)) {
            ingredientRepo.findById(ingId)
                    .filter(i -> Boolean.TRUE.equals(i.getIsActive()) && i.getName() != null)
                    .ifPresent(i -> targetByName.putIfAbsent(i.getName().trim().toLowerCase(), i));
        }
        if (targetByName.isEmpty()) return List.of();

        // Tồn kho thành phẩm của xưởng → gộp theo tên
        List<FinishedGoodsStock> lots = stockRepo.searchActiveLotsByFactory(null, factoryId);
        Map<String, List<FinishedGoodsStock>> byProduct = lots.stream()
                .collect(Collectors.groupingBy(FinishedGoodsStock::getProductName, LinkedHashMap::new, Collectors.toList()));

        List<TransferableProductDto> result = new ArrayList<>();
        for (Map.Entry<String, List<FinishedGoodsStock>> e : byProduct.entrySet()) {
            Ingredient match = targetByName.get(e.getKey().trim().toLowerCase());
            if (match == null) continue;   // kho đích không có → bỏ qua
            BigDecimal total = e.getValue().stream()
                    .map(FinishedGoodsStock::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(BigDecimal.ZERO) <= 0) continue;
            result.add(TransferableProductDto.builder()
                    .productName(e.getKey())
                    .unit(e.getValue().get(0).getUnit())
                    .availableQuantity(total)
                    .targetIngredientId(match.getId())
                    .build());
        }
        result.sort(Comparator.comparing(TransferableProductDto::getProductName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Tìm Ingredient ĐÃ ĐĂNG KÝ ở kho đích có tên trùng (không phân biệt hoa/thường). */
    private Optional<Ingredient> findIngredientInWarehouseByName(Long warehouseId, String name) {
        if (name == null) return Optional.empty();
        String key = name.trim().toLowerCase();
        return ingredientWarehouseRepo.findIngredientIdsByWarehouseId(warehouseId).stream()
                .map(ingredientRepo::findById)
                .filter(Optional::isPresent).map(Optional::get)
                .filter(i -> Boolean.TRUE.equals(i.getIsActive()))
                .filter(i -> i.getName() != null && i.getName().trim().toLowerCase().equals(key))
                .findFirst();
    }

    private String resolveFactoryName(Long factoryId) {
        if (factoryId == null) return null;
        return productionFactoryRepo.findById(factoryId).map(ProductionFactory::getName).orElse(null);
    }

    private String writeImages(List<String> images) {
        if (images == null || images.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(images);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private List<String> readImages(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /** Trừ kho thành phẩm theo FIFO (ưu tiên lô gần hết hạn nhất) — dùng chung cho export */
    private String deductFifo(String productName, BigDecimal qty, Long factoryId) {
        List<FinishedGoodsStock> lots = stockRepo
                .findAvailableLotsByProductAndFactoryOrderByExpiryAsc(productName, factoryId);
        BigDecimal totalAvailable = lots.stream()
                .map(FinishedGoodsStock::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAvailable.compareTo(qty) < 0) {
            throw new IllegalStateException(
                    String.format("Kho thành phẩm không đủ '%s': cần %s, tồn %s",
                            productName, qty.stripTrailingZeros().toPlainString(),
                            totalAvailable.stripTrailingZeros().toPlainString()));
        }
        String unit = lots.isEmpty() ? "" : lots.get(0).getUnit();
        BigDecimal remaining = qty;
        for (FinishedGoodsStock lot : lots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = remaining.min(lot.getQuantity());
            lot.setQuantity(lot.getQuantity().subtract(take));
            if (lot.getQuantity().compareTo(BigDecimal.ZERO) == 0) lot.setIsActive(false);
            stockRepo.save(lot);
            remaining = remaining.subtract(take);
        }
        return unit;
    }

    private Ingredient createIngredientForFinishedGoods(String name, String unit) {
        long now = System.currentTimeMillis();
        Ingredient ing = Ingredient.builder()
                .name(name).unit(unit != null && !unit.isBlank() ? unit : "kg")
                .isActive(true).createdAt(now).updatedAt(now)
                .build();
        return ingredientRepo.save(ing);
    }

    /**
     * Cộng vào lô IngredientExpiry ở kho bán hàng — mang theo ngày SX + HSD + GIÁ VỐN
     * của lô thành phẩm gốc.
     *
     * <p>MỖI LẦN CHUYỂN TẠO MỘT LÔ MỚI, không gộp vào lô sẵn có — giữ nguyên ngày
     * SX và giá vốn của từng mẻ để truy xuất nguồn gốc.
     */
    private void addToSaleWarehouse(Warehouse warehouse, Ingredient ingredient, BigDecimal qty,
                                    Long manufactureDateMs, Long expiryDateMs,
                                    BigDecimal costPrice, long now) {
        LocalDate expiryLocalDate = expiryDateMs != null
                ? java.time.Instant.ofEpochMilli(expiryDateMs).atZone(VN).toLocalDate() : null;
        LocalDate manufactureLocalDate = manufactureDateMs != null
                ? java.time.Instant.ofEpochMilli(manufactureDateMs).atZone(VN).toLocalDate() : null;
        BigDecimal cost = costPrice != null ? costPrice : BigDecimal.ZERO;

        // MỖI LẦN CHUYỂN = MỘT LÔ MỚI ở kho bán hàng.
        //
        //   Bản cũ gộp vào lô cùng HSD + giá vốn. Ở đây tác hại lớn hơn hai chỗ
        //   chuyển kho kia: lô thành phẩm còn mang NGÀY SẢN XUẤT, mà gộp thì ngày
        //   SX của lô mới bị bỏ đi (chỉ điền khi lô cũ đang trống). Hai mẻ sản
        //   xuất khác ngày, cùng HSD và cùng giá vốn, sẽ nằm chung một dòng và
        //   mất luôn ngày SX của mẻ sau — không truy xuất nguồn gốc được nữa.
        //
        //   Ngoài ra tra cứu lô cũ còn ném "query did not return a unique result"
        //   khi kho bán đã có 2 lô trùng; bảng không có ràng buộc duy nhất.
        ingredientExpiryRepo.save(IngredientExpiry.builder()
                .warehouse(warehouse)
                .ingredientId(ingredient.getId())
                .expiryDate(expiryLocalDate)
                .manufactureDate(manufactureLocalDate)
                .costPrice(cost)
                .quantity(qty)
                .createdAt(now).updatedAt(now)
                .build());
    }

    /** Cộng vào IngredientStock (tồn kho tổng) của kho đích — tạo mới nếu chưa có */
    private void upsertIngredientStockTotal(Warehouse warehouse, Ingredient ingredient,
                                            BigDecimal qty, BigDecimal costValue, long now) {
        IngredientStock stock = ingredientStockRepo
                .findByIngredientIdAndWarehouseId(ingredient.getId(), warehouse.getId())
                .orElseGet(() -> IngredientStock.builder()
                        .ingredientId(ingredient.getId())
                        .ingredientNameSnapshot(ingredient.getName())
                        .ingredientUnitSnapshot(ingredient.getUnit())
                        .warehouse(warehouse)
                        .stockQuantity(BigDecimal.ZERO)
                        .build());
        stock.setStockQuantity(stock.getStockQuantity().add(qty));
        BigDecimal currentValue = stock.getTotalCostValue() != null
                ? stock.getTotalCostValue() : BigDecimal.ZERO;
        stock.setTotalCostValue(currentValue.add(costValue != null ? costValue : BigDecimal.ZERO));
        stock.setUpdatedAt(now);
        ingredientStockRepo.save(stock);
    }

    private FinishedGoodsTransactionDto toTxDto(FinishedGoodsTransaction tx) {
        return FinishedGoodsTransactionDto.builder()
                .id(tx.getId())
                .type(tx.getType().name())
                .productName(tx.getProductName())
                .unit(tx.getUnit())
                .quantity(tx.getQuantity())
                .reason(tx.getReason())
                .documentImages(readImages(tx.getDocumentImages()))
                .factoryId(tx.getFactoryId())
                .factoryName(tx.getFactoryName())
                .targetWarehouseId(tx.getTargetWarehouseId())
                .targetWarehouseName(tx.getTargetWarehouseName())
                .performedByName(tx.getPerformedByName())
                .createdAt(tx.getCreatedAt())
                .build();
    }

    // ─── Lịch sử giao dịch ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FinishedGoodsTransactionDto> listTransactions(String productName, int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        var result = (productName != null && !productName.isBlank())
                ? txRepo.findByProductNameOrderByCreatedAtDesc(productName, pageable)
                : txRepo.findAllByOrderByCreatedAtDesc(pageable);
        return result.getContent().stream().map(this::toTxDto).collect(Collectors.toList());
    }

    /** Danh sách kho bán hàng (type=SALE) — cho dropdown chọn kho đích khi chuyển kho */
    @Transactional(readOnly = true)
    public List<Warehouse> listSaleWarehouses() {
        return warehouseRepo.findByTypeAndActiveTrue(Warehouse.WarehouseType.SALE);
    }
}