package com.nhatnam.server.utils;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;

/**
 * TÍNH TOÁN NGÀY KỶ NIỆM LẶP HÀNG NĂM — sinh nhật nhân viên, sinh nhật khách lẻ,
 * ngày khai trương cửa hàng của khách công ty.
 *
 * <p>Ba màn hình khác nhau (Quản lý nhân viên, Khách hàng của SELLER, Dự báo đặt hàng)
 * cùng cần đúng một phép tính "còn bao nhiêu ngày nữa tới dịp này" và "dịp này có rơi
 * vào tháng hiện tại mà chưa qua không". Gom về một chỗ để ba màn hình không trôi lệch
 * nhau — chỉ cần một chỗ định nghĩa sai "hôm nay là sinh nhật thì tính 0 hay 365 ngày"
 * là bảng xếp hạng của mỗi màn hình ra một kiểu.
 */
public final class AnniversaryUtil {

    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private AnniversaryUtil() {}

    /** Đổi epoch millis → LocalDate theo giờ VN. Trả null nếu đầu vào null. */
    public static LocalDate toLocalDate(Long epochMillis) {
        if (epochMillis == null) return null;
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(VN_ZONE).toLocalDate();
    }

    /** Đổi LocalDate → epoch millis lúc 00:00 giờ VN. */
    public static Long toEpochMillis(LocalDate date) {
        if (date == null) return null;
        return date.atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
    }

    public static LocalDate today() {
        return LocalDate.now(VN_ZONE);
    }

    /**
     * SỐ NGÀY CÒN LẠI tới lần kỷ niệm kế tiếp, tính từ {@code today}.
     *
     * <p>Hôm nay đúng dịp ⇒ trả về {@code 0} (chứ không phải 365) — đó là ngày cần hành
     * động gấp nhất nên phải đứng đầu danh sách sort. Dịp đã qua trong năm nay ⇒ nhảy
     * sang năm sau.
     *
     * <p>29/02 của năm không nhuận được quy về 28/02, nếu không {@code withYear()} sẽ
     * ném lỗi và làm hỏng cả trang danh sách chỉ vì một người sinh ngày nhuận.
     *
     * @return {@code null} nếu chưa khai báo ngày — phía gọi tự quyết định xếp cuối.
     */
    public static Integer daysUntilNext(Long anniversaryMillis, LocalDate today) {
        LocalDate src = toLocalDate(anniversaryMillis);
        if (src == null) return null;

        LocalDate next = safeWithYear(src, today.getYear());
        if (next.isBefore(today)) next = safeWithYear(src, today.getYear() + 1);
        return (int) java.time.temporal.ChronoUnit.DAYS.between(today, next);
    }

    public static Integer daysUntilNext(Long anniversaryMillis) {
        return daysUntilNext(anniversaryMillis, today());
    }

    /**
     * DỊP NÀY CÓ RƠI VÀO THÁNG HIỆN TẠI VÀ CHƯA QUA KHÔNG?
     *
     * <p>Đây là điều kiện tô màu ở màn hình nhân viên và màn hình khách hàng của SELLER:
     * "sinh nhật trong tháng (không tính đã qua)". Đã qua ngày ⇒ về màu bình thường.
     * Đúng hôm nay ⇒ vẫn tính là chưa qua.
     */
    public static boolean isUpcomingThisMonth(Long anniversaryMillis, LocalDate today) {
        LocalDate src = toLocalDate(anniversaryMillis);
        if (src == null) return false;
        if (src.getMonthValue() != today.getMonthValue()) return false;
        return safeDayOfMonth(src, today) >= today.getDayOfMonth();
    }

    public static boolean isUpcomingThisMonth(Long anniversaryMillis) {
        return isUpcomingThisMonth(anniversaryMillis, today());
    }

    /** Đúng hôm nay là dịp kỷ niệm. */
    public static boolean isToday(Long anniversaryMillis, LocalDate today) {
        LocalDate src = toLocalDate(anniversaryMillis);
        if (src == null) return false;
        return MonthDay.from(safeWithYear(src, today.getYear())).equals(MonthDay.from(today));
    }

    /** Ngày kỷ niệm kế tiếp (đã quy về năm hiện tại hoặc năm sau). */
    public static LocalDate nextOccurrence(Long anniversaryMillis, LocalDate today) {
        LocalDate src = toLocalDate(anniversaryMillis);
        if (src == null) return null;
        LocalDate next = safeWithYear(src, today.getYear());
        return next.isBefore(today) ? safeWithYear(src, today.getYear() + 1) : next;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** {@code withYear} an toàn cho 29/02: năm không nhuận thì lùi về 28/02. */
    private static LocalDate safeWithYear(LocalDate src, int year) {
        if (src.getMonthValue() == 2 && src.getDayOfMonth() == 29 && !java.time.Year.isLeap(year)) {
            return LocalDate.of(year, 2, 28);
        }
        return src.withYear(year);
    }

    private static int safeDayOfMonth(LocalDate src, LocalDate today) {
        return safeWithYear(src, today.getYear()).getDayOfMonth();
    }
}
