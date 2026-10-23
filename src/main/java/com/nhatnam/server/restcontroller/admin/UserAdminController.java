package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.user.CreateUserRequest;
import com.nhatnam.server.dto.user.UpdateUserRequest;
import com.nhatnam.server.dto.user.UserDto;
import com.nhatnam.server.entity.DriverAttendance;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.DriverAttendanceRepository;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.admin.UserAdminService;
import com.nhatnam.server.service.hr.SalaryReportPdfService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Log4j2
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userService;
    private final DriverAttendanceRepository driverAttendanceRepository;
    private final OrderRepository orderRepository;
    private final DriverRepository driverRepository;
    // (10/2026) Báo cáo tài xế đổi sang PDF — service riêng.
    private final SalaryReportPdfService pdfService;

    /**
     * Báo cáo tài xế → PDF (10/2026 rewrite).
     *
     * <h3>Thay đổi so với bản Excel cũ</h3>
     * <ul>
     *   <li>Output PDF A4 ngang (iText) thay vì xlsx</li>
     *   <li>Meta chỉ hiển thị "Xuất lúc / Người xuất" — bỏ giá xăng + thưởng</li>
     *   <li>HARDCODE skip tài xế tên "Q9"</li>
     *   <li>Tài xế đã ngưng: nền đỏ nhạt + chữ đỏ in nghiêng + prefix "[Đã nghỉ việc]"</li>
     *   <li>Fix bug: tài xế đã ngưng vẫn hiển thị nếu có data trong khoảng
     *       (kể cả systemDriver=true như trước), vì data lịch sử phải thanh toán</li>
     * </ul>
     */
    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER','HR','SUPER_ACCOUNTANT')")
    public ResponseEntity<byte[]> exportDriverReport(
            @RequestParam long from,
            @RequestParam long to,
            @RequestParam(defaultValue = "true") boolean excludeWarehouse,
            @RequestParam(defaultValue = "0") double gasRatePerKm,
            @RequestParam(defaultValue = "0") double bikeOrderBonus,
            @RequestParam(defaultValue = "0") double truckOrderBonus,
            Authentication auth) {
        try {
            final double gasRate   = gasRatePerKm   > 0 ? gasRatePerKm   : 488d;
            final double bikeBonus = bikeOrderBonus > 0 ? bikeOrderBonus : 5000d;
            final double truckBonus= truckOrderBonus > 0 ? truckOrderBonus : 8000d;

            java.time.ZoneId tz = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
            java.time.LocalDate fromDate = java.time.Instant.ofEpochMilli(from).atZone(tz).toLocalDate();
            java.time.LocalDate toDate   = java.time.Instant.ofEpochMilli(to).atZone(tz).toLocalDate();
            java.time.format.DateTimeFormatter dmy =
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

            // ── 1) Load attendance → km / số lượt mỗi tài xế ──
            java.util.function.Predicate<com.nhatnam.server.entity.Driver> isWarehouseDriver =
                    d -> d.getName() != null && d.getName().toLowerCase().contains("kho");

            List<DriverAttendance> attList = driverAttendanceRepository
                    .findByDateRange(fromDate.toString(), toDate.toString());

            // key: driverId|VT|date → [startOdo, endOdo]
            Map<String, Integer[]> dayOdo = new java.util.LinkedHashMap<>();
            for (var a : attList) {
                if (excludeWarehouse && isWarehouseDriver.test(a.getDriver())) continue;
                String key = a.getDriver().getId() + "|" + a.getVehicleType().name()
                        + "|" + a.getAttendanceDate();
                Integer[] pair = dayOdo.computeIfAbsent(key, k -> new Integer[]{null, null});
                if (a.getSessionType() == com.nhatnam.server.entity.DriverAttendance.SessionType.START)
                    pair[0] = a.getOdometer();
                else pair[1] = a.getOdometer();
            }

            // stats: driverId → [kmBike, bikeOrders(count ODO), kmTruck, truckOrders(count ODO)]
            Map<Long, int[]> stats = new java.util.LinkedHashMap<>();
            Map<Long, String>  driverNames  = new java.util.LinkedHashMap<>();  // tài xế THẬT (role DRIVER/SHIPPER)
            Map<Long, Boolean> driverActive = new java.util.HashMap<>();
            Map<Long, String>  helperNames  = new java.util.LinkedHashMap<>();  // tài xế ngoài role (hỗ trợ)

            final String DELETED_PREFIX = com.nhatnam.server.service.DriverUserSyncService.DELETED_PREFIX;
            java.util.function.Function<String, String> stripName = s -> {
                if (s == null) return "";
                String t = s.trim();
                if (t.startsWith(DELETED_PREFIX)) t = t.substring(DELETED_PREFIX.length()).trim();
                return t;
            };
            java.util.function.Function<String, String> lcName = s -> stripName.apply(s).toLowerCase();

            java.util.function.Predicate<com.nhatnam.server.entity.Driver> isActualDriver = d -> {
                var u = d.getUser();
                if (u == null) return true;
                java.util.Set<Role> all = new java.util.HashSet<>();
                if (u.getRole() != null)        all.add(u.getRole());
                if (u.getPayrollRole() != null) all.add(u.getPayrollRole());
                if (u.getRoles() != null)       all.addAll(u.getRoles());
                return all.contains(Role.DRIVER) || all.contains(Role.SHIPPER);
            };

            // (10/2026) FIX BUG: dùng findAll() để KHÔNG BỎ SÓT tài xế đã bị
            // đánh "systemDriver=true" nhưng vẫn có data lịch sử trong kỳ.
            // Logic cũ dùng findBySystemDriverFalse → mất Đinh Đặng Thành
            // (đã nghỉ → bị gán systemDriver khi quản lý tắt). Giờ bao gộp cả.
            for (var d : driverRepository.findAll()) {
                if (d.getName() == null || d.getName().isBlank()) continue;
                if (excludeWarehouse && isWarehouseDriver.test(d)) continue;
                // (10/2026) HARDCODE skip tên "Q9"
                if ("Q9".equalsIgnoreCase(stripName.apply(d.getName()))) continue;
                // Grab và các "virtual" khác: nếu systemDriver nhưng tên có chứa "grab" → bỏ
                if (d.isSystemDriver() && stripName.apply(d.getName()).toLowerCase().contains("grab")) continue;

                driverActive.put(d.getId(), d.isActive());
                stats.putIfAbsent(d.getId(), new int[]{0, 0, 0, 0});
                if (isActualDriver.test(d)) driverNames.put(d.getId(), d.getName());
                else helperNames.put(d.getId(), d.getName());
            }

            // Fill km / số lượt từ dayOdo
            dayOdo.forEach((k, p) -> {
                if (p[0] == null || p[1] == null) return;
                String[] parts = k.split("\\|");
                long did = Long.parseLong(parts[0]);
                int km = Math.max(0, p[1] - p[0]);
                int[] s = stats.get(did);
                if (s == null) return;
                if ("MOTORBIKE".equals(parts[1])) { s[0] += km; s[1]++; }
                else if ("TRUCK".equals(parts[1])) { s[2] += km; s[3]++; }
            });

            // ── 2) Đếm số đơn theo tên trong deliveryInfoJson ──
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Map<String, Integer>> ordersByNameAndVt = new java.util.LinkedHashMap<>();
            Map<String, String> displayNameByLc = new java.util.LinkedHashMap<>();
            orderRepository.findAll().stream()
                    .filter(o -> (o.getStatus() == com.nhatnam.server.enumtype.OrderStatus.PENDING_PAYMENT
                            || o.getStatus() == com.nhatnam.server.enumtype.OrderStatus.COMPLETED)
                            && o.getCreatedAt() >= from && o.getCreatedAt() <= to
                            && o.getDeliveryInfoJson() != null && !o.getDeliveryInfoJson().isBlank())
                    .forEach(o -> {
                        try {
                            List<Map<String, Object>> info = om.readValue(o.getDeliveryInfoJson(),
                                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
                            for (var d : info) {
                                String n = String.valueOf(d.getOrDefault("name", "")).trim();
                                if (n.isBlank()) continue;
                                if ("Q9".equalsIgnoreCase(n)) continue;  // hardcode skip
                                String lc = n.toLowerCase();
                                displayNameByLc.putIfAbsent(lc, n);
                                String vt = String.valueOf(d.getOrDefault("type", "")).trim().toUpperCase();
                                if ("MOTORBIKE".equals(vt) || "TRUCK".equals(vt)) {
                                    ordersByNameAndVt
                                            .computeIfAbsent(lc, k -> new java.util.LinkedHashMap<>())
                                            .merge(vt, 1, Integer::sum);
                                }
                            }
                        } catch (Exception ignored) {}
                    });

            // ── 3) Build rows ──
            var report = new com.nhatnam.server.service.hr.SalaryReportPdfService.DriverAttReport();
            report.fromLabel = fromDate.format(dmy);
            report.toLabel   = toDate.format(dmy);
            report.exportedAt = java.time.LocalDateTime.now(tz)
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));
            report.exportedBy = auth != null
                    ? ((com.nhatnam.server.entity.User) auth.getPrincipal()).getFullName() : "";
            report.gasRate = gasRate;
            report.bikeBonus = bikeBonus;
            report.truckBonus = truckBonus;

            java.util.Set<String> alreadyShown = new java.util.HashSet<>();

            // Group 1: tài xế thật
            for (var e : driverNames.entrySet()) {
                int[] s = stats.getOrDefault(e.getKey(), new int[]{0,0,0,0});
                String lc = lcName.apply(e.getValue());
                var vt = ordersByNameAndVt.getOrDefault(lc, Map.of());
                int kmBike = s[0], bikeO = vt.getOrDefault("MOTORBIKE", 0),
                        truckO = vt.getOrDefault("TRUCK", 0);
                boolean active = driverActive.getOrDefault(e.getKey(), true);
                boolean hasData = kmBike > 0 || bikeO > 0 || truckO > 0;
                // Đã ngưng + không data → bỏ (nhưng CÓ data vẫn ra)
                if (!active && !hasData) continue;

                var row = new com.nhatnam.server.service.hr.SalaryReportPdfService.DriverAttRow();
                row.name = stripName.apply(e.getValue());
                row.kmBike = kmBike;
                row.bikeOrders = bikeO;
                row.truckOrders = truckO;
                row.active = active;
                row.isHelper = false;
                report.rows.add(row);
                alreadyShown.add(lc);
            }

            // Group 2: hỗ trợ giao hàng — từ helperNames (DB) + từ deliveryInfo (ngoài DB)
            for (var e : helperNames.entrySet()) {
                String lc = lcName.apply(e.getValue());
                if (alreadyShown.contains(lc)) continue;
                var vt = ordersByNameAndVt.getOrDefault(lc, Map.of());
                int[] s = stats.getOrDefault(e.getKey(), new int[]{0,0,0,0});
                int kmBike = s[0], bikeO = vt.getOrDefault("MOTORBIKE", 0),
                        truckO = vt.getOrDefault("TRUCK", 0);
                boolean active = driverActive.getOrDefault(e.getKey(), true);
                if (!active && kmBike == 0 && bikeO == 0 && truckO == 0) continue;

                var row = new com.nhatnam.server.service.hr.SalaryReportPdfService.DriverAttRow();
                row.name = stripName.apply(e.getValue());
                row.kmBike = kmBike; row.bikeOrders = bikeO; row.truckOrders = truckO;
                row.active = active; row.isHelper = true;
                report.rows.add(row);
                alreadyShown.add(lc);
            }
            for (var e : displayNameByLc.entrySet()) {
                String lc = e.getKey();
                if (alreadyShown.contains(lc)) continue;
                var vt = e.getValue() == null ? Map.<String,Integer>of()
                        : ordersByNameAndVt.getOrDefault(lc, Map.of());
                int bikeO = vt.getOrDefault("MOTORBIKE", 0), truckO = vt.getOrDefault("TRUCK", 0);
                if (bikeO == 0 && truckO == 0) continue;

                var row = new com.nhatnam.server.service.hr.SalaryReportPdfService.DriverAttRow();
                row.name = e.getValue();
                row.kmBike = 0; row.bikeOrders = bikeO; row.truckOrders = truckO;
                row.active = true; row.isHelper = true;
                report.rows.add(row);
            }

            byte[] pdf = pdfService.driverAttendanceReportPdf(report);
            String fn = "bao-cao-tai-xe-"
                    + fromDate.format(dmy).replace("/", "-") + "-"
                    + toDate.format(dmy).replace("/", "-") + ".pdf";
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + fn + "\"")
                    .header("Content-Type", "application/pdf")
                    .body(pdf);
        } catch (Exception e) {
            log.error("exportDriverReport error", e);
            return ResponseEntity.status(500).body(null);
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<UserDto>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean locked,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "false") boolean birthdaySort,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(userService.list(q, role, locked, includeDeleted, birthdaySort, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(userService.getById(id));
    }

    @PostMapping
    public ApiResponse<UserDto> create(@Valid @RequestBody CreateUserRequest req) {
        return ApiResponse.ok("Tạo user thành công", userService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserDto> update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateUserRequest req) {
        return ApiResponse.ok("Cập nhật user thành công", userService.update(id, req));
    }

    /** Khóa / mở khóa user — khi khóa, user không thao tác được gì */
    @PutMapping("/{id}/lock")
    public ApiResponse<UserDto> setLocked(@PathVariable Long id,
                                          @RequestParam boolean value) {
        String msg = value ? "Khóa user thành công" : "Mở khóa user thành công";
        return ApiResponse.ok(msg, userService.setLocked(id, value));
    }

    /**
     * XOÁ MỀM nhân viên.
     *
     * <p>Không xoá cứng (user còn được tham chiếu ở đơn hàng, phiếu kho, log…).
     * Thay vào đó: {@code deleted = true}, khoá tài khoản, thu hồi token, và gắn tiền tố
     * {@code SOFT_DELETED_{id}_} vào username/email/SĐT → GIẢI PHÓNG các giá trị unique
     * để có thể tạo lại nhân viên mới với đúng thông tin cũ.
     */
    @PreAuthorize("hasAnyRole('ADMIN','OWNER','SUPERADMIN','HR')")
    @DeleteMapping("/{id}")
    public ApiResponse<UserDto> softDelete(@PathVariable Long id, Authentication auth) {
        String actor = auth != null ? auth.getName() : "system";
        return ApiResponse.ok("Đã xoá nhân viên", userService.softDelete(id, actor));
    }

    /** Khôi phục nhân viên đã xoá mềm (nếu username/email/SĐT cũ chưa bị ai dùng mất) */
    @PreAuthorize("hasAnyRole('ADMIN','OWNER','SUPERADMIN','HR')")
    @PutMapping("/{id}/restore")
    public ApiResponse<UserDto> restore(@PathVariable Long id) {
        return ApiResponse.ok("Đã khôi phục nhân viên", userService.restore(id));
    }

    /** Danh sách nhân viên ĐÃ XOÁ */
    @GetMapping("/deleted")
    public ApiResponse<PageResponse<UserDto>> listDeleted(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(userService.listDeleted(
                org.springframework.data.domain.PageRequest.of(page, size)));
    }

    @PutMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @RequestBody Map<String, String> body) {
        userService.resetPassword(id, body.get("newPassword"));
        return ApiResponse.ok("Đổi mật khẩu thành công", null);
    }
}