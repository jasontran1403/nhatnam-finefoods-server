package com.nhatnam.server.service;

import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.IngredientExpiryRepository;
import com.nhatnam.server.repository.IngredientStockRepository;
import com.nhatnam.server.repository.OrderStockDeductionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class FifoDeductService {

    private final IngredientExpiryRepository    expiryRepository;
    private final IngredientStockRepository     stockRepository;
    private final OrderStockDeductionRepository deductionRepository;
    private final StockMutationService          stockMutationService;   // BUG FIX (cross-field lost update)

    /**
     * Trừ kho theo FIFO.
     *
     * THAY ĐỔI: nhận Ingredient object để tương thích ngược với callers hiện tại,
     * nhưng nội bộ chỉ dùng ingredient.getId() vì IngredientExpiry + IngredientStock
     * đã chuyển sang plain column.
     */
    @Transactional
    public BigDecimal deduct(Long orderId, Warehouse warehouse, Ingredient ingredient,
                             BigDecimal needed, long now) {
        return deductById(orderId, warehouse, ingredient.getId(), ingredient.getName(), needed, now);
    }

    /**
     * Overload dùng ingredientId trực tiếp — dùng khi không có Ingredient object
     * (ví dụ: khi ingredient đã bị soft-delete nhưng vẫn cần hoàn kho).
     */
    @Transactional
    public BigDecimal deductById(Long orderId, Warehouse warehouse, Long ingredientId,
                                 String ingredientName, BigDecimal needed, long now) {

        IngredientStock stock = stockRepository
                .findByIngredientIdAndWarehouseId(ingredientId, warehouse.getId())
                .orElse(null);

        BigDecimal currentStock     = stock != null && stock.getStockQuantity() != null
                ? stock.getStockQuantity() : BigDecimal.ZERO;
        BigDecimal currentCostValue = stock != null && stock.getTotalCostValue() != null
                ? stock.getTotalCostValue() : BigDecimal.ZERO;

        BigDecimal avgCost = BigDecimal.ZERO;
        if (currentStock.compareTo(BigDecimal.ZERO) > 0
                && currentCostValue.compareTo(BigDecimal.ZERO) > 0) {
            avgCost = currentCostValue.divide(currentStock, 6, RoundingMode.HALF_UP);
        }

        // BUG FIX 1.3: dùng PESSIMISTIC_WRITE thay findFifoLots không lock.
        // Trước: 2 request FIFO song song cùng đọc list lô, cùng chọn lô đầu,
        // cùng trừ → lô có thể xuống âm hoặc tổng lô vượt số hàng thực.
        // Sau: SELECT ... FOR UPDATE khóa các row lô đến khi transaction commit.
        // Request thứ 2 phải chờ → không race.
        //
        // DEADLOCK GUARD: caller (OrderServiceImpl.createOrder, updateOrderItems,
        // WarehouseService.exportForOrder) PHẢI sort ingredientId ASC khi trừ
        // nhiều ingredient trong 1 transaction.
        List<IngredientExpiry> lots = expiryRepository
                .findFifoLotsForUpdate(warehouse.getId(), ingredientId);

        BigDecimal remaining    = needed;
        BigDecimal costDeducted = BigDecimal.ZERO;

        for (IngredientExpiry lot : lots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = remaining.min(lot.getQuantity());

            BigDecimal unitCost = (lot.getCostPrice() != null
                    && lot.getCostPrice().compareTo(BigDecimal.ZERO) > 0)
                    ? lot.getCostPrice() : avgCost;

            if (unitCost.compareTo(BigDecimal.ZERO) > 0)
                costDeducted = costDeducted.add(
                        take.multiply(unitCost).setScale(2, RoundingMode.HALF_UP));

            lot.setQuantity(lot.getQuantity().subtract(take).setScale(3, RoundingMode.HALF_UP));
            lot.setUpdatedAt(now);
            expiryRepository.save(lot);

            if (orderId != null && stock != null) {
                deductionRepository.save(OrderStockDeduction.builder()
                        .orderId(orderId)
                        .ingredientStock(stock)
                        // LUÔN gắn lô, kể cả khi lô vừa bị trừ về 0.
                        //
                        //   Bản cũ ghi null cho lô hết hàng. Hậu quả: lúc huỷ đơn,
                        //   restoreStock() thấy ingredientExpiry == null nên chỉ
                        //   cộng lại TỒN TỔNG mà không cộng lại LÔ ⇒ tồn tổng nhiều
                        //   hơn tổng lô, và phần chênh đó vĩnh viễn không xuất được
                        //   vì FIFO trừ theo lô.
                        //
                        //   Lô hết hàng KHÔNG bị xoá (giữ quantity = 0 vì FK từ
                        //   OrderStockDeduction), nên tham chiếu tới nó vẫn hợp lệ.
                        .ingredientExpiry(lot)
                        .expiryDate(lot.getExpiryDate())
                        .costPrice(lot.getCostPrice())
                        .quantity(take)
                        .createdAt(now)
                        .build());
            }
            remaining = remaining.subtract(take);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            // LỆCH SỐ LIỆU: tồn tổng cho phép trừ nhiều hơn số thực có trong các lô.
            //
            //   Người gọi đã (hoặc sắp) trừ TỒN TỔNG đủ `needed`, nhưng các lô chỉ
            //   gánh được tới đây. Bỏ qua phần thiếu ⇒ tổng lô còn nhiều hơn tồn
            //   tổng, và mỗi lần xuất kho lại nới rộng khoảng lệch.
            //
            //   Dùng ERROR chứ không WARN: đây là hỏng dữ liệu tồn kho, không phải
            //   tình huống vận hành bình thường, phải nổi lên trong cảnh báo log.
            log.error("[FIFO] LỆCH TỒN KHO — '{}' tại kho '{}': cần trừ {} nhưng các lô "
                            + "chỉ còn {}. Tồn tổng đang LỚN HƠN tổng lô. "
                            + "Cần kiểm kê và tạo lô bù cho phần chênh.",
                    ingredientName != null ? ingredientName : "ingId=" + ingredientId,
                    warehouse.getName(), needed, needed.subtract(remaining));
            if (avgCost.compareTo(BigDecimal.ZERO) > 0)
                costDeducted = costDeducted.add(
                        remaining.multiply(avgCost).setScale(2, RoundingMode.HALF_UP));
            if (orderId != null && stock != null) {
                deductionRepository.save(OrderStockDeduction.builder()
                        .orderId(orderId)
                        .ingredientStock(stock)
                        .ingredientExpiry(null)
                        .expiryDate(null)
                        .costPrice(avgCost.compareTo(BigDecimal.ZERO) > 0 ? avgCost : null)
                        .quantity(remaining)
                        .createdAt(now)
                        .build());
            }
        }

        BigDecimal toDeduct = costDeducted;
        if (toDeduct.compareTo(BigDecimal.ZERO) == 0
                && avgCost.compareTo(BigDecimal.ZERO) > 0)
            toDeduct = needed.multiply(avgCost).setScale(2, RoundingMode.HALF_UP);

        if (toDeduct.compareTo(BigDecimal.ZERO) > 0 && stock != null) {
            // BUG FIX (cross-field lost update): atomic sub thay cho read-modify-write.
            // Trước: read currentCostValue → subtract → save → race với các flow
            // khác ghi totalCostValue (confirm cost, cancel restore).
            // Sau: 1 câu UPDATE atomic ép sàn 0.
            stockMutationService.subCostValue(ingredientId, warehouse.getId(), toDeduct, now);
            log.debug("[FIFO] ingId={} -{} units → cost -{} (atomic)",
                    ingredientId, needed, toDeduct);
        }

        return costDeducted;
    }

    /** Backward-compat overload không có orderId */
    @Transactional
    public BigDecimal deduct(Warehouse warehouse, Ingredient ingredient,
                             BigDecimal needed, long now) {
        return deduct(null, warehouse, ingredient, needed, now);
    }
}