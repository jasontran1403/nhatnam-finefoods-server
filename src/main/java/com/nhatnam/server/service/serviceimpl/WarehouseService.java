package com.nhatnam.server.service.serviceimpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.WarehouseDTO.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.entity.Warehouse.WarehouseType;
import com.nhatnam.server.entity.WarehouseReceipt.CostStatus;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import com.nhatnam.server.entity.WarehouseReceiptItem.AdjustResult;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.FifoDeductService;
import com.nhatnam.server.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseService {

    private final WarehouseRepository        warehouseRepository;
    private final WarehouseReceiptRepository warehouseReceiptRepository;
    private final IngredientExpiryRepository ingredientExpiryRepository;
    private final FifoDeductService          fifoDeductService;
    private final IngredientRepository       ingredientRepository;
    private final UserRepository             userRepository;
    private final ObjectMapper               objectMapper;
    private final IngredientStockRepository  ingredientStockRepository;
    private final NotificationService        notificationService;

    // ════════════════════════════════════════════════════════════════
    // EXPIRY LOT HELPERS
    // Thay đổi: dùng warehouseId + ingredientId thay vì Warehouse + Ingredient object
    // ════════════════════════════════════════════════════════════════

    private void adjustExpiryLot(Warehouse warehouse, Ingredient ingredient,
                                 BigDecimal delta, long now) {
        if (delta.compareTo(BigDecimal.ZERO) == 0) return;
        Long warehouseId = warehouse.getId();
        Long ingredientId = ingredient.getId();

        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            List<IngredientExpiry> allLots = ingredientExpiryRepository
                    .findByWarehouseIdAndIngredientIdOrderByExpiryDateAsc(warehouseId, ingredientId);

            BigDecimal costPrice = ingredientExpiryRepository
                    .findByWarehouseIdAndIngredientIdOrderByCreatedAtAsc(warehouseId, ingredientId)
                    .stream().findFirst().map(IngredientExpiry::getCostPrice)
                    .orElse(BigDecimal.ZERO);

            if (!allLots.isEmpty()) {
                IngredientExpiry soonest = allLots.get(0);
                soonest.setQuantity(soonest.getQuantity().add(delta));
                soonest.setUpdatedAt(now);
                ingredientExpiryRepository.save(soonest);
            } else {
                ingredientExpiryRepository.save(IngredientExpiry.builder()
                        .warehouse(warehouse)
                        .ingredientId(ingredientId)       // ← plain id
                        .expiryDate(null)
                        .costPrice(costPrice)
                        .quantity(delta)
                        .createdAt(now).updatedAt(now)
                        .build());
            }
        } else {
            BigDecimal toDeduct = delta.abs();
            List<IngredientExpiry> lots = ingredientExpiryRepository
                    .findByWarehouseIdAndIngredientIdOrderByExpiryDateAsc(warehouseId, ingredientId);

            for (IngredientExpiry lot : lots) {
                if (toDeduct.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal take = lot.getQuantity().min(toDeduct);
                lot.setQuantity(lot.getQuantity().subtract(take));
                lot.setUpdatedAt(now);
                if (lot.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
                    ingredientExpiryRepository.delete(lot);
                } else {
                    ingredientExpiryRepository.save(lot);
                }
                toDeduct = toDeduct.subtract(take);
            }
        }
    }

    private void transferExpiryLots(Warehouse source, Warehouse dest,
                                    Ingredient ingredient, BigDecimal qty, long now) {
        Long srcId = source.getId();
        Long dstId = dest.getId();
        Long ingId = ingredient.getId();

        List<IngredientExpiry> sourceLots = ingredientExpiryRepository
                .findByWarehouseIdAndIngredientIdOrderByExpiryDateAsc(srcId, ingId);

        BigDecimal remaining = qty;
        for (IngredientExpiry lot : sourceLots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = lot.getQuantity().min(remaining);

            lot.setQuantity(lot.getQuantity().subtract(take));
            lot.setUpdatedAt(now);
            if (lot.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
                ingredientExpiryRepository.delete(lot);
            } else {
                ingredientExpiryRepository.save(lot);
            }

            final LocalDate expDate = lot.getExpiryDate();
            final BigDecimal cost   = lot.getCostPrice();

            // Tìm lô cùng expiryDate + costPrice ở kho đích
            Optional<IngredientExpiry> destLot = ingredientExpiryRepository
                    .findByWarehouseIdAndIngredientIdAndExpiryDateAndCostPrice(
                            dstId, ingId, expDate, cost);

            if (destLot.isPresent()) {
                destLot.get().setQuantity(destLot.get().getQuantity().add(take));
                destLot.get().setUpdatedAt(now);
                ingredientExpiryRepository.save(destLot.get());
            } else {
                ingredientExpiryRepository.save(IngredientExpiry.builder()
                        .warehouse(dest)
                        .ingredientId(ingId)              // ← plain id
                        .expiryDate(expDate).costPrice(cost)
                        .quantity(take)
                        .createdAt(now).updatedAt(now)
                        .build());
            }
            remaining = remaining.subtract(take);
        }
    }

    // ── WAREHOUSE CRUD ────────────────────────────────────────────────────────

    public List<WarehouseResponse> getAllWarehouses() {
        return warehouseRepository.findByActiveTrue()
                .stream().map(this::mapWarehouse).collect(Collectors.toList());
    }

    @Transactional
    public WarehouseResponse createWarehouse(CreateWarehouseRequest req) {
        long now = System.currentTimeMillis();
        Warehouse w = Warehouse.builder()
                .name(req.getName()).address(req.getAddress())
                .type(req.getType()).active(true)
                .createdAt(now).updatedAt(now).build();
        return mapWarehouse(warehouseRepository.save(w));
    }

    @Transactional
    public WarehouseResponse updateWarehouse(Long id, CreateWarehouseRequest req) {
        Warehouse w = warehouseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Warehouse not found: " + id));
        w.setName(req.getName()); w.setAddress(req.getAddress()); w.setType(req.getType());
        w.setUpdatedAt(System.currentTimeMillis());
        return mapWarehouse(warehouseRepository.save(w));
    }

    // ── TỒN KHO ──────────────────────────────────────────────────────────────

    public List<StockResponse> getStockByWarehouse(Long warehouseId) {
        // IngredientStock.ingredientId là plain column — không cần JOIN FETCH ingredient
        List<IngredientStock> stocks = ingredientStockRepository.findByWarehouseIdWithIngredient(warehouseId);

        // Lấy Ingredient records để lấy tên/đơn vị/ảnh (chỉ active mới hiển thị)
        Set<Long> ingIds = stocks.stream().map(IngredientStock::getIngredientId).collect(Collectors.toSet());
        Map<Long, Ingredient> ingredientMap = ingredientRepository.findAllById(ingIds)
                .stream().collect(Collectors.toMap(Ingredient::getId, i -> i));

        List<IngredientExpiry> allExpiry = ingredientExpiryRepository.findByWarehouseId(warehouseId);
        // IngredientExpiry.ingredientId là plain column
        Map<Long, List<IngredientExpiry>> expiryMap = allExpiry.stream()
                .collect(Collectors.groupingBy(IngredientExpiry::getIngredientId));

        return stocks.stream().map(s -> {
            StockResponse r = new StockResponse();
            r.setIngredientId(s.getIngredientId());

            // Ưu tiên dùng Ingredient object nếu còn active, fallback về snapshot
            Ingredient ing = ingredientMap.get(s.getIngredientId());
            r.setIngredientName(ing != null ? ing.getName()     : s.resolvedName());
            r.setUnit         (ing != null ? ing.getUnit()     : s.resolvedUnit());
            r.setImageUrl     (ing != null ? ing.getImageUrl() : null);
            r.setStockQuantity(s.getStockQuantity());

            List<ExpiryInfo> expiryList = expiryMap
                    .getOrDefault(s.getIngredientId(), List.of())
                    .stream()
                    .filter(e -> e.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                    .map(e -> {
                        ExpiryInfo ei = new ExpiryInfo();
                        ei.setId(e.getId());
                        ei.setExpiryDate(e.getExpiryDate());
                        ei.setQuantity(e.getQuantity());
                        return ei;
                    }).collect(Collectors.toList());
            r.setExpiryList(expiryList);
            return r;
        }).collect(Collectors.toList());
    }

    // ── NHẬP KHO ─────────────────────────────────────────────────────────────

    @Transactional
    public ReceiptResponse importStock(ImportRequest req, Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse warehouse = getWarehouse(req.getWarehouseId());

        WarehouseReceipt receipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("IMP"))
                .receiptType(ReceiptType.IMPORT)
                .costStatus(CostStatus.PENDING_COST)
                .warehouse(warehouse)
                .referenceCode(req.getReferenceCode())
                .note(req.getNote())
                .imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now)
                .items(new ArrayList<>())
                .build();

        for (ImportItemRequest ir : req.getItems()) {
            Ingredient ing = getIngredient(ir.getIngredientId());
            receipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(receipt)
                    .ingredientId(ing.getId())                    // ← plain id
                    .ingredientNameSnapshot(ing.getName())        // ← snapshot
                    .ingredientUnitSnapshot(ing.getUnit())        // ← snapshot
                    .ingredientImageUrlSnapshot(ing.getImageUrl())// ← snapshot
                    .quantity(ir.getQuantity())
                    .quantityBefore(BigDecimal.ZERO)
                    .quantityAfter(BigDecimal.ZERO)
                    .difference(BigDecimal.ZERO)
                    .expiryDate(ir.getExpiryDate())
                    .costPrice(null)
                    .build());
        }

        WarehouseReceipt saved = warehouseReceiptRepository.save(receipt);
        notifyImportPendingCost(saved, warehouse);
        return mapReceipt(saved);
    }

    private void notifyImportPendingCost(WarehouseReceipt receipt, Warehouse warehouse) {
        try {
            String message = String.format("Phiếu nhập kho %s (%s) cần nhập giá vốn",
                    receipt.getReceiptCode(), warehouse.getName());
            String payload = String.format(
                    "{\"receiptId\":%d,\"receiptCode\":\"%s\",\"warehouseName\":\"%s\"}",
                    receipt.getId(), receipt.getReceiptCode(), warehouse.getName());
            List<User> targets = userRepository.findByRoleAndIsLockAccountFalse(Role.SUPER_ACCOUNTANT);
            for (User u : targets)
                notificationService.sendToUser(u, "SUPER_ACCOUNTANT", "IMPORT_PENDING_COST", message, payload);
        } catch (Exception e) {
            log.warn("[IMPORT] Failed to send notification: {}", e.getMessage());
        }
    }

    // ── XUẤT KHO ─────────────────────────────────────────────────────────────

    @Transactional
    public ReceiptResponse exportStock(ExportRequest req, Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse warehouse = getWarehouse(req.getWarehouseId());

        if (warehouse.getType() == WarehouseType.TRANSIT)
            throw new RuntimeException("Kho trung chuyển không được phép xuất bán.");
        if (req.getReason() == null || req.getReason().isBlank())
            throw new RuntimeException("Vui lòng nhập lý do xuất kho.");

        WarehouseReceipt receipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("EXP"))
                .receiptType(ReceiptType.EXPORT_OTHER)
                .costStatus(CostStatus.CONFIRMED)
                .warehouse(warehouse)
                .reason(req.getReason()).note(req.getNote())
                .imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now)
                .items(new ArrayList<>())
                .build();

        for (ExportItemRequest er : req.getItems()) {
            Ingredient ing = getIngredient(er.getIngredientId());
            IngredientStock stock = getOrCreateStock(warehouse, ing, now);
            checkSufficientStock(stock, er.getQuantity(), ing.getName());

            BigDecimal before = stock.getStockQuantity();
            BigDecimal after  = before.subtract(er.getQuantity());
            stock.setStockQuantity(after);
            stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);

            receipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(receipt)
                    .ingredientId(ing.getId())                    // ← plain id
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(er.getQuantity().negate())
                    .quantityBefore(before).quantityAfter(after)
                    .difference(after.subtract(before))
                    .build());
        }
        return mapReceipt(warehouseReceiptRepository.save(receipt));
    }

    // ── XUẤT KHO THEO ĐƠN HÀNG ───────────────────────────────────────────────

    @Transactional
    public ReceiptResponse exportForOrder(Long warehouseId, Order order,
                                          List<ExportItemRequest> items,
                                          Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse warehouse = getWarehouse(warehouseId);

        if (warehouse.getType() == WarehouseType.TRANSIT)
            throw new RuntimeException("Kho trung chuyển không được phép xuất bán.");

        WarehouseReceipt receipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("EXP"))
                .receiptType(ReceiptType.EXPORT_ORDER)
                .costStatus(CostStatus.CONFIRMED)
                .warehouse(warehouse).order(order)
                .referenceCode(order.getOrderCode())
                .note("Xuất kho theo đơn hàng: " + order.getOrderCode())
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now)
                .items(new ArrayList<>())
                .build();

        for (ExportItemRequest er : items) {
            Ingredient ing = getIngredient(er.getIngredientId());
            IngredientStock stock = getOrCreateStock(warehouse, ing, now);
            checkSufficientStock(stock, er.getQuantity(), ing.getName());

            BigDecimal before = stock.getStockQuantity();
            BigDecimal after  = before.subtract(er.getQuantity());
            stock.setStockQuantity(after);
            stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);

            fifoDeductService.deduct(warehouse, ing, er.getQuantity(), now);

            receipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(receipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(er.getQuantity().negate())
                    .quantityBefore(before).quantityAfter(after)
                    .difference(after.subtract(before))
                    .build());
        }
        return mapReceipt(warehouseReceiptRepository.save(receipt));
    }

    // ── CHUYỂN KHO ───────────────────────────────────────────────────────────

    @Transactional
    public TransferResponse transferStock(TransferRequest req, Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse fromWarehouse = getWarehouse(req.getFromWarehouseId());
        Warehouse toWarehouse   = getWarehouse(req.getToWarehouseId());

        if (fromWarehouse.getId().equals(toWarehouse.getId()))
            throw new RuntimeException("Kho nguồn và kho đích không được trùng nhau.");

        String transferNote = String.format("Chuyển kho từ [%s] sang [%s]",
                fromWarehouse.getName(), toWarehouse.getName());
        String fullNote = req.getNote() != null && !req.getNote().isBlank()
                ? transferNote + " - " + req.getNote() : transferNote;

        WarehouseReceipt outReceipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("TRO")).receiptType(ReceiptType.TRANSFER_OUT)
                .costStatus(CostStatus.CONFIRMED).warehouse(fromWarehouse).partnerWarehouse(toWarehouse)
                .note(fullNote).imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();

        WarehouseReceipt inReceipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("TRI")).receiptType(ReceiptType.TRANSFER_IN)
                .costStatus(CostStatus.CONFIRMED).warehouse(toWarehouse).partnerWarehouse(fromWarehouse)
                .note(fullNote).imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();

        for (TransferItemRequest tr : req.getItems()) {
            Ingredient ing = getIngredient(tr.getIngredientId());

            IngredientStock fromStock = getOrCreateStock(fromWarehouse, ing, now);
            checkSufficientStock(fromStock, tr.getQuantity(), ing.getName());
            BigDecimal fromBefore = fromStock.getStockQuantity();
            BigDecimal fromAfter  = fromBefore.subtract(tr.getQuantity());
            fromStock.setStockQuantity(fromAfter); fromStock.setUpdatedAt(now);
            ingredientStockRepository.save(fromStock);

            IngredientStock toStock = getOrCreateStock(toWarehouse, ing, now);
            BigDecimal toBefore = toStock.getStockQuantity();
            BigDecimal toAfter  = toBefore.add(tr.getQuantity());
            toStock.setStockQuantity(toAfter); toStock.setUpdatedAt(now);
            ingredientStockRepository.save(toStock);

            transferExpiryLots(fromWarehouse, toWarehouse, ing, tr.getQuantity(), now);

            outReceipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(outReceipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(tr.getQuantity().negate())
                    .quantityBefore(fromBefore).quantityAfter(fromAfter)
                    .difference(fromAfter.subtract(fromBefore)).build());

            inReceipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(inReceipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(tr.getQuantity())
                    .quantityBefore(toBefore).quantityAfter(toAfter)
                    .difference(toAfter.subtract(toBefore)).build());
        }

        WarehouseReceipt savedOut = warehouseReceiptRepository.save(outReceipt);
        WarehouseReceipt savedIn  = warehouseReceiptRepository.save(inReceipt);
        savedOut.setLinkedReceiptId(savedIn.getId());
        savedIn.setLinkedReceiptId(savedOut.getId());
        warehouseReceiptRepository.save(savedOut);
        warehouseReceiptRepository.save(savedIn);

        TransferResponse res = new TransferResponse();
        res.setOutReceipt(mapReceipt(savedOut));
        res.setInReceipt(mapReceipt(savedIn));
        return res;
    }

    // ── ĐIỀU CHỈNH KHO ───────────────────────────────────────────────────────

    @Transactional
    public ReceiptResponse adjustStock(AdjustRequest req, Long userId) {
        long now = System.currentTimeMillis();
        User user = getUser(userId);
        Warehouse warehouse = getWarehouse(req.getWarehouseId());

        WarehouseReceipt receipt = WarehouseReceipt.builder()
                .receiptCode(generateCode("ADJ")).receiptType(ReceiptType.ADJUST)
                .costStatus(CostStatus.CONFIRMED).warehouse(warehouse)
                .reason(req.getReason()).note(req.getNote())
                .imageUrls(toJson(req.getImageUrls()))
                .createdBy(user).createdByName(resolveUserName(user))
                .createdAt(now).updatedAt(now).items(new ArrayList<>()).build();

        for (AdjustItemRequest ar : req.getItems()) {
            Ingredient ing = getIngredient(ar.getIngredientId());
            IngredientStock stock = getOrCreateStock(warehouse, ing, now);

            BigDecimal before = stock.getStockQuantity();
            BigDecimal after  = ar.getPhysicalQty();
            BigDecimal diff   = after.subtract(before);

            AdjustResult result;
            if      (diff.compareTo(BigDecimal.ZERO) > 0) result = AdjustResult.SURPLUS;
            else if (diff.compareTo(BigDecimal.ZERO) < 0) result = AdjustResult.SHORTAGE;
            else                                           result = AdjustResult.MATCH;

            stock.setStockQuantity(after); stock.setUpdatedAt(now);
            ingredientStockRepository.save(stock);

            receipt.getItems().add(WarehouseReceiptItem.builder()
                    .receipt(receipt)
                    .ingredientId(ing.getId())
                    .ingredientNameSnapshot(ing.getName())
                    .ingredientUnitSnapshot(ing.getUnit())
                    .ingredientImageUrlSnapshot(ing.getImageUrl())
                    .quantity(diff)
                    .quantityBefore(before).quantityAfter(after).difference(diff)
                    .physicalQty(ar.getPhysicalQty()).adjustResult(result)
                    .build());
        }
        return mapReceipt(warehouseReceiptRepository.save(receipt));
    }

    // ── LỊCH SỬ ──────────────────────────────────────────────────────────────

    public Page<ReceiptResponse> getHistory(List<ReceiptType> types, Long warehouseId,
                                            int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<WarehouseReceipt> receipts = warehouseId != null
                ? warehouseReceiptRepository.findByWarehouseIdAndTypes(warehouseId, types, pageable)
                : warehouseReceiptRepository.findByTypes(types, pageable);
        return receipts.map(this::mapReceipt);
    }

    public ReceiptResponse getReceiptDetail(Long receiptId) {
        return mapReceipt(warehouseReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new RuntimeException("Receipt not found: " + receiptId)));
    }

    // ── NOTIFICATION ─────────────────────────────────────────────────────────

    public void notifyCostConfirmed(WarehouseReceipt receipt, String confirmedByName) {
        try {
            String message = String.format(
                    "Phiếu nhập kho %s (%s) đã được xác nhận giá vốn bởi %s. Tồn kho đã cập nhật.",
                    receipt.getReceiptCode(), receipt.getWarehouse().getName(), confirmedByName);
            String payload = String.format(
                    "{\"receiptId\":%d,\"receiptCode\":\"%s\",\"warehouseName\":\"%s\",\"confirmedBy\":\"%s\"}",
                    receipt.getId(), receipt.getReceiptCode(),
                    receipt.getWarehouse().getName(), confirmedByName);

            List<User> admins = userRepository.findByRoleAndIsLockAccountFalse(Role.ADMIN);
            List<User> owners = userRepository.findByRoleAndIsLockAccountFalse(Role.OWNER);
            for (User u : admins) notificationService.sendToUser(u, "COST_CONFIRMED", message, payload);
            for (User u : owners) notificationService.sendToUser(u, "COST_CONFIRMED", message, payload);
            notificationService.sendToRole("ADMIN", "COST_CONFIRMED", message, payload);
            notificationService.sendToRole("OWNER", "COST_CONFIRMED", message, payload);
        } catch (Exception e) {
            log.warn("[COST_CONFIRMED] Failed to send notification: {}", e.getMessage());
        }
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    private Warehouse getWarehouse(Long id) {
        return warehouseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Warehouse not found: " + id));
    }

    private Ingredient getIngredient(Long id) {
        return ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found: " + id));
    }

    private User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
    }

    /**
     * Tạo hoặc lấy IngredientStock.
     * THAY ĐỔI: không set .ingredient(ingredient) nữa — set .ingredientId() + snapshot.
     */
    private IngredientStock getOrCreateStock(Warehouse warehouse, Ingredient ingredient, long now) {
        return ingredientStockRepository
                .findByIngredientIdAndWarehouseId(ingredient.getId(), warehouse.getId())
                .orElseGet(() -> ingredientStockRepository.save(
                        IngredientStock.builder()
                                .ingredientId(ingredient.getId())                // ← plain id
                                .ingredientNameSnapshot(ingredient.getName())    // ← snapshot
                                .ingredientUnitSnapshot(ingredient.getUnit())    // ← snapshot
                                .warehouse(warehouse)
                                .stockQuantity(BigDecimal.ZERO)
                                .updatedAt(now)
                                .build()));
    }

    private void checkSufficientStock(IngredientStock stock, BigDecimal needed, String ingName) {
        if (stock.getStockQuantity().compareTo(needed) < 0)
            throw new RuntimeException(String.format(
                    "Không đủ tồn kho '%s' tại kho '%s' (còn: %s, cần: %s)",
                    ingName, stock.getWarehouse().getName(),
                    stock.getStockQuantity(), needed));
    }

    private void addExpiry(Warehouse warehouse, Ingredient ingredient,
                           BigDecimal quantity, LocalDate expiryDate,
                           BigDecimal costPrice, long now) {
        ingredientExpiryRepository
                .findByWarehouseIdAndIngredientId(warehouse.getId(), ingredient.getId())
                .stream()
                .filter(e -> java.util.Objects.equals(e.getExpiryDate(), expiryDate)
                        && java.util.Objects.equals(e.getCostPrice(), costPrice))
                .findFirst()
                .ifPresentOrElse(e -> {
                    e.setQuantity(e.getQuantity().add(quantity));
                    e.setUpdatedAt(now);
                    ingredientExpiryRepository.save(e);
                }, () -> ingredientExpiryRepository.save(
                        IngredientExpiry.builder()
                                .warehouse(warehouse)
                                .ingredientId(ingredient.getId()) // ← plain id
                                .expiryDate(expiryDate).quantity(quantity)
                                .costPrice(costPrice)
                                .createdAt(now).updatedAt(now)
                                .build()));
    }

    private String generateCode(String prefix) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String rand = String.format("%04d", new Random().nextInt(10000));
        String code = prefix + "-" + date + "-" + rand;
        while (warehouseReceiptRepository.existsByReceiptCode(code)) {
            rand = String.format("%04d", new Random().nextInt(10000));
            code = prefix + "-" + date + "-" + rand;
        }
        return code;
    }

    private String resolveUserName(User user) {
        return (user.getFullName() != null && !user.getFullName().isBlank())
                ? user.getFullName() : user.getUsername();
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(list); }
        catch (JsonProcessingException e) { return null; }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private WarehouseResponse mapWarehouse(Warehouse w) {
        WarehouseResponse r = new WarehouseResponse();
        r.setId(w.getId()); r.setName(w.getName()); r.setAddress(w.getAddress());
        r.setType(w.getType()); r.setActive(w.isActive());
        return r;
    }

    public ReceiptResponse mapReceipt(WarehouseReceipt receipt) {
        ReceiptResponse r = new ReceiptResponse();
        r.setId(receipt.getId());
        r.setReceiptCode(receipt.getReceiptCode());
        r.setReceiptType(receipt.getReceiptType());
        r.setWarehouseName(receipt.getWarehouse().getName());
        r.setWarehouseId(receipt.getWarehouse().getId());
        if (receipt.getPartnerWarehouse() != null) {
            r.setPartnerWarehouseName(receipt.getPartnerWarehouse().getName());
            r.setPartnerWarehouseId(receipt.getPartnerWarehouse().getId());
        }
        r.setReferenceCode(receipt.getReferenceCode());
        r.setReason(receipt.getReason());
        r.setNote(receipt.getNote());
        r.setImageUrls(fromJson(receipt.getImageUrls()));
        r.setCreatedByName(receipt.getCreatedByName());
        r.setCreatedAt(receipt.getCreatedAt());

        // THAY ĐỔI: đọc từ snapshot thay vì item.getIngredient().getXxx()
        List<ReceiptItemResponse> items = receipt.getItems().stream().map(item -> {
            ReceiptItemResponse ir = new ReceiptItemResponse();
            ir.setIngredientId(item.getIngredientId());                  // ← plain id
            ir.setIngredientName(item.resolvedIngredientName());         // ← snapshot
            ir.setUnit(item.resolvedIngredientUnit());                   // ← snapshot
            ir.setImageUrl(item.resolvedIngredientImageUrl());           // ← snapshot
            ir.setQuantity(item.getQuantity());
            ir.setQuantityBefore(item.getQuantityBefore());
            ir.setQuantityAfter(item.getQuantityAfter());
            ir.setDifference(item.getDifference());
            ir.setPhysicalQty(item.getPhysicalQty());
            ir.setAdjustResult(item.getAdjustResult() != null ? item.getAdjustResult().name() : null);
            ir.setExpiryDate(item.getExpiryDate());
            return ir;
        }).collect(Collectors.toList());

        r.setItems(items);
        return r;
    }
}
