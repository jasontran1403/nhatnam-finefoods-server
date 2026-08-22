package com.nhatnam.server.service;

import com.nhatnam.server.entity.Driver;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ĐỒNG BỘ 2 CHIỀU GIỮA TÀI KHOẢN ({@code _user}) VÀ TÀI XẾ ({@code driver}).
 *
 * <h3>Quy tắc</h3>
 * <pre>
 *   Tạo user có role DRIVER          → tạo bản ghi driver cùng họ tên, gắn user_id
 *   Thêm role DRIVER cho user cũ     → tạo mới, hoặc MỞ LẠI driver đã xoá mềm
 *   Bỏ role DRIVER khỏi user         → xoá mềm driver
 *   Xoá mềm user                     → xoá mềm driver
 *   Khôi phục user (có role DRIVER)  → mở lại driver
 *   Đổi họ tên user                  → đổi luôn tên driver
 * </pre>
 *
 * <h3>Xoá mềm là gì</h3>
 * KHÔNG xoá dòng khỏi bảng (đơn hàng cũ vẫn tham chiếu tới tài xế), mà:
 * <ul>
 *   <li>Lưu tên gốc vào {@code original_name}</li>
 *   <li>Thêm tiền tố {@value #DELETED_PREFIX} vào {@code name}</li>
 *   <li>Đặt {@code active = false} → biến mất khỏi mọi dropdown chọn tài xế</li>
 * </ul>
 * Nhờ đổi tên nên vẫn tạo lại được tài xế TRÙNG TÊN, và khi set lại role
 * {@code DRIVER} thì bản ghi cũ được mở lại nguyên vẹn cùng lịch sử giao hàng.
 *
 * <h3>Tài xế KHÔNG cần tài khoản</h3>
 * Hoàn toàn được. Các lựa chọn "ảo" như <i>Giao tại kho</i> chỉ cần bật cờ
 * {@code systemDriver = true} là hệ thống bỏ qua, không đòi tài khoản và không
 * bị đồng bộ theo user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverUserSyncService {

    /** Tiền tố đánh dấu tài xế đã xoá mềm. */
    public static final String DELETED_PREFIX = "[Đã xóa] ";

    /** Mật khẩu mặc định khi backfill tạo tài khoản cho tài xế cũ. */
    public static final String DEFAULT_PASSWORD = "Driver@123";

    private final DriverRepository driverRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // ══════════════════════════════════════════════════════════════════════════
    // ĐỒNG BỘ THEO USER — gọi sau mỗi lần tạo / sửa / xoá / khôi phục tài khoản
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Đưa bản ghi driver về đúng trạng thái của {@code user}.
     *
     * <p>Hàm này AN TOÀN KHI GỌI NHIỀU LẦN (idempotent) — gọi lại cũng không
     * tạo trùng, nên cứ gọi ở cuối mọi luồng đụng tới tài khoản.
     */
    @Transactional
    public void syncFromUser(User user) {
        // LƯU Ý: User.id là kiểu long NGUYÊN THUỶ (không phải Long) nên không so
        // sánh được với null. User chưa persist thì id = 0.
        if (user == null || user.getId() <= 0) return;

        boolean shouldBeDriver = !user.isDeleted() && hasDriverRole(user);
        Driver linked = driverRepository.findByUser_Id(user.getId()).orElse(null);

        if (shouldBeDriver) {
            if (linked == null) linked = adoptOrCreate(user);
            reactivate(linked, user);
        } else if (linked != null) {
            softDelete(linked);
        }
    }

    /** Tiện dụng: đồng bộ theo id tài khoản. */
    @Transactional
    public void syncFromUserId(Long userId) {
        if (userId == null) return;
        userRepository.findById(userId).ifPresent(this::syncFromUser);
    }

    private boolean hasDriverRole(User u) {
        Set<Role> roles = u.getAllRoles();
        return roles != null && roles.contains(Role.DRIVER);
    }

    /**
     * Tìm tài xế CÙNG TÊN chưa gắn tài khoản để ghép vào (giữ lại lịch sử giao
     * hàng cũ), nếu không có thì tạo mới.
     */
    private Driver adoptOrCreate(User user) {
        String fullName = safeName(user);

        // 1. Ghép với tài xế đang hoạt động, cùng tên, chưa có tài khoản
        Optional<Driver> orphan = driverRepository.findByUserIsNullAndNameIgnoreCase(fullName)
                .stream()
                .filter(d -> !d.isSystemDriver())
                .findFirst();
        if (orphan.isPresent()) {
            Driver d = orphan.get();
            d.setUser(user);
            log.info("[DriverSync] Ghép tài xế #{} \"{}\" với tài khoản #{}",
                    d.getId(), d.getName(), user.getId());
            return driverRepository.save(d);
        }

        // 2. Ghép với tài xế ĐÃ XOÁ MỀM cùng tên (tạo lại người cũ)
        Optional<Driver> deleted = driverRepository.findByUserIsNullAndNameIgnoreCase(DELETED_PREFIX + fullName)
                .stream()
                .filter(d -> !d.isSystemDriver())
                .findFirst();
        if (deleted.isPresent()) {
            Driver d = deleted.get();
            d.setUser(user);
            log.info("[DriverSync] Mở lại tài xế đã xoá #{} cho tài khoản #{}", d.getId(), user.getId());
            return driverRepository.save(d);
        }

        // 3. Tạo mới
        Driver d = driverRepository.save(Driver.builder()
                .name(fullName)
                .active(true)
                .vehicleType(Driver.VehicleType.BOTH)   // mặc định — sửa lại ở màn hình Tài xế
                .user(user)
                .systemDriver(false)
                .build());
        log.info("[DriverSync] Tạo tài xế #{} \"{}\" cho tài khoản #{}", d.getId(), fullName, user.getId());
        return d;
    }

    /** Mở lại tài xế đã xoá mềm + đồng bộ họ tên theo tài khoản. */
    private void reactivate(Driver d, User user) {
        String fullName = safeName(user);
        boolean changed = false;

        if (!d.isActive()) {
            d.setActive(true);
            changed = true;
        }
        if (d.getName() != null && d.getName().startsWith(DELETED_PREFIX)) {
            d.setName(d.getOriginalName() != null ? d.getOriginalName()
                    : d.getName().substring(DELETED_PREFIX.length()));
            d.setOriginalName(null);
            changed = true;
        }
        // Họ tên đổi bên tài khoản → cập nhật sang tài xế
        if (!fullName.equals(d.getName())) {
            d.setName(fullName);
            changed = true;
        }
        if (changed) {
            driverRepository.save(d);
            log.info("[DriverSync] Kích hoạt tài xế #{} \"{}\"", d.getId(), d.getName());
        }
    }

    /** Xoá mềm: thêm tiền tố vào tên + tắt active. Giữ nguyên user_id để mở lại được. */
    private void softDelete(Driver d) {
        if (d.isSystemDriver()) return;
        if (!d.isActive() && d.getName() != null && d.getName().startsWith(DELETED_PREFIX)) return;

        if (d.getOriginalName() == null) d.setOriginalName(d.getName());
        if (d.getName() == null || !d.getName().startsWith(DELETED_PREFIX)) {
            d.setName(truncate(DELETED_PREFIX + d.getName(), 255));
        }
        d.setActive(false);
        driverRepository.save(d);
        log.info("[DriverSync] Xoá mềm tài xế #{} → \"{}\"", d.getId(), d.getName());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BACKFILL — tạo tài khoản cho tài xế cũ chưa có _user
    // ══════════════════════════════════════════════════════════════════════════

    /** Kết quả chạy backfill, trả về cho màn hình quản trị. */
    public record BackfillResult(int created, int linked, int skipped, List<String> details) {}

    /**
     * TẠO TÀI KHOẢN cho những tài xế đã có sẵn trong bảng {@code driver} nhưng
     * chưa gắn {@code user_id}, rồi ghép hai bên lại.
     *
     * <p>BỎ QUA: tài xế hệ thống ({@code systemDriver}), tài xế đã xoá mềm, và
     * tài xế trùng tên với một tài khoản đã có role {@code DRIVER} (trường hợp
     * này chỉ ghép chứ không tạo mới).
     *
     * <p>Mật khẩu mặc định là {@value #DEFAULT_PASSWORD} — NÊN yêu cầu tài xế
     * đổi ngay ở lần đăng nhập đầu tiên.
     */
    @Transactional
    public BackfillResult backfillDriverAccounts() {
        int created = 0, linked = 0, skipped = 0;
        List<String> details = new ArrayList<>();

        for (Driver d : driverRepository.findByUserIsNull()) {
            String name = d.getName() != null ? d.getName().trim() : "";

            if (d.isSystemDriver()) {
                skipped++;
                details.add("Bỏ qua (tài xế hệ thống): " + name);
                continue;
            }
            if (name.isEmpty() || name.startsWith(DELETED_PREFIX)) {
                skipped++;
                details.add("Bỏ qua (đã xoá / thiếu tên): " + name);
                continue;
            }

            // Đã có tài khoản trùng họ tên → chỉ ghép, không tạo thêm
            Optional<User> existing = userRepository.findAll().stream()
                    .filter(u -> !u.isDeleted())
                    .filter(u -> name.equalsIgnoreCase(u.getFullName() != null ? u.getFullName().trim() : ""))
                    .findFirst();

            if (existing.isPresent()) {
                User u = existing.get();
                if (driverRepository.findByUser_Id(u.getId()).isPresent()) {
                    skipped++;
                    details.add("Bỏ qua (tài khoản đã gắn tài xế khác): " + name);
                    continue;
                }
                ensureDriverRole(u);
                d.setUser(u);
                driverRepository.save(d);
                linked++;
                details.add("Ghép tài xế \"" + name + "\" với tài khoản @" + u.getUsername());
                continue;
            }

            // Tạo tài khoản mới
            String username = uniqueUsername(name);
            User u = userRepository.save(User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(DEFAULT_PASSWORD))
                    .fullName(name)
                    .role(Role.DRIVER)
                    .roles(new HashSet<>(Set.of(Role.DRIVER)))
                    .payrollRole(Role.DRIVER)
                    .department("Tài xế")
                    .position("Tài xế giao nhận")
                    .timeCreate(System.currentTimeMillis())
                    .isLockAccount(false)
                    .mfaEnabled(false)
                    .build());

            d.setUser(u);
            driverRepository.save(d);
            created++;
            details.add("Tạo tài khoản @" + username + " cho tài xế \"" + name + "\"");
        }

        log.info("[DriverSync] Backfill: tạo {} · ghép {} · bỏ qua {}", created, linked, skipped);
        return new BackfillResult(created, linked, skipped, details);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TẠO TÀI XẾ KÈM TÀI KHOẢN — dùng chung cho màn Quản lý tài xế và tạo nhanh ở kho
    // ══════════════════════════════════════════════════════════════════════════

    /** Mật khẩu mặc định khi TẠO MỚI tài xế trên giao diện. */
    public static final String NEW_DRIVER_PASSWORD = "123456";

    /** Tên miền email công ty — email tài xế = {username}@{EMAIL_DOMAIN}. */
    public static final String EMAIL_DOMAIN = "nhatnamfinefoods.com.vn";

    /**
     * TẠO tài xế mới, ĐỒNG THỜI tạo một tài khoản đăng nhập cho tài xế đó và gắn
     * hai bên lại (quan hệ 1–1).
     *
     * <p>Quy tắc tài khoản (theo yêu cầu nghiệp vụ):
     * <ul>
     *   <li>Họ tên = tên tài xế nhập vào.</li>
     *   <li>Username = <b>tên</b> + ký tự đầu của <b>họ và tên đệm</b>. VD "Tạ Lê
     *       Anh Đức" → {@code ductla}. Tự thêm số nếu trùng.</li>
     *   <li>Mật khẩu mặc định {@value #NEW_DRIVER_PASSWORD}.</li>
     *   <li>Email = {@code username@}{@value #EMAIL_DOMAIN}.</li>
     *   <li>Số điện thoại: ngẫu nhiên, không trùng trong DB.</li>
     *   <li>Ngày sinh = ngày tạo tài xế.</li>
     *   <li>Role = {@code DRIVER}.</li>
     * </ul>
     *
     * <p>Chặn TRÙNG TÊN với tài xế đang hoạt động (case-insensitive).
     * Với {@code systemDriver = true} (tài xế "ảo" như <i>Giao tại kho</i>) thì
     * CHỈ tạo bản ghi tài xế, KHÔNG tạo tài khoản.
     *
     * @throws IllegalArgumentException nếu tên trống hoặc đã tồn tại tài xế cùng tên.
     */
    @Transactional
    public Driver createDriverWithAccount(String rawName, Driver.VehicleType vehicleType,
                                          boolean systemDriver) {
        String fullName = rawName != null ? rawName.trim() : "";
        if (fullName.isBlank())
            throw new IllegalArgumentException("Tên tài xế không được trống");

        // Chặn trùng tên với tài xế đang hoạt động (bỏ qua bản ghi đã xoá mềm).
        boolean duplicated = driverRepository.findAll().stream()
                .filter(Driver::isActive)
                .anyMatch(x -> x.getName() != null
                        && x.getName().trim().equalsIgnoreCase(fullName));
        if (duplicated)
            throw new IllegalArgumentException("Đã có tài xế tên \"" + fullName + "\"");

        Driver.VehicleType vt = vehicleType != null ? vehicleType : Driver.VehicleType.BOTH;

        // Tài xế hệ thống: không cần tài khoản.
        if (systemDriver) {
            return driverRepository.save(Driver.builder()
                    .name(fullName).active(true).vehicleType(vt).systemDriver(true).build());
        }

        String username = uniqueDriverUsername(fullName);
        String email = uniqueEmailFor(username);
        String phone = randomUniquePhone();
        long dob = todayEpochMillis();

        User u = userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(NEW_DRIVER_PASSWORD))
                .fullName(fullName)
                .email(email)
                .phoneNumber(phone)
                .dateOfBirth(dob)
                .role(Role.DRIVER)
                .roles(new HashSet<>(Set.of(Role.DRIVER)))
                .payrollRole(Role.DRIVER)
                .department("Tài xế")
                .position("Tài xế giao nhận")
                .timeCreate(System.currentTimeMillis())
                .isLockAccount(false)
                .mfaEnabled(false)
                .build());

        Driver d = driverRepository.save(Driver.builder()
                .name(fullName).active(true).vehicleType(vt).systemDriver(false)
                .user(u).build());

        log.info("[DriverSync] Tạo tài xế #{} \"{}\" + tài khoản @{} ({})",
                d.getId(), fullName, username, email);
        return d;
    }

    /**
     * Username tài xế: <b>tên</b> (từ cuối) + ký tự đầu của <b>họ + tên đệm</b>.
     * "Tạ Lê Anh Đức" → "duc" + "t","l","a" = {@code ductla}. Bỏ dấu, đ→d, chữ thường.
     */
    static String driverUsernameBase(String fullName) {
        String[] parts = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        if (parts.length == 0) return "taixe";

        String given = asciiLower(parts[parts.length - 1]);
        StringBuilder sb = new StringBuilder(given);
        for (int i = 0; i < parts.length - 1; i++) {
            String w = asciiLower(parts[i]);
            if (!w.isEmpty()) sb.append(w.charAt(0));
        }
        String base = sb.toString();
        return base.isBlank() ? "taixe" : base;
    }

    private String uniqueDriverUsername(String fullName) {
        String base = driverUsernameBase(fullName);
        if (base.length() > 40) base = base.substring(0, 40);
        String candidate = base;
        int i = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + (++i);
        }
        return candidate;
    }

    /** Email theo username; nếu email trùng thì thêm số cho tới khi không trùng. */
    private String uniqueEmailFor(String username) {
        String candidate = username + "@" + EMAIL_DOMAIN;
        int i = 1;
        while (userRepository.existsByEmail(candidate)) {
            candidate = username + (++i) + "@" + EMAIL_DOMAIN;
        }
        return candidate;
    }

    /** SĐT ngẫu nhiên 10 số bắt đầu bằng 0, không trùng trong DB. */
    private String randomUniquePhone() {
        java.util.Random rnd = new java.util.Random();
        for (int attempt = 0; attempt < 10_000; attempt++) {
            StringBuilder sb = new StringBuilder("0");
            for (int i = 0; i < 9; i++) sb.append(rnd.nextInt(10));
            String phone = sb.toString();
            if (!userRepository.existsByPhoneNumber(phone)) return phone;
        }
        // Cực hiếm khi tới đây — dùng timestamp làm hậu tố cho chắc chắn duy nhất.
        return "0" + String.valueOf(System.nanoTime()).substring(0, 9);
    }

    /** Đầu ngày hôm nay (Asia/Ho_Chi_Minh) tính bằng epoch milli. */
    private static long todayEpochMillis() {
        return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .toInstant().toEpochMilli();
    }

    /** Bỏ dấu + đ→d + chữ thường + bỏ ký tự không phải a-z0-9. */
    private static String asciiLower(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "");
    }

    private void ensureDriverRole(User u) {
        Set<Role> roles = u.getRoles() != null ? new HashSet<>(u.getRoles()) : new HashSet<>();
        if (u.getRole() != null) roles.add(u.getRole());
        if (!roles.contains(Role.DRIVER)) {
            roles.add(Role.DRIVER);
            u.setRoles(roles);
            userRepository.save(u);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TIỆN ÍCH
    // ══════════════════════════════════════════════════════════════════════════

    private String safeName(User user) {
        String n = user.getFullName();
        if (n == null || n.isBlank()) n = user.getUsername();
        return n != null ? n.trim() : ("Tài xế #" + user.getId());
    }

    /** "Trần Nguyên Hải" → "trannguyenhai", thêm hậu tố số nếu đã tồn tại. */
    private String uniqueUsername(String fullName) {
        String base = Normalizer.normalize(fullName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "");
        if (base.isBlank()) base = "taixe";
        if (base.length() > 40) base = base.substring(0, 40);

        String candidate = base;
        int i = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + (++i);
        }
        return candidate;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}