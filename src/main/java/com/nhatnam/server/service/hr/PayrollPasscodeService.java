package com.nhatnam.server.service.hr;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PASSCODE XEM LƯƠNG — 6 chữ số, tách riêng khỏi mật khẩu đăng nhập.
 *
 * <pre>
 *  Luồng:
 *   1. Nhân viên mở trang "Quản lý lương" → FE gọi /status → nếu chưa có "vé"
 *      hợp lệ thì hiện màn hình nhập 6 số.
 *   2. Nhập đúng  → cấp "vé" {@code payrollAccessExpiresAt = now + 15 phút},
 *                   reset bộ đếm sai về 0.
 *   3. Nhập sai   → tăng bộ đếm. Sai đủ {@value #MAX_ATTEMPTS} lần → KHOÁ:
 *                   không cho nhập tiếp, không cho tự đổi passcode, backend
 *                   chặn luôn API phiếu lương. Chỉ admin mở khoá được.
 * </pre>
 *
 * <p><b>Vì sao chặn ở backend chứ không chỉ ở giao diện?</b> Màn hình passcode
 * ở FE chỉ là lớp trải nghiệm — ai mở DevTools gọi thẳng
 * {@code /api/factory-payroll/my-payslip} vẫn đọc được lương. Nên "vé" phải nằm
 * ở DB và được kiểm tra ngay trong controller phiếu lương.
 *
 * <p><b>Vì sao "vé" chỉ sống 15 phút?</b> Yêu cầu là "vào lại vẫn phải nhập
 * passcode" — phía FE không lưu trạng thái nên rời trang là mất. Vé ngắn hạn ở
 * BE chỉ để bọc trọn một phiên xem (đổi tháng, tải lại phiếu) mà không hỏi lại
 * liên tục, đồng thời tự hết hạn nếu người dùng bỏ máy đó.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollPasscodeService {

    /** Passcode mặc định khi nhân viên chưa từng đổi. */
    public static final String DEFAULT_PASSCODE = "000000";

    /** Số lần nhập sai tối đa trước khi khoá. */
    public static final int MAX_ATTEMPTS = 3;

    /** Thời hạn "vé" xem lương sau khi nhập đúng (millis). */
    public static final long ACCESS_TTL_MS = 15 * 60 * 1000L;

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // ══════════════════════════════════════════════════════════════════════════
    // NGOẠI LỆ NGHIỆP VỤ
    // ══════════════════════════════════════════════════════════════════════════

    /** Sai passcode — mang theo số lần còn lại để FE hiển thị. */
    public static class WrongPasscodeException extends RuntimeException {
        private final int remainingAttempts;

        public WrongPasscodeException(int remainingAttempts) {
            super(remainingAttempts > 0
                    ? "Mật khẩu xem lương không đúng. Bạn còn " + remainingAttempts + " lần thử."
                    : "Mật khẩu xem lương không đúng.");
            this.remainingAttempts = remainingAttempts;
        }

        public int getRemainingAttempts() { return remainingAttempts; }
    }

    /** Đã khoá — bắt buộc liên hệ admin. */
    public static class PasscodeLockedException extends RuntimeException {
        public PasscodeLockedException() {
            super("Chức năng xem lương đã bị khoá do nhập sai quá "
                    + MAX_ATTEMPTS + " lần. Vui lòng liên hệ quản trị viên để mở khoá.");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NHÂN VIÊN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Trạng thái passcode của chính mình — FE dùng để quyết định hiện màn hình
     * nhập passcode, màn hình đã khoá, hay cho vào thẳng phiếu lương.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> status(User principal) {
        User u = reload(principal);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("locked",             u.isPayrollPasscodeLocked());
        m.put("maxAttempts",        MAX_ATTEMPTS);
        m.put("failCount",          u.getPayrollPasscodeFailCount());
        m.put("remainingAttempts",  Math.max(0, MAX_ATTEMPTS - u.getPayrollPasscodeFailCount()));
        m.put("usingDefault",       u.getPayrollPasscode() == null);
        m.put("unlocked",           hasValidAccess(u));
        m.put("accessExpiresAt",    u.getPayrollAccessExpiresAt());
        m.put("lockedAt",           u.getPayrollPasscodeLockedAt());
        return m;
    }

    /**
     * Kiểm tra "vé" xem lương còn hiệu lực. Dùng ở controller phiếu lương.
     * Nhận entity nào cũng được — luôn đọc lại từ DB để tránh dùng bản
     * principal cũ đã cache trong JWT filter.
     */
    @Transactional(readOnly = true)
    public boolean hasValidAccessFor(User principal) {
        return hasValidAccess(reload(principal));
    }

    private boolean hasValidAccess(User u) {
        if (u == null || u.isPayrollPasscodeLocked()) return false;
        Long exp = u.getPayrollAccessExpiresAt();
        return exp != null && exp > System.currentTimeMillis();
    }

    @Transactional(readOnly = true)
    public boolean isLocked(User principal) {
        User u = reload(principal);
        return u != null && u.isPayrollPasscodeLocked();
    }

    /**
     * XÁC THỰC PASSCODE.
     *
     * <p><b>noRollbackFor — KHÔNG được bỏ.</b> {@link #registerFailure} ghi bộ đếm sai
     * rồi hàm này ném {@link WrongPasscodeException}. Ném RuntimeException ra khỏi một
     * method {@code @Transactional} sẽ đánh dấu transaction ROLLBACK ⇒ lần tăng bộ đếm
     * vừa rồi bị huỷ, và cờ khoá đặt ở lần sai thứ 3 cũng vậy.
     *
     * <p>Triệu chứng nếu thiếu: nhập sai bao nhiêu lần cũng luôn báo "còn 2 lần thử"
     * và không bao giờ khoá được.
     *
     * <p>Liệt kê cả {@link PasscodeLockedException} vì đó chính là ngoại lệ được ném ở
     * lần sai thứ 3 — đúng lúc cần commit cờ khoá nhất.
     *
     * @return trạng thái mới (giống {@link #status}) khi đúng
     * @throws PasscodeLockedException đã bị khoá từ trước
     * @throws WrongPasscodeException  sai passcode (đã tăng bộ đếm)
     */
    @Transactional(noRollbackFor = { WrongPasscodeException.class, PasscodeLockedException.class })
    public Map<String, Object> verify(User principal, String passcode) {
        User u = reload(principal);
        if (u == null) throw new IllegalArgumentException("Không tìm thấy tài khoản");
        if (u.isPayrollPasscodeLocked()) throw new PasscodeLockedException();

        if (passcode == null || !SIX_DIGITS.matcher(passcode.trim()).matches())
            throw new IllegalArgumentException("Mật khẩu xem lương phải gồm đúng 6 chữ số");

        if (!matches(u, passcode.trim())) {
            registerFailure(u);
            if (u.isPayrollPasscodeLocked()) throw new PasscodeLockedException();
            throw new WrongPasscodeException(Math.max(0, MAX_ATTEMPTS - u.getPayrollPasscodeFailCount()));
        }

        u.setPayrollPasscodeFailCount(0);
        u.setPayrollAccessExpiresAt(System.currentTimeMillis() + ACCESS_TTL_MS);
        userRepository.save(u);
        return status(u);
    }

    /**
     * ĐỔI PASSCODE — cần passcode CŨ + 2 lần nhập passcode MỚI.
     *
     * <p>Nhập sai passcode cũ vẫn tính vào bộ đếm 3 lần: nếu không, đây sẽ là
     * đường vòng để dò passcode không giới hạn.
     *
     * <p>Đổi xong thì HUỶ "vé" hiện tại → buộc nhập lại bằng passcode mới.
     *
     * <p>{@code noRollbackFor} vì lý do y hệt {@link #verify}: nhập sai passcode cũ
     * phải ghi được bộ đếm, không thì đây thành đường dò passcode không giới hạn.
     */
    @Transactional(noRollbackFor = { WrongPasscodeException.class, PasscodeLockedException.class })
    public void changePasscode(User principal, String current, String next, String confirm) {
        User u = reload(principal);
        if (u == null) throw new IllegalArgumentException("Không tìm thấy tài khoản");
        if (u.isPayrollPasscodeLocked()) throw new PasscodeLockedException();

        if (current == null || current.isBlank())
            throw new IllegalArgumentException("Vui lòng nhập mật khẩu xem lương hiện tại");
        if (next == null || !SIX_DIGITS.matcher(next.trim()).matches())
            throw new IllegalArgumentException("Mật khẩu mới phải gồm đúng 6 chữ số");
        if (!next.trim().equals(confirm == null ? null : confirm.trim()))
            throw new IllegalArgumentException("Hai lần nhập mật khẩu mới không khớp");

        if (!matches(u, current.trim())) {
            registerFailure(u);
            if (u.isPayrollPasscodeLocked()) throw new PasscodeLockedException();
            throw new WrongPasscodeException(Math.max(0, MAX_ATTEMPTS - u.getPayrollPasscodeFailCount()));
        }

        if (next.trim().equals(current.trim()))
            throw new IllegalArgumentException("Mật khẩu mới phải khác mật khẩu hiện tại");

        u.setPayrollPasscode(passwordEncoder.encode(next.trim()));
        u.setPayrollPasscodeFailCount(0);
        u.setPayrollAccessExpiresAt(null);   // huỷ vé → nhập lại bằng passcode mới
        userRepository.save(u);
        log.info("[PayrollPasscode] User {} đã đổi mật khẩu xem lương", u.getUsername());
    }

    /** Chủ động huỷ vé — dùng khi rời trang / bấm "Khoá lại". */
    @Transactional
    public void revokeAccess(User principal) {
        User u = reload(principal);
        if (u == null) return;
        u.setPayrollAccessExpiresAt(null);
        userRepository.save(u);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // QUẢN TRỊ (OWNER / ADMIN / HR / SUPERADMIN)
    // ══════════════════════════════════════════════════════════════════════════

    /** Danh sách nhân viên đang bị khoá xem lương. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lockedUsers() {
        return userRepository.findPayrollPasscodeLocked().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",            u.getId());
            m.put("username",      u.getUsername());
            m.put("fullName",      u.getFullName());
            m.put("department",    u.getDepartment());
            m.put("position",      u.getPosition());
            m.put("role",          u.getRole() != null ? u.getRole().name() : null);
            m.put("failCount",     u.getPayrollPasscodeFailCount());
            m.put("lockedAt",      u.getPayrollPasscodeLockedAt());
            m.put("usingDefault",  u.getPayrollPasscode() == null);
            return m;
        }).toList();
    }

    /**
     * MỞ KHOÁ cho 1 nhân viên.
     *
     * @param resetToDefault đưa passcode về {@value #DEFAULT_PASSCODE}. Đặt true
     *                       khi nhân viên QUÊN passcode (trường hợp phổ biến
     *                       nhất); đặt false khi chỉ bị khoá do người khác nghịch
     *                       máy và nhân viên vẫn nhớ passcode của mình.
     */
    @Transactional
    public Map<String, Object> unlock(long userId, boolean resetToDefault, String actorName) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên"));

        u.setPayrollPasscodeLocked(false);
        u.setPayrollPasscodeLockedAt(null);
        u.setPayrollPasscodeFailCount(0);
        u.setPayrollAccessExpiresAt(null);
        if (resetToDefault) u.setPayrollPasscode(null);   // null ⇒ hiểu là 000000
        userRepository.save(u);

        log.info("[PayrollPasscode] {} đã mở khoá xem lương cho {} (reset={})",
                actorName, u.getUsername(), resetToDefault);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           u.getId());
        m.put("username",     u.getUsername());
        m.put("fullName",     u.getFullName());
        m.put("resetToDefault", resetToDefault);
        return m;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NỘI BỘ
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * So khớp passcode. {@code payrollPasscode == null} nghĩa là chưa từng đổi →
     * so với {@value #DEFAULT_PASSCODE}.
     */
    private boolean matches(User u, String raw) {
        if (u.getPayrollPasscode() == null) return DEFAULT_PASSCODE.equals(raw);
        return passwordEncoder.matches(raw, u.getPayrollPasscode());
    }

    /** Tăng bộ đếm sai; chạm ngưỡng thì khoá. Luôn huỷ vé đang có. */
    private void registerFailure(User u) {
        int fails = u.getPayrollPasscodeFailCount() + 1;
        u.setPayrollPasscodeFailCount(fails);
        u.setPayrollAccessExpiresAt(null);
        if (fails >= MAX_ATTEMPTS) {
            u.setPayrollPasscodeLocked(true);
            u.setPayrollPasscodeLockedAt(System.currentTimeMillis());
            log.warn("[PayrollPasscode] KHOÁ xem lương của {} sau {} lần sai", u.getUsername(), fails);
        }
        userRepository.save(u);
    }

    /** Đọc lại từ DB — principal trong SecurityContext là bản chụp lúc parse JWT. */
    private User reload(User principal) {
        if (principal == null) return null;
        return userRepository.findById(principal.getId()).orElse(null);
    }
}