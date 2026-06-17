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

        // FIFO: dùng ingredientId plain column
        List<IngredientExpiry> lots = expiryRepository
                .findFifoLots(warehouse.getId(), ingredientId);

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
                        .ingredientExpiry(lot.getQuantity().compareTo(BigDecimal.ZERO) > 0 ? lot : null)
                        .expiryDate(lot.getExpiryDate())
                        .costPrice(lot.getCostPrice())
                        .quantity(take)
                        .createdAt(now)
                        .build());
            }
            remaining = remaining.subtract(take);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("[FIFO] {} còn {} chưa khớp lô HSD tại kho {}",
                    ingredientName != null ? ingredientName : "ingId=" + ingredientId,
                    remaining, warehouse.getName());
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
            BigDecimal updated = currentCostValue.subtract(toDeduct)
                    .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            stock.setTotalCostValue(updated);
            stock.setUpdatedAt(now);
            stockRepository.save(stock);
            log.debug("[FIFO] ingId={} -{} units → cost -{} → remaining cost {}",
                    ingredientId, needed, toDeduct, updated);
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
