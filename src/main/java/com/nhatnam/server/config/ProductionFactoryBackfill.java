package com.nhatnam.server.config;

import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Backfill dữ liệu sản xuất cũ (trước khi có mô hình quản lý theo XƯỞNG) về
 * "Xưởng Quận 9".
 *
 * <p>Idempotent — chỉ đụng các bản ghi chưa gán xưởng (productionFactory == null):
 * <ul>
 *   <li>Tạo Xưởng Quận 9 nếu chưa tồn tại (theo tên).</li>
 *   <li>Gán tồn kho nguyên liệu xưởng ({@link FactoryMaterialStock}) chưa có xưởng → Q9.</li>
 *   <li>Gán lệnh sản xuất ({@link WorkOrder}) chưa có xưởng → Q9.</li>
 *   <li>Gán phiếu đặt hàng ({@link MaterialRequest}) chưa có xưởng → Q9.</li>
 *   <li>Nguyên liệu xưởng ({@link FactoryMaterial}) chưa gán xưởng nào → gán tất cả xưởng.</li>
 * </ul>
 *
 * <p>Có thể xoá class này an toàn sau khi toàn bộ dữ liệu cũ đã được gán xưởng.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(50)
public class ProductionFactoryBackfill implements ApplicationRunner {

    private static final String Q9_NAME = "Xưởng Quận 9";
    private static final String Q9_ADDRESS = "20 đường 904, Phường Hiệp Phú, TP. Thủ Đức";

    private final ProductionFactoryRepository factoryRepository;
    private final FactoryMaterialStockRepository stockRepository;
    private final FactoryMaterialRepository materialRepository;
    private final WorkOrderRepository workOrderRepository;
    private final MaterialRequestRepository materialRequestRepository;
    private final com.nhatnam.server.repository.ProductionPlanRepository productionPlanRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ProductionFactory q9 = factoryRepository.findAllByOrderByNameAsc().stream()
                .filter(f -> Q9_NAME.equalsIgnoreCase(f.getName()))
                .findFirst()
                .orElseGet(() -> factoryRepository.save(ProductionFactory.builder()
                        .name(Q9_NAME)
                        .address(Q9_ADDRESS)
                        .description("Xưởng mặc định cho dữ liệu sản xuất cũ.")
                        .status(ProductionFactory.FactoryStatus.ACTIVE)
                        .createdByName("Hệ thống")
                        .build()));

        int stocks = 0;
        List<FactoryMaterialStock> allStocks = stockRepository.findAll();
        for (FactoryMaterialStock s : allStocks) {
            if (s.getProductionFactory() == null) {
                s.setProductionFactory(q9);
                stocks++;
            }
        }
        if (stocks > 0) stockRepository.saveAll(allStocks);

        int orders = 0;
        List<WorkOrder> allOrders = workOrderRepository.findAll();
        for (WorkOrder w : allOrders) {
            if (w.getProductionFactory() == null) {
                w.setProductionFactory(q9);
                w.setProductionFactoryName(q9.getName());
                orders++;
            }
        }
        if (orders > 0) workOrderRepository.saveAll(allOrders);

        int reqs = 0;
        List<MaterialRequest> allReqs = materialRequestRepository.findAll();
        for (MaterialRequest r : allReqs) {
            if (r.getProductionFactory() == null) {
                r.setProductionFactory(q9);
                reqs++;
            }
        }
        if (reqs > 0) materialRequestRepository.saveAll(allReqs);

        int plans = 0;
        List<ProductionPlan> allPlans = productionPlanRepository.findAll();
        for (ProductionPlan p : allPlans) {
            if (p.getProductionFactory() == null) {
                p.setProductionFactory(q9);
                p.setProductionFactoryName(q9.getName());
                plans++;
            }
        }
        if (plans > 0) productionPlanRepository.saveAll(allPlans);

        int mats = 0;
        List<ProductionFactory> allFactories = factoryRepository.findAllByOrderByNameAsc();
        List<FactoryMaterial> allMaterials = materialRepository.findAll();
        for (FactoryMaterial m : allMaterials) {
            if (m.getFactories() == null || m.getFactories().isEmpty()) {
                m.setFactories(new java.util.ArrayList<>(allFactories));
                mats++;
            }
        }
        if (mats > 0) materialRepository.saveAll(allMaterials);

        if (stocks + orders + reqs + plans + mats > 0) {
            log.info("[ProductionFactoryBackfill] Q9(id={}): stocks={}, workOrders={}, requests={}, plans={}, materials={}",
                    q9.getId(), stocks, orders, reqs, plans, mats);
        }
    }
}