package com.nhatnam.server.config;

import com.nhatnam.server.entity.FactoryMaterialStock;
import com.nhatnam.server.entity.MaterialRequestItem;
import com.nhatnam.server.repository.FactoryMaterialStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfill {@code unitCost} cho các lô nguyên liệu kho xưởng đã tồn tại TRƯỚC khi
 * có tính năng giá vốn theo lô.
 *
 * <p>Chạy 1 lần lúc khởi động, idempotent (chỉ đụng lô có unitCost null hoặc 0 và
 * có gắn dòng phiếu đặt hàng). Giá vốn lấy đúng công thức đang dùng ở
 * {@code MaterialRequestService.complete}:
 * <pre>
 *   unitCost = lineAmount của dòng phiếu / tổng số lượng đã nhập kho của dòng đó
 * </pre>
 * Dòng phiếu chưa được kế toán trưởng chốt giá ({@code lineAmount} null) sẽ được bỏ
 * qua — lô giữ giá vốn 0, và sẽ tự có giá khi phiếu được Hoàn thành.
 *
 * <p>Sau khi toàn bộ lô cũ đã có giá vốn, có thể xoá class này an toàn.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FactoryMaterialLotCostBackfill implements ApplicationRunner {

    private final FactoryMaterialStockRepository stockRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<FactoryMaterialStock> pending = stockRepo.findLotsMissingUnitCost();
        if (pending.isEmpty()) return;

        // Gom các lô theo dòng phiếu đặt hàng — 1 dòng có thể sinh nhiều lô
        Map<Long, List<FactoryMaterialStock>> byItem = new LinkedHashMap<>();
        Map<Long, MaterialRequestItem> itemById = new LinkedHashMap<>();
        for (FactoryMaterialStock lot : pending) {
            MaterialRequestItem item = lot.getMaterialRequestItem();
            if (item == null) continue;
            byItem.computeIfAbsent(item.getId(), k -> new ArrayList<>()).add(lot);
            itemById.putIfAbsent(item.getId(), item);
        }

        List<FactoryMaterialStock> updated = new ArrayList<>();
        int skipped = 0;

        for (Map.Entry<Long, List<FactoryMaterialStock>> e : byItem.entrySet()) {
            MaterialRequestItem item = itemById.get(e.getKey());
            BigDecimal lineAmount = item.getLineAmount();
            if (lineAmount == null || lineAmount.compareTo(BigDecimal.ZERO) <= 0) {
                skipped++;   // phiếu chưa chốt giá → để 0, sẽ có giá khi Hoàn thành phiếu
                continue;
            }

            // Chia theo TỔNG số lượng đã nhập kho của dòng (không phải qtyReceived),
            // để tự đúng cả khi nhận theo đơn vị đặt hàng (thùng → kg).
            BigDecimal stockedQty = e.getValue().stream()
                    .map(l -> l.getInitialQuantity() != null ? l.getInitialQuantity() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (stockedQty.compareTo(BigDecimal.ZERO) <= 0) {
                skipped++;
                continue;
            }

            BigDecimal unitCost = lineAmount.divide(stockedQty, 2, RoundingMode.HALF_UP);
            for (FactoryMaterialStock lot : e.getValue()) {
                lot.setUnitCost(unitCost);
                updated.add(lot);
            }
        }

        stockRepo.saveAll(updated);
        log.info("[FactoryMaterialLotCostBackfill] Đã gán giá vốn cho {} lô nguyên liệu; "
                + "bỏ qua {} dòng phiếu chưa chốt giá.", updated.size(), skipped);
    }
}