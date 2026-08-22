package com.nhatnam.server.service;

import com.nhatnam.server.entity.FactoryProduct;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.repository.FactoryProductRepository;
import com.nhatnam.server.repository.IngredientRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service DÙNG TẠM CHO MÔI TRƯỜNG TEST — xoá sạch toàn bộ dữ liệu giao dịch
 * của module "xưởng sản xuất" để Owner test lại từ đầu với dữ liệu sạch.
 *
 * GIỮ LẠI (không xoá):
 *  - factory_material  — danh mục Nguyên liệu của nhà máy (VD: Thịt nạc vai, Gia vị)
 *  - machine           — danh mục Máy móc (NHƯNG status sẽ được reset về ACTIVE,
 *    xem bên dưới — vì lịch bảo trì cũ đã xoá hết, máy không còn lý do "đang bảo trì")
 *
 * XOÁ SẠCH (toàn bộ phần còn lại):
 *  - Tồn kho: nguyên liệu (factory_material_stock), thành phẩm, bán thành
 *    phẩm, scrap
 *  - Kế hoạch sản xuất, lệnh sản xuất, mẻ sản xuất, biến thể sản xuất (Recipe)
 *  - Phiếu đặt hàng nguyên liệu (material_request)
 *  - Phiếu xuất/nhập kho, biên bản hao hụt đóng gói (semi_finished_transfer_*,
 *    packaging_loss_report)
 *  - Lịch bảo trì/sửa chữa máy móc (maintenance_schedule) và lịch hoạt động
 *    máy móc (machine_work_schedule) — CHỈ xoá lịch, KHÔNG xoá chính máy móc
 *  - Sản phẩm của xưởng (factory_product) — sẽ được tạo lại từ Ingredient
 *    hiện có qua seedFactoryProductsFromIngredients()
 *
 * ⚠️ KHÔNG dùng trong production có dữ liệu thật — hành động này KHÔNG THỂ
 * HOÀN TÁC. Endpoint gọi service này nên được xoá/disable sau khi dùng xong.
 *
 * Thứ tự xoá tuân theo đúng phụ thuộc khoá ngoại (FK) — xoá bảng con trước,
 * bảng cha sau, để không vi phạm ràng buộc FK của MySQL.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class ProductionResetService {

    @PersistenceContext
    private EntityManager em;

    private final IngredientRepository ingredientRepo;
    private final FactoryProductRepository factoryProductRepo;

    /**
     * Thứ tự bảng cần xoá (TRUNCATE), từ con sâu nhất → cha. Dùng TRUNCATE thay
     * DELETE để reset cả AUTO_INCREMENT về 1 — đúng tinh thần "data clean" của
     * môi trường test. Tạm tắt FOREIGN_KEY_CHECKS để tránh lỗi thứ tự, dù danh
     * sách đã được sắp đúng thứ tự theo dependency graph.
     */
    private static final List<String> TABLES_IN_ORDER = List.of(
            // ── Biên bản hao hụt + phiếu chuyển kho (phụ thuộc semi_finished_*) ──
            "packaging_loss_report",
            "semi_finished_transfer_source_batch",
            "semi_finished_transfer_note_line",
            "semi_finished_transfer_note",

            // ── Tồn kho kho xưởng (phụ thuộc production_batch) ──
            "finished_goods_transaction",
            "finished_goods_stock",
            "semi_finished_goods_stock",
            "scrap_stock",

            // ── Mẻ sản xuất + các bước/nguyên liệu của mẻ ──
            "work_order_step",
            "batch_step",
            "production_batch_item",
            "production_batch",

            // ── Lệnh sản xuất + kế hoạch nội bộ lệnh ──
            "work_order_stock_deduction",
            "work_order_operation",
            "work_order_plan_batch_material",
            "work_order_plan_material",
            "work_order_plan",
            "work_order",

            // ── Kế hoạch sản xuất (Owner tạo) ──
            "production_plan_product",
            "production_plan",
            "annual_mps",

            // ── Biến thể sản xuất (Recipe) ──
            "production_recipe_item",
            "production_recipe_step",
            "production_recipe",

            // ── Phiếu đặt hàng nguyên liệu (xuất/nhập kho NVL) ──
            "material_request_vendor",
            "material_request_item",
            "material_request",

            // ── Tồn kho nguyên liệu xưởng (phụ thuộc factory_material — GIỮ LẠI factory_material) ──
            "factory_material_stock",

            // ── Lịch bảo trì/sửa chữa + lịch hoạt động máy móc (phụ thuộc machine —
            //    GIỮ LẠI chính bảng machine, chỉ xoá lịch sử của nó) ──
            "maintenance_schedule",
            "machine_work_schedule",

            // ── Sản phẩm của xưởng (bảng cha cuối cùng còn xoá trong nhóm này).
            //    KHÔNG xoá factory_material và machine theo yêu cầu: chỉ giữ lại
            //    "Nguyên liệu của nhà máy" (factory_material) và "Máy móc" (machine),
            //    toàn bộ phần còn lại (tồn kho, kế hoạch, lệnh, mẻ, biến thể, phiếu
            //    đặt hàng, lịch bảo trì/hoạt động máy...) đều bị xoá sạch. ──
            "factory_product"
    );

    @Transactional
    public ResetResult resetAll() {
        log.warn("⚠️ [ProductionResetService] BẮT ĐẦU XOÁ TOÀN BỘ DỮ LIỆU MODULE SẢN XUẤT — KHÔNG THỂ HOÀN TÁC");
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
        int totalTablesCleared = 0;
        try {
            for (String table : TABLES_IN_ORDER) {
                em.createNativeQuery("TRUNCATE TABLE " + table).executeUpdate();
                totalTablesCleared++;
                log.info("  ✓ Đã truncate bảng: {}", table);
            }
        } finally {
            em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
        }

        // Đưa toàn bộ máy móc về trạng thái ACTIVE — vì lịch bảo trì/sửa chữa
        // (maintenance_schedule) đã bị xoá sạch ở trên, máy không còn lý do gì
        // để vẫn hiển thị "Đang bảo trì"/"Ngừng hoạt động" từ dữ liệu test cũ.
        // Theo đúng yêu cầu: reset xong, máy móc phải về trạng thái sẵn sàng dùng.
        int machinesReactivated = em.createNativeQuery(
                "UPDATE machine SET status = 'ACTIVE' WHERE status <> 'ACTIVE'").executeUpdate();
        log.info("  ✓ Đã đưa {} máy về trạng thái ACTIVE", machinesReactivated);

        log.warn("✅ [ProductionResetService] Hoàn tất — đã xoá sạch {} bảng, kích hoạt lại {} máy",
                totalTablesCleared, machinesReactivated);
        return new ResetResult(totalTablesCleared, TABLES_IN_ORDER, machinesReactivated);
    }

    /**
     * Tạo lại toàn bộ FactoryProduct từ TẤT CẢ Ingredient đang active hiện có —
     * mỗi Ingredient → 1 FactoryProduct liên kết đúng ingredientId, name/unit
     * snapshot từ Ingredient (nguồn sự thật). Gọi sau resetAll() để đảm bảo
     * bảng factory_product đang trống (tránh tạo trùng nếu gọi lại nhiều lần).
     */
    @Transactional
    public SeedResult seedFactoryProductsFromIngredients() {
        List<Ingredient> ingredients = ingredientRepo.findByIsActiveTrueOrderByNameAsc();
        long now = System.currentTimeMillis();
        int created = 0;
        for (Ingredient ing : ingredients) {
            // Bỏ qua nếu đã có FactoryProduct liên kết ingredient này (tránh trùng nếu gọi lại)
            if (!factoryProductRepo.findByIngredientId(ing.getId()).isEmpty()) continue;
            FactoryProduct fp = FactoryProduct.builder()
                    .name(ing.getName())
                    .unit(ing.getUnit())
                    .ingredientId(ing.getId())
                    .isActive(true)
                    .build();
            factoryProductRepo.save(fp);
            created++;
        }
        log.info("✅ [ProductionResetService] Đã tạo {} FactoryProduct từ {} Ingredient", created, ingredients.size());
        return new SeedResult(created, ingredients.size());
    }

    /** Tiện ích gộp 2 bước resetAll() + seedFactoryProductsFromIngredients() cho 1 lần gọi duy nhất */
    @Transactional
    public ResetAndSeedResult resetAndSeed() {
        ResetResult reset = resetAll();
        SeedResult seed = seedFactoryProductsFromIngredients();
        return new ResetAndSeedResult(reset, seed);
    }

    public record ResetResult(int tablesCleared, List<String> tableNames, int machinesReactivated) {}
    public record SeedResult(int factoryProductsCreated, int totalIngredients) {}
    public record ResetAndSeedResult(ResetResult reset, SeedResult seed) {}
}