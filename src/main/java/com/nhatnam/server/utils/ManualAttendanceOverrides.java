// PATH: src/main/java/com/nhatnam/server/utils/ManualAttendanceOverrides.java
package com.nhatnam.server.utils;

import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HARDCODE OVERRIDE công thực tế + ngày cơm cho một số (nhân viên × tháng)
 * cụ thể — dùng khi dữ liệu chấm công tự động KHÔNG phản ánh đúng thực tế
 * (nhân viên đi làm nhưng quên quẹt thẻ, hoặc máy chấm công hỏng…).
 *
 * <p>Áp dụng ở cả {@code SalaryExportService} (file lương tổng hợp) và
 * {@code BankPaymentExportService} (file chi lương ngân hàng). Hai file này
 * SẼ hiển thị số công / số tiền theo override thay vì theo attendance.
 *
 * <p><b>Phạm vi</b>: chỉ FILE EXPORT. KHÔNG đụng vào:
 * <ul>
 *   <li>Bảng chấm công gốc ({@code AttendanceEntry}) — vẫn giữ đúng dữ liệu
 *       quẹt thẻ để sau này có thể audit.</li>
 *   <li>PayrollBatch / Payslip — nếu HR tạo batch tính lương chính thức, dữ
 *       liệu đó độc lập.</li>
 * </ul>
 *
 * <p><b>Vòng đời</b>: chỉ dành cho tháng hiện tại hoặc gần hiện tại. Tháng
 * SAU vẫn tính bình thường (map chỉ có đúng key cho tháng được chỉ định).
 * Khi hết nhu cầu, xoá entry khỏi {@link #OVERRIDES} là xong.
 *
 * <p><b>Tại sao hardcode trong code mà không để trong DB</b>: đây là biện
 * pháp SỬA GẤP cho 1-2 nhân viên trong 1 tháng. Chuyển sang bảng DB sẽ cần
 * thêm UI nhập (OWNER) + migration — overkill cho trường hợp này. Nếu phát
 * sinh ≥10 override thì mới nên cân nhắc chuyển sang DB.
 */
public final class ManualAttendanceOverrides {

    private ManualAttendanceOverrides() {}

    /**
     * Số ngày công + số ngày cơm được chỉ định bằng tay.
     *
     * @param actualDays         số công thực tế đi làm (CHƯA cộng công lễ)
     * @param mealDays           số ngày tính phụ cấp cơm (30.000đ/ngày)
     * @param skipHolidayBonus   true = KHÔNG cộng công lễ của tháng vào actualDays.
     *                           Dùng cho nhân viên vào làm SAU ngày lễ (VD Tuấn Tài
     *                           vào 9/9/2026 — lễ 1–2/9 nằm trước ngày bắt đầu
     *                           nên không được hưởng).
     */
    public record Override(int actualDays, int mealDays, boolean skipHolidayBonus) {
        /** Constructor rút gọn — mặc định skipHolidayBonus = false. */
        public Override(int actualDays, int mealDays) {
            this(actualDays, mealDays, false);
        }
    }

    /** Key = (tên đã normalize, year, month). */
    private record Key(String nameKey, int year, int month) {}

    private static final Map<Key, Override> OVERRIDES;
    static {
        Map<Key, Override> m = new HashMap<>();

        // ── Tháng 9/2026 ─────────────────────────────────────────────────────
        // Đặng Nguyễn Tuấn Tài (tài xế giao nhận): dữ liệu chấm công đang chỉ
        // ghi 1/24 công, nhưng thực tế đi làm 19 công. Cả lương và cơm đều
        // tính trên 19.
        m.put(new Key("dang nguyen tuan tai", 2026, 9), new Override(19, 19, true));

        // Ngô Thị Mỹ Hạnh (công nhân đóng gói): không có dữ liệu chấm công
        // tháng 9 nên đang hiển thị 0/24, phụ cấp cơm = 0đ. Thực tế đi làm
        // đủ công tháng — áp full 24/24, cơm 24 ngày × 30k.
        m.put(new Key("ngo thi my hanh", 2026, 9), new Override(24, 24));

        // Nguyễn Ngọc Anh (tài xế giao nhận): tương tự — thiếu dữ liệu chấm
        // công tháng 9, thực tế đi làm đủ. Áp full 24/24.
        m.put(new Key("nguyen ngoc anh", 2026, 9), new Override(24, 24));

        OVERRIDES = Collections.unmodifiableMap(m);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Trả override cho (tên × tháng × năm) nếu có, {@code null} nếu không.
     *
     * <p>Tên được so khớp theo bản đã bỏ dấu + lowercase + gộp whitespace, nên
     * "Đặng Nguyễn Tuấn Tài", "dang nguyen tuan tai", "ĐẶNG   NGUYỄN TUẤN TÀI"
     * đều match cùng 1 entry.
     *
     * @param fullName tên đầy đủ của nhân viên (từ {@code User.fullName}); null
     *                 → trả null
     */
    public static Override lookup(String fullName, int year, int month) {
        if (fullName == null) return null;
        String key = normalize(fullName);
        if (key.isEmpty()) return null;
        return OVERRIDES.get(new Key(key, year, month));
    }

    /** Bỏ dấu tiếng Việt, lowercase, gộp whitespace — khớp với
     *  {@code BankPaymentExportService.normalize} để đồng nhất cách so tên. */
    private static String normalize(String s) {
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ROUNDING HELPER — dùng chung ở 2 file export
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Làm tròn LÊN hàng nghìn (ceiling). Ví dụ:
     * <pre>
     *   6.729.173 → 6.730.000
     *   6.729.000 → 6.729.000  (đã tròn, giữ nguyên)
     *          0 → 0
     * </pre>
     * Dùng cho cột "Lương thực nhận" (file tổng hợp) và cột "Số tiền" (file
     * NH) — để số chuyển khoản gọn, dễ đếm.
     */
    public static long roundUpToThousand(long v) {
        if (v <= 0) return v;
        long r = v % 1000L;
        return r == 0 ? v : v + (1000L - r);
    }

    /**
     * Số công lễ (T2..T7) mà một nhân viên được CỘNG THÊM vào công thực tế
     * trong tháng, dựa trên override (nếu có).
     *
     * <p>Áp dụng cho FACTORY và DRIVER: file chấm công và dữ liệu điểm danh ODO
     * không ghi 1–2/9 (ngày lễ) vì nhân viên không đi làm hôm đó. Trừ thẳng 2
     * ngày khỏi lương là sai — ngày lễ hợp pháp vẫn được hưởng lương. Hàm này
     * trả về số ngày cần cộng bù (= {@link VietnameseHolidays#nonSundayHolidaysInMonth}),
     * trừ trường hợp override báo {@code skipHolidayBonus = true}.
     *
     * @param fullName tên NV để tra override
     * @return 0 nếu NV bị skip; số ngày lễ T2..T7 nếu không
     */
    public static int holidayBonusDaysFor(String fullName, int year, int month) {
        Override ov = lookup(fullName, year, month);
        if (ov != null && ov.skipHolidayBonus()) return 0;
        return com.nhatnam.server.utils.VietnameseHolidays.nonSundayHolidaysInMonth(year, month);
    }
}