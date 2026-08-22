package com.nhatnam.server.service;

import com.nhatnam.server.entity.FinishedGoodsStock;
import com.nhatnam.server.entity.ProductionBatch;
import com.nhatnam.server.entity.WorkOrder;
import com.nhatnam.server.repository.FinishedGoodsStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * BACKFILL 1 LẦN — vá dữ liệu cũ của Kho thành phẩm.
 *
 * <p>Trước bugfix, {@code SemiFinishedGoodsService.confirmReceive} tạo lô
 * {@link FinishedGoodsStock} mà KHÔNG set {@code factoryId} / {@code factoryNameSnapshot}.
 * Hệ quả: page "Kho thành phẩm" (lọc theo xưởng đang chọn) luôn hiển thị 0 lô,
 * dù phiếu chuyển kho bán thành phẩm đã được xác nhận nhận.
 *
 * <p>Component này chạy 1 lần lúc app khởi động: tìm các lô có {@code factoryId = null}
 * và suy ngược xưởng qua {@code batch → workOrder → productionFactory}. Lô nào không suy
 * được (không còn batch) thì bỏ qua và ghi log — cần sửa tay.
 *
 * <p>An toàn để chạy lặp lại (idempotent): sau lần đầu, danh sách null sẽ rỗng.
 * Có thể xoá class này sau khi dữ liệu đã sạch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinishedGoodsFactoryBackfill {

    private final FinishedGoodsStockRepository stockRepo;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        List<FinishedGoodsStock> orphans = stockRepo.findByFactoryIdIsNull();
        if (orphans.isEmpty()) return;

        int fixed = 0, skipped = 0;
        for (FinishedGoodsStock lot : orphans) {
            ProductionBatch batch = lot.getBatch();
            WorkOrder wo = batch != null ? batch.getWorkOrder() : null;
            if (wo == null || wo.getProductionFactory() == null) {
                skipped++;
                continue;
            }
            lot.setFactoryId(wo.getProductionFactory().getId());
            lot.setFactoryNameSnapshot(wo.getProductionFactoryName() != null
                    ? wo.getProductionFactoryName()
                    : wo.getProductionFactory().getName());
            stockRepo.save(lot);
            fixed++;
        }
        log.info("[FinishedGoodsFactoryBackfill] Đã gán xưởng cho {} lô kho thành phẩm, bỏ qua {} lô (không truy được xưởng).",
                fixed, skipped);
    }
}
