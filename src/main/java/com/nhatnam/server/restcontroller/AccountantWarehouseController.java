package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.entity.WarehouseReceipt.CostStatus;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.serviceimpl.WarehouseService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Endpoints cho SUPER_ACCOUNTANT (và ADMIN/OWNER):
 * - Xem danh sách phiếu nhập kho chờ nhập giá vốn
 * - Nhập giá vốn và xác nhận → cộng tồn kho
 *
 * ACCOUNTANT thường (không phải SUPER) KHÔNG có quyền truy cập.
 */
@RestController
@RequestMapping("/api/accountant/warehouse-receipts")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','ADMIN','OWNER')")  // ← bỏ ACCOUNTANT
public class AccountantWarehouseController {

    private final WarehouseReceiptRepository     receiptRepository;
    private final WarehouseReceiptItemRepository receiptItemRepository;
    private final IngredientStockRepository      stockRepository;
    private final IngredientExpiryRepository     expiryRepository;
    private final UserRepository                 userRepository;

    /** Danh sách phiếu nhập chờ nhập giá vốn */
    @GetMapping("/pending-cost")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPendingCost() {
        try {
            List<WarehouseReceipt> list = receiptRepository
                    .findByReceiptTypeAndCostStatusOrderByCreatedAtDesc(
                            ReceiptType.IMPORT, CostStatus.PENDING_COST);
            List<Map<String, Object>> result = list.stream()
                    .map(this::mapReceiptBasic)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("listPendingCost error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Chi tiết phiếu nhập (kèm items để nhập giá vốn) */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDetail(@PathVariable Long id) {
        return receiptRepository.findById(id)
                .map(r -> ResponseEntity.ok(ApiResponse.success(mapReceiptDetail(r), "OK")))
                .orElse(ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, "Không tìm thấy")));
    }

    /** Xác nhận giá vốn và cộng tồn kho */
    @PostMapping("/{id}/confirm-cost")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmCost(
            @PathVariable Long id,
            @RequestBody ConfirmCostRequest req,
            Authentication auth) {
        try {
            WarehouseReceipt receipt = receiptRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy phiếu #" + id));

            if (receipt.getReceiptType() != ReceiptType.IMPORT) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Chỉ áp dụng cho phiếu nhập kho"));
            }
            if (receipt.getCostStatus() != CostStatus.PENDING_COST) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Phiếu đã được xác nhận trước đó"));
            }

            // Build map: receiptItemId → costPrice
            Map<Long, BigDecimal> costMap = new HashMap<>();
            if (req.getItems() != null) {
                for (ConfirmCostRequest.ItemCost ic : req.getItems()) {
                    if (ic.getCostPrice() == null || ic.getCostPrice().compareTo(BigDecimal.ZERO) <= 0) {
                        return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                                "Tất cả các mặt hàng phải có giá vốn > 0"));
                    }
                    costMap.put(ic.getReceiptItemId(), ic.getCostPrice());
                }
            }

            long now = System.currentTimeMillis();
            Warehouse warehouse = receipt.getWarehouse();

            // Cập nhật giá vốn từng item + cộng tồn kho
            for (WarehouseReceiptItem item : receipt.getItems()) {
                BigDecimal costPrice = costMap.get(item.getId());
                if (costPrice == null) {
                    return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                            "Thiếu giá vốn cho: " + item.resolvedIngredientName()));
                }

                item.setCostPrice(costPrice);
                receiptItemRepository.save(item);

                // Dùng ingredientId plain + snapshot từ WarehouseReceiptItem
                Long ingId = item.getIngredientId();
                BigDecimal qty = item.getQuantity();

                // Cộng tồn kho
                IngredientStock stock = stockRepository
                        .findByIngredientIdAndWarehouseId(ingId, warehouse.getId())
                        .orElseGet(() -> {
                            IngredientStock s = IngredientStock.builder()
                                    .ingredientId(ingId)
                                    .ingredientNameSnapshot(item.resolvedIngredientName())
                                    .ingredientUnitSnapshot(item.resolvedIngredientUnit())
                                    .warehouse(warehouse)
                                    .stockQuantity(BigDecimal.ZERO).updatedAt(now).build();
                            return stockRepository.save(s);
                        });

                BigDecimal before = stock.getStockQuantity();
                BigDecimal after  = before.add(qty);
                stock.setStockQuantity(after);
                stock.setUpdatedAt(now);

                BigDecimal addedCost = costPrice.multiply(qty);
                BigDecimal curCost   = stock.getTotalCostValue() != null ? stock.getTotalCostValue() : BigDecimal.ZERO;
                stock.setTotalCostValue(curCost.add(addedCost));
                stockRepository.save(stock);

                // Cập nhật snapshot quantityBefore/After
                item.setQuantityBefore(before);
                item.setQuantityAfter(after);
                item.setDifference(qty);
                receiptItemRepository.save(item);

                addOrUpdateExpiry(warehouse, ingId, qty, item.getExpiryDate(), costPrice, now);
            }

            // Xác nhận phiếu
            User actor = null;
            if (auth != null && auth.getPrincipal() instanceof User u) {
                actor = userRepository.findById(u.getId()).orElse(null);
            }
            receipt.setCostStatus(CostStatus.CONFIRMED);
            receipt.setCostConfirmedAt(now);
            if (actor != null) {
                receipt.setCostConfirmedBy(actor);
                receipt.setCostConfirmedByName(
                        actor.getFullName() != null && !actor.getFullName().isBlank()
                                ? actor.getFullName() : actor.getUsername());
            }
            receipt.setUpdatedAt(now);
            receiptRepository.save(receipt);

            return ResponseEntity.ok(ApiResponse.success(mapReceiptBasic(receipt),
                    "Đã xác nhận giá vốn và cộng tồn kho"));

        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("confirmCost error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addOrUpdateExpiry(Warehouse warehouse, Long ingredientId,
                                   BigDecimal quantity, java.time.LocalDate expiryDate,
                                   BigDecimal costPrice, long now) {
        expiryRepository
                .findByWarehouseIdAndIngredientId(warehouse.getId(), ingredientId)
                .stream()
                .filter(e -> java.util.Objects.equals(e.getExpiryDate(), expiryDate)
                        && java.util.Objects.equals(e.getCostPrice(), costPrice))
                .findFirst()
                .ifPresentOrElse(e -> {
                    e.setQuantity(e.getQuantity().add(quantity));
                    e.setUpdatedAt(now);
                    expiryRepository.save(e);
                }, () -> expiryRepository.save(
                        IngredientExpiry.builder()
                                .warehouse(warehouse).ingredientId(ingredientId)
                                .expiryDate(expiryDate).quantity(quantity)
                                .costPrice(costPrice).createdAt(now).updatedAt(now)
                                .build()));
    }

    private Map<String, Object> mapReceiptBasic(WarehouseReceipt r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           r.getId());
        m.put("receiptCode",  r.getReceiptCode());
        m.put("warehouseId",  r.getWarehouse().getId());
        m.put("warehouseName",r.getWarehouse().getName());
        m.put("referenceCode",r.getReferenceCode());
        m.put("note",         r.getNote());
        m.put("costStatus",   r.getCostStatus() != null ? r.getCostStatus().name() : null);
        m.put("createdByName",r.getCreatedByName());
        m.put("createdAt",    r.getCreatedAt());
        m.put("itemCount",    r.getItems().size());
        return m;
    }

    private Map<String, Object> mapReceiptDetail(WarehouseReceipt r) {
        Map<String, Object> m = mapReceiptBasic(r);
        List<Map<String, Object>> items = r.getItems().stream().map(item -> {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("id",             item.getId());
            i.put("ingredientId",   item.getIngredientId());
            i.put("ingredientName", item.resolvedIngredientName());
            i.put("unit",           item.resolvedIngredientUnit());
            i.put("imageUrl",       item.resolvedIngredientImageUrl());
            i.put("quantity",       item.getQuantity());
            i.put("expiryDate",     item.getExpiryDate());
            i.put("costPrice",      item.getCostPrice()); // null khi chưa nhập
            return i;
        }).collect(Collectors.toList());
        m.put("items", items);
        return m;
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    @Data
    public static class ConfirmCostRequest {
        private List<ItemCost> items;

        @Data
        public static class ItemCost {
            private Long       receiptItemId;
            private BigDecimal costPrice;
        }
    }
}