package com.nhatnam.server.restcontroller.driver;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.Driver;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.DriverPortalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * QUẢN LÝ TÀI XẾ (OWNER / ADMIN / kho) — bao gồm việc GẮN 1 TÀI KHOẢN với
 * 1 bản ghi tài xế (quan hệ 1–1).
 *
 * <pre>
 *  GET   /api/admin/drivers                  → danh sách tài xế + tài khoản đã gắn
 *  GET   /api/admin/drivers/available-users  → các user role DRIVER chưa gắn tài xế
 *  PATCH /api/admin/drivers/{id}/link        → gắn / bỏ gắn tài khoản  body: {"userId": 5}
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/drivers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPER_WAREHOUSE','WAREHOUSE')")
public class DriverAdminController {

    private final DriverRepository driverRepo;
    private final UserRepository userRepo;
    private final DriverPortalService driverPortalService;
    private final com.nhatnam.server.service.DriverUserSyncService driverUserSyncService;

    /**
     * BACKFILL — tạo tài khoản cho các tài xế cũ chưa có {@code _user} rồi ghép
     * hai bảng lại. Chạy MỘT LẦN sau khi deploy; gọi lại cũng an toàn vì hàm
     * bỏ qua những tài xế đã được ghép.
     *
     * <pre>POST /api/admin/drivers/backfill-accounts</pre>
     */
    @PostMapping("/backfill-accounts")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfillAccounts() {
        var r = driverUserSyncService.backfillDriverAccounts();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("created", r.created());
        body.put("linked", r.linked());
        body.put("skipped", r.skipped());
        body.put("details", r.details());
        return ResponseEntity.ok(ApiResponse.success(body,
                "Đã tạo " + r.created() + " tài khoản, ghép " + r.linked() + " tài xế"));
    }

    /**
     * Bật/tắt cờ TÀI XẾ HỆ THỐNG — tài xế "ảo" như <i>Giao tại kho</i> không
     * cần tài khoản và sẽ bị bỏ qua khi backfill / đồng bộ.
     *
     * <pre>PATCH /api/admin/drivers/{id}/system  body: {"systemDriver": true}</pre>
     */
    @PatchMapping("/{id}/system")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setSystemDriver(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Driver d = driverRepo.findById(id).orElse(null);
        if (d == null) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, "Không tìm thấy tài xế"));
        }
        d.setSystemDriver(Boolean.TRUE.equals(body.get("systemDriver")));
        driverRepo.save(d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("systemDriver", d.isSystemDriver());
        return ResponseEntity.ok(ApiResponse.success(m, "Đã cập nhật"));
    }

    /**
     * TẠO tài xế mới từ màn quản trị — cho phép set luôn {@code systemDriver}.
     *
     * <pre>POST /api/admin/drivers
     * body: {"name":"Grab", "vehicleType":"MOTORBIKE", "systemDriver":true}</pre>
     *
     * <p>Khác với {@code POST /api/warehouse/drivers} (tạo nhanh ngay lúc gán tài xế
     * cho đơn, luôn ra tài xế thật), endpoint này dùng khi khai báo có chủ đích nên
     * bật/tắt được cờ "không xử lý" ngay từ đầu.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody Map<String, Object> body) {
        try {
            String name = String.valueOf(body.getOrDefault("name", "")).trim();
            boolean systemDriver = Boolean.TRUE.equals(body.get("systemDriver"));

            // Tạo tài xế + tài khoản đăng nhập (trừ tài xế hệ thống). Chặn trùng tên.
            Driver d = driverUserSyncService.createDriverWithAccount(
                    name, parseVehicleType(body.get("vehicleType"), Driver.VehicleType.BOTH),
                    systemDriver);

            Map<String, Object> m = toMap(d);
            if (d.getUser() != null) {
                m.put("userId", d.getUser().getId());
                m.put("username", d.getUser().getUsername());
                m.put("email", d.getUser().getEmail());
            }
            String msg = d.getUser() != null
                    ? "Đã tạo tài xế và tài khoản @" + d.getUser().getUsername()
                    : "Đã tạo tài xế";
            return ResponseEntity.ok(ApiResponse.success(m, msg));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[DriverAdmin] Lỗi tạo tài xế", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * SỬA tài xế — tên, loại xe, còn hoạt động, và cờ "không xử lý".
     * Trường nào không gửi lên thì giữ nguyên.
     *
     * <pre>PATCH /api/admin/drivers/{id}
     * body: {"name":"...", "vehicleType":"TRUCK", "active":true, "systemDriver":false}</pre>
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Driver d = driverRepo.findById(id).orElse(null);
        if (d == null)
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, "Không tìm thấy tài xế"));

        if (body.containsKey("name")) {
            String name = String.valueOf(body.get("name")).trim();
            if (name.isBlank())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Tên tài xế không được trống"));
            boolean duplicated = driverRepo.findAll().stream()
                    .anyMatch(x -> !x.getId().equals(id)
                            && x.getName() != null && x.getName().trim().equalsIgnoreCase(name));
            if (duplicated)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Đã có tài xế tên \"" + name + "\""));
            d.setName(name);
        }
        if (body.containsKey("vehicleType"))
            d.setVehicleType(parseVehicleType(body.get("vehicleType"), d.getVehicleType()));
        if (body.containsKey("active"))
            d.setActive(Boolean.TRUE.equals(body.get("active")));
        if (body.containsKey("systemDriver"))
            d.setSystemDriver(Boolean.TRUE.equals(body.get("systemDriver")));

        return ResponseEntity.ok(ApiResponse.success(toMap(driverRepo.save(d)), "Đã cập nhật"));
    }

    private Driver.VehicleType parseVehicleType(Object raw, Driver.VehicleType fallback) {
        if (raw == null) return fallback;
        try {
            return Driver.VehicleType.valueOf(String.valueOf(raw).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private Map<String, Object> toMap(Driver d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("active", d.isActive());
        m.put("vehicleType", d.getVehicleType() != null ? d.getVehicleType().name() : null);
        m.put("systemDriver", d.isSystemDriver());
        return m;
    }

    /** Danh sách tài xế kèm thông tin tài khoản đã gắn (ẩn tài xế đã xoá/gộp). */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        try {
            List<Map<String, Object>> result = driverRepo.findAll().stream()
                    // Ẩn tài xế đã xoá mềm / đã gộp (tên có tiền tố "[Đã xóa] ").
                    .filter(d -> d.getName() == null
                            || !d.getName().startsWith(com.nhatnam.server.service.DriverUserSyncService.DELETED_PREFIX))
                    .map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("name", d.getName());
                m.put("active", d.isActive());
                m.put("vehicleType", d.getVehicleType() != null ? d.getVehicleType().name() : "BOTH");
                // true = "không xử lý": ẩn khỏi màn điểm danh ODO và báo cáo ODO
                m.put("systemDriver", d.isSystemDriver());
                if (d.getUser() != null) {
                    m.put("userId", d.getUser().getId());
                    m.put("username", d.getUser().getUsername());
                    m.put("userFullName", d.getUser().getFullName());
                } else {
                    m.put("userId", null);
                }
                return m;
            }).toList();
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Các tài khoản có thể GẮN với tài xế — mọi tài khoản đang hoạt động
     * (chưa khoá, chưa xoá) và CHƯA gắn với tài xế nào. Không giới hạn role DRIVER
     * nữa: có thể gắn tài xế vào một tài khoản sẵn có, khi gắn hệ thống sẽ tự thêm
     * role Tài xế cho tài khoản đó.
     */
    @GetMapping("/available-users")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> availableUsers() {
        try {
            Set<Long> linked = new HashSet<>();
            driverRepo.findAll().forEach(d -> {
                if (d.getUser() != null) linked.add(d.getUser().getId());
            });

            List<Map<String, Object>> result = userRepo.findAll().stream()
                    .filter(u -> !u.isLockAccount() && !u.isDeleted())
                    .filter(u -> !linked.contains(u.getId()))
                    .sorted(Comparator.comparing(
                            u -> u.getFullName() != null ? u.getFullName() : u.getUsername(),
                            String.CASE_INSENSITIVE_ORDER))
                    .map(u -> {
                        boolean isDriver = u.getAllRoles() != null && u.getAllRoles().contains(Role.DRIVER);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", u.getId());
                        m.put("username", u.getUsername());
                        m.put("fullName", u.getFullName());
                        m.put("phoneNumber", u.getPhoneNumber());
                        m.put("isDriver", isDriver);   // FE có thể ưu tiên/nhãn tài khoản đã là tài xế
                        return m;
                    }).toList();

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Gắn / bỏ gắn tài khoản với tài xế. {@code userId = null} để bỏ gắn. */
    @PatchMapping("/{id}/link")
    public ResponseEntity<ApiResponse<Map<String, Object>>> link(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            Long userId = null;
            Object raw = body.get("userId");
            if (raw instanceof Number n) userId = n.longValue();

            Driver d = driverPortalService.linkUser(id, userId);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("name", d.getName());
            m.put("userId", d.getUser() != null ? d.getUser().getId() : null);
            m.put("userFullName", d.getUser() != null ? d.getUser().getFullName() : null);
            return ResponseEntity.ok(ApiResponse.success(m,
                    userId == null ? "Đã bỏ liên kết tài khoản" : "Đã gắn tài khoản với tài xế"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[DriverAdmin] Lỗi gắn tài khoản cho tài xế {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}