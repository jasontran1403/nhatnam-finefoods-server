// PATH: src/main/java/com/nhatnam/server/utils/VietnameseHolidays.java
package com.nhatnam.server.utils;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * DANH SÁCH NGÀY NGHỈ LỄ được cả công ty áp dụng — dùng để trừ khỏi:
 * <ul>
 *   <li><b>Số công chuẩn của tháng</b>
 *       ({@link PayrollTaxCalculator#standardWorkdaysOf}) — để nhân viên nghỉ
 *       ngày lễ không bị coi là nghỉ thiếu công, không bị trừ lương pro-rata.</li>
 *   <li><b>Số ngày được phụ cấp cơm</b> — ngày nghỉ lễ theo luật thì không đi
 *       làm nên KHÔNG phát sinh bữa ăn giữa ca. Với các bộ phận có file chấm
 *       công (FACTORY/DRIVER) thì việc này đã tự đúng qua rule
 *       {@code mealEligible = worked >= 360} — không quẹt thẻ → không cộng.
 *       Với các bộ phận fallback (SALES/ACCOUNTING/WAREHOUSE) thì cơm =
 *       standardDays × 30.000 nên khi standardDays trừ ngày lễ là tự đúng.</li>
 * </ul>
 *
 * <h3>Vì sao là file riêng</h3>
 * <p>Đặt {@code Map} ngoài các class tính lương để (1) không lẫn với logic
 * tính lương, (2) sau này khi triển khai "upload lịch nghỉ" — OWNER khai báo
 * lịch nghỉ theo năm ở màn hình HR — chỉ cần thay implementation:
 * bỏ {@link #FIXED_HOLIDAYS}, đọc từ bảng {@code company_holiday} trong DB,
 * cache lại. Các call-site {@link #isHoliday}/{@link #holidaysInMonth} không
 * cần sửa.
 *
 * <h3>Chính sách khai báo hiện tại (2026)</h3>
 * <p>Chỉ khai 2 ngày Quốc khánh (1–2/9/2026). Các ngày lễ còn lại (Tết dương,
 * Giỗ Tổ, 30/4, 1/5…) SẼ khai bổ sung khi HR chốt lịch năm. Ngày lễ rơi vào
 * Chủ nhật vẫn khai bình thường ở đây — {@code standardWorkdaysOf} đã bỏ CN
 * trước rồi nên không bị trừ 2 lần.
 */
public final class VietnameseHolidays {

    private VietnameseHolidays() {}

    /**
     * Ngày nghỉ lễ theo NĂM. Key = năm dương lịch, value = tập hợp các
     * {@link MonthDay} nghỉ.
     *
     * <p>Dùng {@link MonthDay} thay vì {@link LocalDate} vì lịch nghỉ khai
     * theo năm — một entry sẽ chỉ được match nếu {@code year} khớp key. Tránh
     * lỗi copy-paste ngày lễ năm này sang năm khác.
     *
     * <p>Immutable — không sửa runtime. Khi chuyển sang lấy từ DB, thay bằng
     * cache có TTL hoặc invalidate khi HR update.
     */
    private static final Map<Integer, Set<MonthDay>> FIXED_HOLIDAYS;
    static {
        Map<Integer, Set<MonthDay>> m = new HashMap<>();

        // ── 2026 ─────────────────────────────────────────────────────────────
        // Quốc khánh 2/9. Ngày 1/9 nghỉ bù/liền theo lịch năm 2026 (2/9 = thứ Tư,
        // 1/9 = thứ Ba). Các ngày lễ còn lại năm 2026 sẽ khai sau khi HR chốt.
        m.put(2026, Set.of(
                MonthDay.of(9, 1),
                MonthDay.of(9, 2)
        ));

        FIXED_HOLIDAYS = Collections.unmodifiableMap(m);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Ngày {@code d} có phải nghỉ lễ không.
     *
     * @param d ngày kiểm tra; {@code null} → {@code false} (không phải lễ)
     */
    public static boolean isHoliday(LocalDate d) {
        if (d == null) return false;
        Set<MonthDay> days = FIXED_HOLIDAYS.get(d.getYear());
        if (days == null || days.isEmpty()) return false;
        return days.contains(MonthDay.of(d.getMonthValue(), d.getDayOfMonth()));
    }

    /**
     * Số ngày nghỉ lễ trong tháng RƠI VÀO ngày TRONG TUẦN (không phải Chủ nhật).
     *
     * <p>Dùng ở {@code standardWorkdaysOf} để trừ khỏi công chuẩn. KHÔNG đếm
     * lễ rơi Chủ nhật vì {@code standardWorkdaysOf} đã bỏ CN trước rồi — cộng
     * dồn sẽ trừ 2 lần → hụt công.
     *
     * @param year  năm dương lịch
     * @param month tháng 1..12
     * @return số ngày lễ rơi thứ Hai đến thứ Bảy trong tháng
     */
    public static int nonSundayHolidaysInMonth(int year, int month) {
        Set<MonthDay> days = FIXED_HOLIDAYS.get(year);
        if (days == null || days.isEmpty()) return 0;

        int count = 0;
        for (MonthDay md : days) {
            if (md.getMonthValue() != month) continue;
            LocalDate d = LocalDate.of(year, month, md.getDayOfMonth());
            if (d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) continue;
            count++;
        }
        return count;
    }


}