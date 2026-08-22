package com.nhatnam.server.enumtype;

import java.time.LocalTime;

/**
 * CÁC LOẠI NGOẠI LỆ CHẤM CÔNG — dùng chung cho 2 nguồn:
 * <ul>
 *   <li><b>Lịch nghỉ của công ty</b> ({@code AttendanceException}) — áp dụng cho
 *       TOÀN BỘ nhân viên trong ngày đó</li>
 *   <li><b>Đơn xin nghỉ cá nhân</b> ({@code AttendanceLeaveRequest}) — chỉ áp dụng
 *       cho 1 nhân viên, và chỉ khi trạng thái là ĐÃ DUYỆT</li>
 * </ul>
 *
 * <p>Mỗi loại tác động lên KHUNG GIỜ CHUẨN của ngày ({@code 08:00–17:00}) theo
 * cách khác nhau — xem {@link #adjustsStart()} / {@link #adjustsEnd()}.
 */
public enum AttendanceExceptionType {

    /**
     * NGHỈ CẢ NGÀY — chấm công hay không, đủ giờ vào/ra hay không, vẫn tính
     * ĐỦ 1 CÔNG và không cộng phút trễ/sớm nào.
     */
    FULL_DAY_OFF("Nghỉ cả ngày", false),

    /**
     * NGHỈ NỬA NGÀY – SÁNG: buổi sáng được nghỉ, chiều đi làm.
     * Khung giờ thu lại còn {@code 13:30–17:00}. Nhân viên vào lúc 12:00 hay
     * 13:30 đều không bị tính trễ; ra lúc 17:00 là đủ công.
     */
    HALF_DAY_MORNING_OFF("Nghỉ nửa ngày - Sáng", false),

    /**
     * NGHỈ NỬA NGÀY – CHIỀU: sáng đi làm, chiều được nghỉ.
     * Khung giờ thu lại còn {@code 08:00–12:00}. Vào 08:00 ra 12:00 là đủ công.
     */
    HALF_DAY_AFTERNOON_OFF("Nghỉ nửa ngày - Chiều", false),

    /**
     * ĐI TRỄ CÓ PHÉP — dời giờ VÀO ca sang mốc thời gian được chỉ định.
     * VD mốc {@code 10:00}: vào lúc 10:00 → đủ công; vào 10:01 → trễ 1 phút.
     */
    LATE_ARRIVAL("Đi trễ", true),

    /**
     * VỀ SỚM CÓ PHÉP — dời giờ KẾT THÚC ca về mốc thời gian được chỉ định.
     * VD mốc {@code 14:00}: ra lúc 14:00 → đủ công; ra 13:59 → sớm 1 phút.
     */
    EARLY_LEAVE("Về sớm", true);

    /** Giờ vào ca khi nghỉ buổi sáng. */
    public static final LocalTime AFTERNOON_SHIFT_START = LocalTime.of(13, 30);

    /** Giờ tan ca khi nghỉ buổi chiều. */
    public static final LocalTime MORNING_SHIFT_END = LocalTime.of(12, 0);

    private final String label;
    private final boolean requiresTime;

    AttendanceExceptionType(String label, boolean requiresTime) {
        this.label = label;
        this.requiresTime = requiresTime;
    }

    /** Nhãn tiếng Việt — đúng chuỗi dùng trong file Excel mẫu. */
    public String getLabel() { return label; }

    /**
     * TRUE nếu loại này BẮT BUỘC phải có mốc thời gian.
     * Chỉ {@link #LATE_ARRIVAL} và {@link #EARLY_LEAVE} cần.
     */
    public boolean isRequiresTime() { return requiresTime; }

    /** Loại này có làm thay đổi giờ VÀO ca không. */
    public boolean adjustsStart() {
        return this == LATE_ARRIVAL || this == HALF_DAY_MORNING_OFF;
    }

    /** Loại này có làm thay đổi giờ TAN ca không. */
    public boolean adjustsEnd() {
        return this == EARLY_LEAVE || this == HALF_DAY_AFTERNOON_OFF;
    }

    /** Nghỉ trọn ngày — tính đủ công vô điều kiện. */
    public boolean isFullDayCredit() { return this == FULL_DAY_OFF; }

    /**
     * Tìm loại theo nhãn tiếng Việt đọc từ file Excel.
     * Bỏ qua hoa/thường, dấu, và khoảng trắng thừa để nhận cả các biến thể gõ tay.
     */
    public static AttendanceExceptionType fromLabel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String n = normalize(raw);
        for (AttendanceExceptionType t : values()) {
            if (normalize(t.label).equals(n)) return t;
        }
        // Cho phép gõ thẳng tên hằng số (VD "FULL_DAY_OFF")
        for (AttendanceExceptionType t : values()) {
            if (t.name().equalsIgnoreCase(raw.trim())) return t;
        }
        return null;
    }

    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd')
                .replaceAll("[\\s\\-–—]+", " ")
                .trim();
    }
}
