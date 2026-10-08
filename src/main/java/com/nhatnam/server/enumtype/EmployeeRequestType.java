// ──────────────────────────────────────────────────────────────────────
// PATH: src/main/java/com/nhatnam/server/enumtype/EmployeeRequestType.java
// ──────────────────────────────────────────────────────────────────────
package com.nhatnam.server.enumtype;

import java.util.Arrays;

/**
 * LOẠI ĐƠN NHÂN VIÊN TỰ TẠO trên trang "Tạo phiếu".
 *
 * <h3>Cửa sổ ngày được phép chọn khi tạo đơn</h3>
 * <ul>
 *   <li>Nghỉ phép — LÙI: ngày 1 tháng hiện tại; TIẾN: không giới hạn.
 *       (Hôm nay tháng 9 vẫn xin nghỉ cho tháng 12 được.)</li>
 *   <li>Công tác — 7 ngày trước → không giới hạn tương lai.</li>
 *   <li>Đi trễ / Về sớm / Quên chấm công — 7 ngày trước → 7 ngày sau.</li>
 * </ul>
 * Với các loại dùng offset, offset KHÔNG tính ngày hiện tại:
 * -7 nghĩa là đúng 7 ngày trước hôm nay.
 */
public enum EmployeeRequestType {

    /** Nghỉ phép — khoảng ngày; kèm khung giờ nếu nghỉ ít hơn 1 ngày. */
    LEAVE("Nghỉ phép", true, false, -7, null, true),

    /** Đi công tác — khoảng ngày, luôn tính đủ công. */
    BUSINESS_TRIP("Công tác", true, false, -7, null, false),

    /**
     * PHASE 6b (10/2026): LÀM Ở NHÀ / TỪ XA.
     *
     * <p>Dành cho nhân viên không thể đến công ty (ốm nhẹ, trời bão, kẹt xe
     * dài, đang trông con…) nhưng vẫn làm việc được từ xa. OWNER / ADMIN duyệt;
     * ngày WFH được duyệt sẽ tính 1 công ĐẦY ĐỦ như đi làm thật — không trừ
     * quỹ phép năm, không trừ phụ cấp cơm.
     *
     * <h3>So với các loại khác</h3>
     * <ul>
     *   <li>Khác {@link #LEAVE}: không rút quỹ phép, không có khái niệm "nửa
     *       ngày paid / unpaid" — WFH đã duyệt = 1 công.</li>
     *   <li>Giống {@link #BUSINESS_TRIP}: {@link #alwaysFullCredit()} = true.</li>
     * </ul>
     *
     * <p>Cửa sổ chọn ngày: -7 tới +∞ (nhân viên có thể báo trước nhiều tháng
     * hoặc xin phê duyệt lại trong vòng 7 ngày trước).
     */
    WORK_FROM_HOME("Làm ở nhà", true, false, -7, null, false);

//    /** Đi trễ — 1 ngày, khai SỐ PHÚT xin trễ. */
//    LATE_ARRIVAL("Đi trễ", false, true, -7, 7, false),
//
//    /** Về sớm — 1 ngày, khai SỐ PHÚT xin về sớm. */
//    EARLY_LEAVE("Về sớm", false, true, -7, 7, false),
//
//    /** Quên chấm công — 1 ngày, không cần giờ. */
//    MISSING_PUNCH("Quên chấm công", false, false, -7, 7, false);

    private final String label;
    private final boolean rangeBased;
    private final boolean minutesBased;
    private final int minOffsetDays;
    private final Integer maxOffsetDays;
    /**
     * TRUE nếu cửa sổ LÙI là "ngày 1 tháng hiện tại" thay vì offset so với hôm nay.
     * Chiều TIẾN vẫn không giới hạn (maxOffsetDays = null).
     */
    private final boolean monthScoped;

    EmployeeRequestType(String label, boolean rangeBased, boolean minutesBased,
                        int minOffsetDays, Integer maxOffsetDays, boolean monthScoped) {
        this.label = label;
        this.rangeBased = rangeBased;
        this.minutesBased = minutesBased;
        this.minOffsetDays = minOffsetDays;
        this.maxOffsetDays = maxOffsetDays;
        this.monthScoped = monthScoped;
    }

    /** Nhãn tiếng Việt hiển thị trên UI và trong nội dung thông báo. */
    public String getLabel() { return label; }

    /** TRUE nếu đơn khai theo KHOẢNG NGÀY (nghỉ phép / công tác). */
    public boolean isRangeBased() { return rangeBased; }

    /** TRUE nếu đơn bắt buộc khai SỐ PHÚT (đi trễ / về sớm). */
    public boolean isMinutesBased() { return minutesBased; }

    /**
     * Số ngày lệch NHỎ NHẤT so với hôm nay được phép chọn.
     * {@code -7} = 7 ngày trước hôm nay.
     * <p>Chỉ có ý nghĩa khi {@link #isMonthScoped()} = false.
     */
    public int minOffsetDays() { return minOffsetDays; }

    /**
     * Số ngày lệch LỚN NHẤT so với hôm nay được phép chọn.
     * {@code null} = không giới hạn (công tác / nghỉ phép xin trước nhiều tháng).
     * <p>Chỉ có ý nghĩa khi {@link #isMonthScoped()} = false.
     */
    public Integer maxOffsetDays() { return maxOffsetDays; }

    /**
     * TRUE nếu cửa sổ LÙI của loại này là "ngày 1 THÁNG HIỆN TẠI",
     * không phụ thuộc hôm nay là ngày nào. Chiều TIẾN không giới hạn.
     * <p>Ví dụ: hôm nay 30/9 vẫn được tạo đơn nghỉ cho ngày 1/9;
     * hôm nay tháng 9 vẫn xin nghỉ cho tháng 12.
     */
    public boolean isMonthScoped() { return monthScoped; }

    /**
     * Loại này có được tính ĐỦ CÔNG khi duyệt "có phép" hay không.
     * Công tác và Làm-ở-nhà luôn đủ công; nghỉ phép thì tuỳ OWNER chọn
     * có lương / không lương.
     */
    public boolean alwaysFullCredit() {
        return this == BUSINESS_TRIP || this == WORK_FROM_HOME;
    }

    public static EmployeeRequestType parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(raw.trim()))
                .findFirst().orElse(null);
    }
}