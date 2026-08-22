package com.nhatnam.server.config;

import com.nhatnam.server.entity.SupplyWarehouse;
import com.nhatnam.server.repository.MaterialRequestRepository;
import com.nhatnam.server.repository.SupplyWarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Khởi tạo module "Phiếu đặt hàng Văn phòng phẩm / Đồ dùng".
 *
 * <p><b>Idempotent</b> — chạy lại bao nhiêu lần cũng an toàn:
 * <ol>
 *   <li>Seed 2 kho VPP: <b>Phổ Quang</b> và <b>Quận 9</b> (tìm theo tên,
 *       chỉ tạo khi chưa có).</li>
 *   <li>Backfill {@code material_request.order_type = 'MATERIAL'} và
 *       {@code vendor_expense_category.category_kind = 'SERVICE'} cho dữ liệu cũ.</li>
 * </ol>
 *
 * <h3>Quan hệ với {@code ddl-auto: update}</h3>
 * Hibernate tự thêm cột và tạo bảng mới, nhưng KHÔNG điền dữ liệu cho các dòng đã
 * có. Hai cột enum mới vì thế sẽ NULL trên toàn bộ dữ liệu cũ. Class này dọn chúng
 * về giá trị đúng.
 *
 * <p><b>Nhưng hệ thống không phụ thuộc vào nó.</b> Cả 2 cột đều được khai báo
 * nullable và mọi nhánh code đều coi NULL là giá trị mặc định hợp lệ
 * ({@code MATERIAL} / {@code SERVICE}). Nếu vì lý do nào đó backfill không chạy
 * được, ứng dụng vẫn hoạt động đúng — backfill chỉ để dữ liệu sạch sẽ. Đây là lựa
 * chọn có chủ đích: một tính năng mới không được phép làm biến mất dữ liệu cũ chỉ
 * vì một bước khởi động thất bại.
 *
 * <p>Có thể xoá class này sau khi 2 kho đã tồn tại và dữ liệu cũ đã được backfill.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(55)
public class SupplyModuleInitializer implements ApplicationRunner {

    private final SupplyWarehouseRepository warehouseRepository;
    private final MaterialRequestRepository materialRequestRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedWarehouse("Phổ Quang", 1);
        seedWarehouse("Quận 9", 2);

        backfill("material_request.order_type", materialRequestRepository::backfillOrderType);
        backfill("vendor_expense_category.category_kind", materialRequestRepository::backfillCategoryKind);
    }

    private void backfill(String column, java.util.function.IntSupplier op) {
        try {
            int updated = op.getAsInt();
            if (updated > 0) {
                log.info("[SupplyModule] Backfill {}: đã cập nhật {} dòng", column, updated);
            }
        } catch (Exception e) {
            // Lần chạy đầu tiên cột có thể chưa tồn tại (tuỳ thứ tự của Hibernate).
            // Không được để việc này chặn khởi động ứng dụng — xem javadoc ở trên.
            log.warn("[SupplyModule] Bỏ qua backfill {}: {}", column, e.getMessage());
        }
    }

    private void seedWarehouse(String name, int sortOrder) {
        warehouseRepository.findByName(name).orElseGet(() -> {
            SupplyWarehouse w = warehouseRepository.save(SupplyWarehouse.builder()
                    .name(name)
                    .active(true)
                    .sortOrder(sortOrder)
                    .build());
            log.info("[SupplyModule] Đã tạo kho VPP \"{}\" (id={})", name, w.getId());
            return w;
        });
    }
}
