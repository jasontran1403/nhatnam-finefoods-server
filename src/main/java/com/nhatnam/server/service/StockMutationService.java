package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.InsufficientStockException;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.IngredientStock;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.IngredientStockRepository;
import com.nhatnam.server.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ==================================================================
 *  Race-safe orchestrator cho MỌI thao tác đổi tồn kho nguyên liệu.
 *
 *  Thay thế các pattern read-modify-write cũ trong OrderServiceImpl,
 *  WarehouseService, OrderReturnService, WarehouseReceiptCostService,
 *  FifoDeductService bằng các atomic UPDATE.
 *
 *  Nguyên tắc:
 *   1. MỌI mutation dùng atomic UPDATE ở repository (không read-modify-write)
 *   2. Sau UPDATE, `after` được TÍNH TOÁN HỌC từ `before` + delta — KHÔNG
 *      đọc lại qua Hibernate. Xem BUG FIX KB13 bên dưới.
 *   3. Insufficient stock → throw {@link InsufficientStockException} với
 *      thông tin chi tiết cho FE hiển thị inline
 *   4. Method dùng {@code Propagation.MANDATORY} — luôn nằm trong transaction
 *      của caller. Không tự tạo transaction để đảm bảo tất cả mutation trong
 *      1 nghiệp vụ được rollback cùng nhau nếu 1 bước fail.
 *
 *  Deadlock guard: khi caller trừ nhiều ingredient trong 1 loop, PHẢI sort
 *  ingredientId ASC trước khi loop. Không thì 2 transaction lock chéo.
 *
 *  BUG FIX KB13 (stale L1 cache after JPQL UPDATE):
 *  Trước: `before = readCurrentQty(...); UPDATE ...; after = readCurrentQty(...)`.
 *  Vấn đề: các câu `@Modifying` UPDATE của repository KHÔNG bật
 *  `clearAutomatically = true` (đã bỏ để tránh detach entity khác trong session),
 *  nên sau UPDATE, entity `IngredientStock` trong Hibernate L1 cache VẪN giữ giá
 *  trị cũ. Đọc lại qua `findByIngredientIdAndWarehouseId()` trả về từ L1 → stale.
 *  Hệ quả: `after == before`, `WarehouseReceiptItem.quantity_after` bị ghi sai
 *  (history hiển thị "Sau = Trước") dù DB đã update đúng.
 *  Fix: tính `after` bằng toán học sau khi UPDATE thành công. Vì UPDATE là atomic
 *  và trả về `updated=1`, ta biết chính xác giá trị mới = `before ± qty`.
 * ==================================================================
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockMutationService {

    private final IngredientStockRepository stockRepository;
    private final IngredientRepository ingredientRepository;
    private final WarehouseRepository warehouseRepository;

    // ══════════════════════════════════════════════════════════════════
    //  INCREASE — cộng tồn (nhập kho, hoàn kho, cancel order)
    // ══════════════════════════════════════════════════════════════════

    @Transactional(propagation = Propagation.MANDATORY)
    public StockMutationResult increase(Long ingredientId, Long warehouseId,
                                        BigDecimal qty, long now) {
        validate(ingredientId, warehouseId, qty);

        BigDecimal before = readCurrentQty(ingredientId, warehouseId);
        int updated = stockRepository.increaseStockAtomic(ingredientId, warehouseId, qty, now);
        if (updated == 0) {
            // Row chưa có → tạo mới với qty
            createStockRow(ingredientId, warehouseId, qty, BigDecimal.ZERO, now);
            return new StockMutationResult(ingredientId, warehouseId,
                    BigDecimal.ZERO, qty, qty);
        }

        // BUG FIX KB13: KHÔNG đọc lại qua repository — L1 cache còn stale.
        // Increase atomic khi updated=1 → giá trị mới chắc chắn = before + qty.
        BigDecimal after = before.add(qty);
        return new StockMutationResult(ingredientId, warehouseId, before, after, qty);
    }

    // ══════════════════════════════════════════════════════════════════
    //  DECREASE — trừ tồn (bán hàng, xuất kho, chuyển kho ra)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Trừ tồn ATOMIC. Nếu không đủ hàng, throw {@link InsufficientStockException}
     * với thông tin chi tiết (available/needed/ingredient name/warehouse name).
     *
     * <p>Dùng cho: createOrder, exportForOrder — flow bán hàng đã có POS lock
     * riêng nên không cần check held.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMutationResult decrease(Long ingredientId, Long warehouseId,
                                        BigDecimal qty, long now) {
        validate(ingredientId, warehouseId, qty);

        BigDecimal before = readCurrentQty(ingredientId, warehouseId);
        int updated = stockRepository.decreaseStockAtomic(ingredientId, warehouseId, qty, now);
        if (updated == 0) {
            throw buildInsufficientException(ingredientId, warehouseId, before, qty);
        }
        // BUG FIX KB13: KHÔNG đọc lại qua repository — L1 cache còn stale.
        // Decrease atomic khi updated=1 → giá trị mới chắc chắn = before - qty.
        BigDecimal after = before.subtract(qty);
        return new StockMutationResult(ingredientId, warehouseId, before, after, qty.negate());
    }

    /**
     * Trừ tồn ATOMIC, tôn trọng phần đang bị POS giữ trong giỏ (held).
     *
     * <p>Dùng cho: exportStock, transferStock, adjust giảm — flow không phải bán
     * hàng nhưng phải chừa phần POS đang giữ để không "cướp" hàng của khách.
     *
     * @param overrideHold true = bỏ qua check held (chỉ OWNER/SUPERADMIN được phép)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMutationResult decreaseRespectingHolds(Long ingredientId, Long warehouseId,
                                                       BigDecimal qty, boolean overrideHold,
                                                       long now) {
        validate(ingredientId, warehouseId, qty);

        BigDecimal before = readCurrentQty(ingredientId, warehouseId);
        BigDecimal held = BigDecimal.ZERO;
        // TODO: nếu có CartHoldService, inject và query held ở đây.
        // Giữ đơn giản trong bản này: không check held (behavior giống decrease).

        BigDecimal available = before.subtract(held);
        if (!overrideHold && available.compareTo(qty) < 0) {
            throw buildInsufficientException(ingredientId, warehouseId, available, qty);
        }

        int updated = stockRepository.decreaseStockAtomic(ingredientId, warehouseId, qty, now);
        if (updated == 0) {
            throw buildInsufficientException(ingredientId, warehouseId, before, qty);
        }
        // BUG FIX KB13: KHÔNG đọc lại qua repository — L1 cache còn stale.
        // Decrease atomic khi updated=1 → giá trị mới chắc chắn = before - qty.
        BigDecimal after = before.subtract(qty);
        return new StockMutationResult(ingredientId, warehouseId, before, after, qty.negate());
    }

    // ══════════════════════════════════════════════════════════════════
    //  COST VALUE — cộng/trừ giá vốn tổng
    // ══════════════════════════════════════════════════════════════════

    /** Cộng totalCostValue ATOMIC. Dùng confirm cost, hoàn cost khi cancel/return. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addCostValue(Long ingredientId, Long warehouseId,
                             BigDecimal costValue, long now) {
        if (costValue == null || costValue.compareTo(BigDecimal.ZERO) <= 0) return;
        stockRepository.addCostValueAtomic(ingredientId, warehouseId, costValue, now);
    }

    /** Trừ totalCostValue ATOMIC (sàn 0). Dùng khi FIFO deduct bán hàng. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void subCostValue(Long ingredientId, Long warehouseId,
                             BigDecimal costValue, long now) {
        if (costValue == null || costValue.compareTo(BigDecimal.ZERO) <= 0) return;
        stockRepository.subCostValueAtomic(ingredientId, warehouseId, costValue, now);
    }

    // ══════════════════════════════════════════════════════════════════
    //  TRANSFER — chuyển kho A→B trong 1 transaction
    // ══════════════════════════════════════════════════════════════════

    @Value
    public static class TransferResult {
        StockMutationResult from;   // kho nguồn
        StockMutationResult to;     // kho đích
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TransferResult transfer(Long ingredientId, Long fromWarehouseId, Long toWarehouseId,
                                   BigDecimal qty, boolean overrideHold, long now) {
        if (fromWarehouseId.equals(toWarehouseId)) {
            throw new BusinessException("Kho nguồn và kho đích không được trùng nhau");
        }
        // Trừ kho nguồn (throw nếu thiếu — rollback toàn bộ transaction)
        StockMutationResult from = decreaseRespectingHolds(ingredientId, fromWarehouseId, qty, overrideHold, now);
        // Cộng kho đích (đảm bảo row tồn tại)
        StockMutationResult to = increase(ingredientId, toWarehouseId, qty, now);
        return new TransferResult(from, to);
    }

    // ══════════════════════════════════════════════════════════════════
    //  ADJUST TO ACTUAL — kiểm kê (Bug 2.4 fix)
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>BUG FIX 2.4</b>: Điều chỉnh về số thực tế thông qua DELTA (không ghi đè tuyệt đối).
     *
     * <p>Cách hoạt động:
     * <ol>
     *   <li>Đọc current từ DB (có thể stale nhưng chỉ dùng để tính delta)</li>
     *   <li>Tính delta = countedQty - current</li>
     *   <li>Cộng/trừ delta ATOMIC (áp dụng lên số MỚI NHẤT của DB, không phải snapshot)</li>
     * </ol>
     *
     * <p>Kết quả: nếu DB đã đổi giữa lúc đọc và write, delta vẫn được áp dụng
     * lên số mới → không mất doanh thu/nhập kho xảy ra trong lúc kiểm kê.
     *
     * <p><b>Chú ý</b>: nếu delta &lt; 0 nhưng lớn hơn tồn hiện tại, decrease sẽ
     * throw InsufficientStockException. Đây là hành vi ĐÚNG — không cho phép
     * kiểm kê làm âm tồn.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMutationResult adjustToActual(Long ingredientId, Long warehouseId,
                                              BigDecimal countedQty, long now) {
        if (countedQty == null || countedQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("countedQty không được null hoặc âm");
        }

        BigDecimal current = readCurrentQty(ingredientId, warehouseId);
        BigDecimal delta = countedQty.subtract(current);

        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            // Không thay đổi
            return new StockMutationResult(ingredientId, warehouseId, current, current, BigDecimal.ZERO);
        }

        if (delta.compareTo(BigDecimal.ZERO) > 0) {
            return increase(ingredientId, warehouseId, delta, now);
        } else {
            return decreaseRespectingHolds(ingredientId, warehouseId, delta.abs(), false, now);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════

    private void validate(Long ingredientId, Long warehouseId, BigDecimal qty) {
        if (ingredientId == null) throw new IllegalArgumentException("ingredientId is null");
        if (warehouseId == null) throw new IllegalArgumentException("warehouseId is null");
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("qty phải > 0, nhận: " + qty);
        }
    }

    private BigDecimal readCurrentQty(Long ingredientId, Long warehouseId) {
        return stockRepository.findByIngredientIdAndWarehouseId(ingredientId, warehouseId)
                .map(IngredientStock::getStockQuantity)
                .map(q -> q != null ? q : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);
    }

    private void createStockRow(Long ingredientId, Long warehouseId,
                                BigDecimal initialQty, BigDecimal initialCost, long now) {
        Ingredient ing = ingredientRepository.findById(ingredientId)
                .orElseThrow(() -> new BusinessException("Nguyên liệu không tồn tại: " + ingredientId));
        Warehouse wh = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException("Kho không tồn tại: " + warehouseId));

        stockRepository.save(IngredientStock.builder()
                .ingredientId(ingredientId)
                .ingredientNameSnapshot(ing.getName())
                .ingredientUnitSnapshot(ing.getUnit())
                .warehouse(wh)
                .stockQuantity(initialQty.setScale(3, RoundingMode.HALF_UP))
                .totalCostValue(initialCost.setScale(2, RoundingMode.HALF_UP))
                .updatedAt(now)
                .build());
    }

    private InsufficientStockException buildInsufficientException(Long ingredientId, Long warehouseId,
                                                                  BigDecimal available, BigDecimal needed) {
        IngredientStock stock = stockRepository.findByIngredientIdAndWarehouseId(ingredientId, warehouseId)
                .orElse(null);
        String ingName = stock != null && stock.getIngredientNameSnapshot() != null
                ? stock.getIngredientNameSnapshot()
                : ingredientRepository.findById(ingredientId).map(Ingredient::getName).orElse("id=" + ingredientId);
        String whName = stock != null && stock.getWarehouse() != null
                ? stock.getWarehouse().getName()
                : warehouseRepository.findById(warehouseId).map(Warehouse::getName).orElse("id=" + warehouseId);
        String unit = stock != null ? stock.getIngredientUnitSnapshot() : "";

        return new InsufficientStockException(
                ingredientId, warehouseId, ingName, whName, unit,
                available != null ? available : BigDecimal.ZERO, needed);
    }
}