package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.entity.WarehouseReceipt.CostStatus;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.serviceimpl.WarehouseService;
import com.nhatnam.server.utils.CostAllocation;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * KẾ TOÁN TRƯỞNG NHẬP GIÁ VỐN cho phiếu NHẬP KHO.
 *
 * <p>Ở flow mới, tồn kho ĐÃ được cộng ngay lúc nhân viên kho tạo phiếu
 * (xem {@link WarehouseService#importStock}), mỗi dòng đã có sẵn 1 lô
 * {@link IngredientExpiry} với {@code costPrice = 0}. Bước này KHÔNG cộng tồn nữa —
 * chỉ tính giá vốn thật rồi GHI ĐÈ {@code costPrice} lên đúng lô đó.
 *
 * <p>Toàn bộ logic đặt trong service (không nằm trong controller) để mọi
 * {@link BusinessException} đều làm ROLLBACK transaction — tránh tình trạng
 * cập nhật dở dang một nửa số dòng rồi trả lỗi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseReceiptCostService {

    private final WarehouseReceiptRepository receiptRepository;
    private final IngredientStockRepository stockRepository;
    private final IngredientExpiryRepository expiryRepository;
    private final UserRepository userRepository;
    private final WarehouseService warehouseService;
    private final ObjectMapper objectMapper;
    private final StockMutationService stockMutationService;   // BUG FIX 2.1

    // ════════════════════════════════════════════════════════════════════════
    // REQUEST DTO
    // ════════════════════════════════════════════════════════════════════════

    @Data
    public static class ConfirmCostRequest {
        /** Đơn giá nhập của từng dòng (CHƯA gồm thuế/phí) */
        private List<ItemCost> items;
        /** Các khoản thuế/phí — mỗi dòng 1 loại, label tự nhập. Có thể rỗng. */
        private List<CostEntry> costEntries;

        @Data
        public static class ItemCost {
            private Long receiptItemId;
            private BigDecimal unitPrice;
            private BigDecimal costPrice;

            /**
             * BUG FIX KB6: snapshot số lượng phiếu tại thời điểm FE đọc.
             * Nếu khác giá trị hiện tại → ai đó đã sửa phiếu → throw để user reload.
             * Optional: bỏ qua check nếu null (tương thích ngược).
             */
            private BigDecimal expectedQuantity;

            /**
             * BUG FIX KB6: snapshot giá vốn hiện tại tại thời điểm FE đọc.
             * Với phiếu chưa confirm thì thường = 0 hoặc null.
             * Nếu khác giá trị hiện tại → ai đó đã confirm rồi → throw để user reload.
             */
            private BigDecimal expectedUnitCost;

            public BigDecimal resolvedUnitPrice() {
                return unitPrice != null ? unitPrice : costPrice;
            }
        }

        @Data
        public static class CostEntry {
            private String label;
            private BigDecimal amount;
            /** Rỗng/null = áp dụng cho tất cả các dòng của phiếu */
            private List<Long> itemIds;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PREVIEW — tính thử giá vốn, KHÔNG ghi DB
    // ════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> previewCost(Long receiptId, ConfirmCostRequest req) {
        WarehouseReceipt receipt = loadImportReceipt(receiptId);
        Map<Long, CostAllocation.Result> results = compute(receipt, req);
        return buildPreviewPayload(receipt, results);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CONFIRM — ghi giá vốn xuống lô + chốt phiếu
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> confirmCost(Long receiptId, ConfirmCostRequest req, Long actorUserId) {
        WarehouseReceipt receipt = loadImportReceipt(receiptId);

        // BUG FIX KB6 (defense 1): status check — chặn confirm 2 lần khi tx đã commit
        if (receipt.getCostStatus() != CostStatus.PENDING_COST)
            throw new BusinessException("Phiếu đã được xác nhận giá vốn trước đó. Vui lòng tải lại trang.");

        // BUG FIX KB6 (defense 2): compare-and-set trên từng receipt item
        // Nếu FE gửi expectedQuantity/expectedUnitCost → verify khớp với DB
        // hiện tại. Không khớp = ai đó đã sửa phiếu hoặc confirm trước → user
        // đang thao tác trên dữ liệu cũ, phải reload.
        Map<Long, WarehouseReceiptItem> itemById = receipt.getItems().stream()
                .collect(Collectors.toMap(WarehouseReceiptItem::getId, i -> i));

        for (ConfirmCostRequest.ItemCost ic : req.getItems()) {
            WarehouseReceiptItem dbItem = itemById.get(ic.getReceiptItemId());
            if (dbItem == null) continue;

            // TH1: số lượng phiếu đã thay đổi
            if (ic.getExpectedQuantity() != null) {
                BigDecimal dbQty = dbItem.getQuantity() != null ? dbItem.getQuantity() : BigDecimal.ZERO;
                if (dbQty.compareTo(ic.getExpectedQuantity()) != 0) {
                    throw new BusinessException(String.format(
                            "Số lượng của '%s' đã thay đổi từ %s → %s. "
                                    + "Vui lòng tải lại phiếu và nhập giá vốn mới.",
                            dbItem.resolvedIngredientName(),
                            ic.getExpectedQuantity().toPlainString(),
                            dbQty.toPlainString()));
                }
            }

            // TH2: giá vốn đã được cập nhật (kế toán khác confirm giữa chừng)
            if (ic.getExpectedUnitCost() != null) {
                BigDecimal dbCost = dbItem.getCostPrice() != null ? dbItem.getCostPrice() : BigDecimal.ZERO;
                if (dbCost.compareTo(ic.getExpectedUnitCost()) != 0) {
                    throw new BusinessException(String.format(
                            "Giá vốn của '%s' đã được cập nhật (%sđ → %sđ) bởi người khác. "
                                    + "Vui lòng tải lại và kiểm tra.",
                            dbItem.resolvedIngredientName(),
                            ic.getExpectedUnitCost().toPlainString(),
                            dbCost.toPlainString()));
                }
            }
        }

        Map<Long, CostAllocation.Result> results = compute(receipt, req);
        long now = System.currentTimeMillis();
        Warehouse warehouse = receipt.getWarehouse();

        for (WarehouseReceiptItem item : receipt.getItems()) {
            CostAllocation.Result r = results.get(item.getId());
            BigDecimal qty = item.getQuantity();
            BigDecimal unitCost = r.unitCost();

            item.setUnitPrice(unitPriceOf(req, item.getId()));
            item.setAllocatedFee(r.feeShare().setScale(CostAllocation.MONEY_SCALE,
                    java.math.RoundingMode.HALF_UP));
            item.setCostPrice(unitCost);

            if (item.getIngredientExpiryId() != null) {
                IngredientExpiry lot = expiryRepository.findById(item.getIngredientExpiryId())
                        .orElseThrow(() -> new BusinessException(
                                "Không tìm thấy lô kho của nguyên liệu: " + item.resolvedIngredientName()));
                lot.setCostPrice(unitCost);
                lot.setUpdatedAt(now);
                expiryRepository.save(lot);

                stockMutationService.addCostValue(item.getIngredientId(), warehouse.getId(),
                        unitCost.multiply(qty), now);
            } else {
                // ... giữ nguyên nhánh tương thích ngược cũ ...
                if (!stockRepository.existsByIngredientIdAndWarehouseId(
                        item.getIngredientId(), warehouse.getId())) {
                    stockRepository.save(IngredientStock.builder()
                            .ingredientId(item.getIngredientId())
                            .ingredientNameSnapshot(item.resolvedIngredientName())
                            .ingredientUnitSnapshot(item.resolvedIngredientUnit())
                            .warehouse(warehouse)
                            .stockQuantity(BigDecimal.ZERO)
                            .totalCostValue(BigDecimal.ZERO)
                            .updatedAt(now)
                            .build());
                }
                StockMutationResult inc = stockMutationService.increase(
                        item.getIngredientId(), warehouse.getId(), qty, now);
                stockMutationService.addCostValue(
                        item.getIngredientId(), warehouse.getId(), unitCost.multiply(qty), now);
                item.setQuantityBefore(inc.getBefore());
                item.setQuantityAfter(inc.getAfter());
                item.setDifference(qty);
                IngredientExpiry lot = expiryRepository.save(IngredientExpiry.builder()
                        .warehouse(warehouse)
                        .ingredientId(item.getIngredientId())
                        .expiryDate(item.getExpiryDate())
                        .quantity(qty)
                        .costPrice(unitCost)
                        .createdAt(now).updatedAt(now)
                        .build());
                item.setIngredientExpiryId(lot.getId());
            }
        }

        // Lưu lại các dòng thuế/phí đã nhập (để xem lại/đối chiếu sau này)
        receipt.getCostEntries().clear();
        List<ConfirmCostRequest.CostEntry> fees = normalizedFees(req);
        int order = 0;
        for (ConfirmCostRequest.CostEntry ce : fees) {
            receipt.getCostEntries().add(WarehouseReceiptCostEntry.builder()
                    .receipt(receipt)
                    .label(ce.getLabel().trim())
                    .amount(CostAllocation.normalizeMoney(ce.getAmount()))
                    .appliesToItemIds(serializeIds(ce.getItemIds()))
                    .sortOrder(order++)
                    .build());
        }

        User actor = actorUserId != null ? userRepository.findById(actorUserId).orElse(null) : null;
        receipt.setCostStatus(CostStatus.CONFIRMED);
        receipt.setCostConfirmedAt(now);
        if (actor != null) {
            receipt.setCostConfirmedBy(actor);
            receipt.setCostConfirmedByName(
                    actor.getFullName() != null && !actor.getFullName().isBlank()
                            ? actor.getFullName() : actor.getUsername());
        }
        receipt.setUpdatedAt(now);
        WarehouseReceipt saved = receiptRepository.save(receipt);

        warehouseService.notifyCostConfirmed(saved,
                saved.getCostConfirmedByName() != null ? saved.getCostConfirmedByName() : "Kế toán");

        return buildPreviewPayload(saved, results);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CORE
    // ════════════════════════════════════════════════════════════════════════

    private Map<Long, CostAllocation.Result> compute(WarehouseReceipt receipt, ConfirmCostRequest req) {
        if (receipt.getItems().isEmpty())
            throw new BusinessException("Phiếu không có nguyên liệu nào");

        Map<Long, BigDecimal> unitPriceById = new HashMap<>();
        if (req != null && req.getItems() != null) {
            for (ConfirmCostRequest.ItemCost ic : req.getItems()) {
                if (ic.getReceiptItemId() == null) continue;
                unitPriceById.put(ic.getReceiptItemId(),
                        CostAllocation.normalizeMoney(ic.resolvedUnitPrice()));
            }
        }

        List<CostAllocation.Line> lines = new ArrayList<>();
        for (WarehouseReceiptItem item : receipt.getItems()) {
            BigDecimal up = unitPriceById.get(item.getId());
            if (up == null || up.compareTo(BigDecimal.ZERO) <= 0)
                throw new BusinessException("Thiếu đơn giá (phải > 0) cho: " + item.resolvedIngredientName());
            lines.add(new CostAllocation.Line(item.getId(), item.getQuantity(), up));
        }

        List<CostAllocation.Fee> fees = normalizedFees(req).stream()
                .map(ce -> new CostAllocation.Fee(
                        ce.getLabel().trim(),
                        CostAllocation.normalizeMoney(ce.getAmount()),
                        ce.getItemIds()))
                .collect(Collectors.toList());

        try {
            return CostAllocation.allocate(lines, fees);
        } catch (IllegalStateException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    /** Lọc + validate các dòng thuế/phí. Bỏ qua dòng rỗng hoàn toàn. */
    private List<ConfirmCostRequest.CostEntry> normalizedFees(ConfirmCostRequest req) {
        if (req == null || req.getCostEntries() == null) return List.of();
        List<ConfirmCostRequest.CostEntry> out = new ArrayList<>();
        for (ConfirmCostRequest.CostEntry ce : req.getCostEntries()) {
            boolean emptyLabel = ce.getLabel() == null || ce.getLabel().isBlank();
            boolean emptyAmount = ce.getAmount() == null || ce.getAmount().compareTo(BigDecimal.ZERO) == 0;
            if (emptyLabel && emptyAmount) continue;               // dòng bỏ trống → bỏ qua
            if (emptyLabel)
                throw new BusinessException("Vui lòng đặt tên cho loại thuế/phí");
            if (ce.getAmount() == null || ce.getAmount().compareTo(BigDecimal.ZERO) < 0)
                throw new BusinessException("Số tiền không hợp lệ cho: " + ce.getLabel());
            out.add(ce);
        }
        return out;
    }

    private BigDecimal unitPriceOf(ConfirmCostRequest req, Long itemId) {
        if (req == null || req.getItems() == null) return null;
        return req.getItems().stream()
                .filter(i -> Objects.equals(i.getReceiptItemId(), itemId))
                .findFirst()
                .map(i -> CostAllocation.normalizeMoney(i.resolvedUnitPrice()))
                .orElse(null);
    }

    private WarehouseReceipt loadImportReceipt(Long id) {
        WarehouseReceipt receipt = receiptRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Không tìm thấy phiếu #" + id));
        if (receipt.getReceiptType() != ReceiptType.IMPORT)
            throw new BusinessException("Chỉ áp dụng cho phiếu nhập kho");
        return receipt;
    }

    // ════════════════════════════════════════════════════════════════════════
    // MAPPERS
    // ════════════════════════════════════════════════════════════════════════

    private Map<String, Object> buildPreviewPayload(WarehouseReceipt r,
                                                    Map<Long, CostAllocation.Result> results) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("receiptCode", r.getReceiptCode());
        m.put("warehouseId", r.getWarehouse().getId());
        m.put("warehouseName", r.getWarehouse().getName());
        m.put("costStatus", r.getCostStatus() != null ? r.getCostStatus().name() : null);

        BigDecimal grandValue = BigDecimal.ZERO, grandFee = BigDecimal.ZERO, grandCost = BigDecimal.ZERO;
        List<Map<String, Object>> items = new ArrayList<>();
        for (WarehouseReceiptItem item : r.getItems()) {
            CostAllocation.Result res = results.get(item.getId());
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("id", item.getId());
            i.put("ingredientId", item.getIngredientId());
            i.put("ingredientName", item.resolvedIngredientName());
            i.put("unit", item.resolvedIngredientUnit());
            i.put("quantity", item.getQuantity());
            i.put("expiryDate", item.getExpiryDate());
            if (res != null) {
                i.put("unitPrice", res.unitPrice());
                i.put("lineValue", res.lineValue());
                i.put("allocatedFee", res.feeShare());
                i.put("unitCost", res.unitCost());       // ← GIÁ VỐN cuối cùng (đã tròn đồng)
                i.put("lineCost", res.lineCost());
                i.put("feeBreakdown", res.feeBreakdown());
                grandValue = grandValue.add(res.lineValue());
                grandFee = grandFee.add(res.feeShare());
                grandCost = grandCost.add(res.lineCost());
            }
            items.add(i);
        }
        m.put("items", items);
        m.put("totalLineValue", grandValue);
        m.put("totalFee", grandFee);
        m.put("totalCost", grandCost);
        return m;
    }

    private String serializeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    public List<Long> deserializeIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}