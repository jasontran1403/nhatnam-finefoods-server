package com.nhatnam.server.enumtype;

import java.util.Arrays;

/**
 * LOẠI ĐƠN NHÂN VIÊN TỰ TẠO trên trang "Tạo phiếu".
 *
 * <p>Khác hẳn với {@link AttendanceExceptionType} — cái đó mô tả NGOẠI LỆ đã
 * được chốt và đưa vào công thức tính công. Enum này mô tả CÁI NHÂN VIÊN XIN,
 * lúc tạo ra chưa có hiệu lực gì cả; phải qua tay OWNER duyệt mới tác động tới
 * bảng chấm công.
 *
 * <h3>Hai nhóm hoàn toàn khác nhau về mặt thời gian</h3>
 * <pre>
 *   NHÓM KHOẢNG NGÀY  (rangeBased = true)
 *     LEAVE          Nghỉ phép   — từ ngày … đến ngày …, có thể kèm khung giờ
 *                                  nếu xin nghỉ ÍT HƠN 1 ngày (VD nghỉ 3 tiếng chiều)
 *     BUSINESS_TRIP  Công tác    — từ ngày … đến ngày …
 *
 *   NHÓM MỘT NGÀY     (rangeBased = false)
 *     LATE_ARRIVAL   Đi trễ          — 1 ngày + SỐ PHÚT xin trễ
 *     EARLY_LEAVE    Về sớm          — 1 ngày + SỐ PHÚT xin về sớm
 *     MISSING_PUNCH  Quên chấm công  — 1 ngày, không cần giờ
 * </pre>
 *
 * <h3>Cửa sổ ngày được phép chọn khi tạo đơn</h3>
 * Quy tắc nằm ở {@link #minOffsetDays()} / {@link #maxOffsetDays()}, tính theo
 * số ngày lệch so với HÔM NAY. Việc đặt ở enum (thay vì rải rác trong service và
 * component React) để FE và BE không bao giờ lệch nhau về luật:
 * <ul>
 *   <li>Nghỉ phép / Công tác — từ HÔM NAY trở đi, không được chọn quá khứ.
 *       Xin nghỉ cho ngày đã trôi qua thì không còn là "xin" nữa.</li>
 *   <li>Đi trễ / Về sớm / Quên chấm công — chỉ HÔM QUA, HÔM NAY, NGÀY MAI.
 *       Ba loại này bản chất là đính chính dữ liệu máy chấm công nên phải khai
 *       gần thời điểm xảy ra, tránh khai bù cả tháng vào cuối kỳ.</li>
 * </ul>
 */
public enum EmployeeRequestType {

    /** Nghỉ phép — khoảng ngày; kèm khung giờ nếu nghỉ ít hơn 1 ngày. */
    LEAVE("Nghỉ phép", true, false, 0, null),

    /** Đi công tác — khoảng ngày, luôn tính đủ công. */
    BUSINESS_TRIP("Công tác", true, false, 0, null),

    /** Đi trễ — 1 ngày, khai SỐ PHÚT xin trễ. */
    LATE_ARRIVAL("Đi trễ", false, true, -1, 1),

    /** Về sớm — 1 ngày, khai SỐ PHÚT xin về sớm. */
    EARLY_LEAVE("Về sớm", false, true, -1, 1),

    /** Quên chấm công — 1 ngày, không cần giờ. */
    MISSING_PUNCH("Quên chấm công", false, false, -1, 1);

    private final String label;
    private final boolean rangeBased;
    private final boolean minutesBased;
    private final int minOffsetDays;
    private final Integer maxOffsetDays;

    EmployeeRequestType(String label, boolean rangeBased, boolean minutesBased,
                        int minOffsetDays, Integer maxOffsetDays) {
        this.label = label;
        this.rangeBased = rangeBased;
        this.minutesBased = minutesBased;
        this.minOffsetDays = minOffsetDays;
        this.maxOffsetDays = maxOffsetDays;
    }

    /** Nhãn tiếng Việt hiển thị trên UI và trong nội dung thông báo. */
    public String getLabel() { return label; }

    /** TRUE nếu đơn khai theo KHOẢNG NGÀY (nghỉ phép / công tác). */
    public boolean isRangeBased() { return rangeBased; }

    /** TRUE nếu đơn bắt buộc khai SỐ PHÚT (đi trễ / về sớm). */
    public boolean isMinutesBased() { return minutesBased; }

    /**
     * Số ngày lệch NHỎ NHẤT so với hôm nay được phép chọn.
     * {@code 0} = hôm nay, {@code -1} = hôm qua.
     */
    public int minOffsetDays() { return minOffsetDays; }

    /**
     * Số ngày lệch LỚN NHẤT so với hôm nay được phép chọn.
     * {@code null} = không giới hạn (nghỉ phép có thể xin trước nhiều tháng).
     */
    public Integer maxOffsetDays() { return maxOffsetDays; }

    /**
     * Loại này có được tính ĐỦ CÔNG khi duyệt "có phép" hay không.
     * Công tác luôn đủ công; nghỉ phép thì tuỳ OWNER chọn có lương / không lương.
     */
    public boolean alwaysFullCredit() { return this == BUSINESS_TRIP; }

    public static EmployeeRequestType parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(raw.trim()))
                .findFirst().orElse(null);
    }
}