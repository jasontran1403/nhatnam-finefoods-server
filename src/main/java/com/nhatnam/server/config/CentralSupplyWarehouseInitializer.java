package com.nhatnam.server.config;

import com.nhatnam.server.entity.SupplyWarehouse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.UserSupplyWarehouse;
import com.nhatnam.server.repository.SupplyWarehouseRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.repository.UserSupplyWarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * KHO VĂN PHÒNG PHẨM DUY NHẤT — "Kho Trung tâm".
 *
 * <p>Sau khi refactor UX, module VPP không còn chọn kho ở FE nữa. Backend vẫn giữ
 * cột {@code warehouse_id} trên {@code OfficeSupplyRequest} / {@code OfficeSupplyOrder}
 * để không phải migrate schema, nhưng CHỈ dùng đúng 1 kho — "Kho Trung tâm" —
 * cho toàn bộ luồng đăng ký & đặt hàng VPP.
 *
 * <p>Class này chạy sau {@link SupplyModuleInitializer} (Order 55):
 * <ol>
 *   <li>Vô hiệu hoá 2 kho cũ "Phổ Quang" / "Quận 9" (nếu có) — set {@code active=false}
 *       để chúng biến mất khỏi mọi dropdown.</li>
 *   <li>Tạo kho "Kho Trung tâm" nếu chưa có (idempotent theo tên).</li>
 *   <li>Gán mọi user CHƯA bị xoá vào kho này — dùng cho cả tài khoản cũ lẫn tài khoản
 *       tạo mới sau này (tài khoản mới còn được gán thêm ở
 *       {@code UserAdminService.create}).</li>
 * </ol>
 *
 * <p>KHÔNG xoá bản ghi kho cũ: order/request lịch sử vẫn tham chiếu vào chúng qua khoá
 * ngoại, xoá cứng sẽ vỡ dữ liệu. Deactivate là đủ để giấu khỏi UI.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(56) // sau SupplyModuleInitializer (55) để dựa trên kho cũ đã seed
public class CentralSupplyWarehouseInitializer implements ApplicationRunner {

    /** Tên chuẩn của kho VPP duy nhất — dùng chung ở service và seeder. */
    public static final String CENTRAL_NAME = "Kho Trung tâm";

    private final SupplyWarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final UserSupplyWarehouseRepository assignmentRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 1) Deactivate các kho cũ (nếu tồn tại). Không xoá vì đơn hàng lịch sử còn trỏ vào.
        deactivateIfPresent("Phổ Quang");
        deactivateIfPresent("Quận 9");

        // 2) Tạo (hoặc lấy) kho Trung tâm
        SupplyWarehouse central = warehouseRepository.findByName(CENTRAL_NAME)
                .orElseGet(() -> {
                    SupplyWarehouse w = warehouseRepository.save(SupplyWarehouse.builder()
                            .name(CENTRAL_NAME)
                            .active(true)
                            .sortOrder(0)
                            .build());
                    log.info("[SupplyModule] Đã tạo \"{}\" (id={})", CENTRAL_NAME, w.getId());
                    return w;
                });
        // Đảm bảo kho luôn active kể cả khi ai đó lỡ tay tắt
        if (!central.isActive()) {
            central.setActive(true);
            warehouseRepository.save(central);
        }

        // 3) Gán TẤT CẢ user chưa xoá vào kho Trung tâm (bỏ qua nếu đã có).
        //    Không lọc lockAccount ở đây — user bị khoá vẫn được giữ mapping để khi
        //    mở khoá lại là dùng được ngay, không cần thao tác thủ công.
        int assigned = 0;
        for (User u : userRepository.findAll()) {
            if (u.isDeleted()) continue;
            if (assignmentRepository.existsByUserIdAndWarehouseId(u.getId(), central.getId())) continue;

            assignmentRepository.save(UserSupplyWarehouse.builder()
                    .userId(u.getId())
                    .warehouseId(central.getId())
                    .assignedByName("SYSTEM")
                    .build());
            assigned++;
        }
        if (assigned > 0) {
            log.info("[SupplyModule] Đã gán {} user vào \"{}\"", assigned, CENTRAL_NAME);
        }
    }

    private void deactivateIfPresent(String name) {
        warehouseRepository.findByName(name).ifPresent(w -> {
            if (w.isActive()) {
                w.setActive(false);
                warehouseRepository.save(w);
                log.info("[SupplyModule] Đã ẩn kho cũ \"{}\" (id={})", name, w.getId());
            }
        });
    }
}
