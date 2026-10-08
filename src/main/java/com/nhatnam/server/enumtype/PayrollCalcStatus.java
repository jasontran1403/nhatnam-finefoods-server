package com.nhatnam.server.enumtype;

/**
 * TRẠNG THÁI VÒNG ĐỜI TÍNH LƯƠNG CỦA 1 THÁNG — Phase 2 refactor (10/2026).
 *
 * <p>Thay thế cờ boolean {@code finalized} đơn lẻ của phiên bản cũ bằng vòng
 * đời 3 trạng thái rõ ràng, khớp với yêu cầu UI mới:
 *
 * <pre>
 *   NONE ────[upload file chấm công]──▶ UPLOADED ──[Tính lương]──▶ CALCULATED
 *                                           ▲                           │
 *                                           │                           │
 *                                           └──────[Mở lại]─────────────┘
 *                                                                       │
 *                                                                       ▼
 *                                                                   PUBLISHED
 *                                           ◀──────[Unpublic]──────────
 *                                           ▲
 *                                           │
 *                                           (nhân viên chỉ thấy phiếu lương
 *                                            khi trạng thái là PUBLISHED)
 * </pre>
 *
 * <h3>Quy tắc chuyển</h3>
 * <ul>
 *   <li>{@code NONE → UPLOADED}: tự động khi file chấm công đầu tiên được tải lên.</li>
 *   <li>{@code UPLOADED → CALCULATED}: bấm "Tính lương". Yêu cầu đã có file chấm công.</li>
 *   <li>{@code CALCULATED → UPLOADED}: bấm "Mở lại". Payslip và OT bị xoá.</li>
 *   <li>{@code CALCULATED → PUBLISHED}: bấm "Public". Nhân viên bắt đầu thấy phiếu lương.</li>
 *   <li>{@code PUBLISHED → CALCULATED}: bấm "Unpublic". Phiếu bị ẩn khỏi nhân viên.</li>
 *   <li>Không thể "Mở lại" trực tiếp khi đang PUBLISHED — phải Unpublic trước.</li>
 * </ul>
 *
 * <h3>Khoá upload</h3>
 * Khi trạng thái ≥ {@link #CALCULATED}, các file thưởng / lịch nghỉ / phụ cấp
 * KHÔNG được tải lên nữa. Phải Mở lại về {@link #UPLOADED} mới cho upload.
 */
public enum PayrollCalcStatus {

    /** Chưa có file chấm công — không có gì để làm. */
    NONE,

    /** Đã có file chấm công, có thể bấm "Tính lương". Cho phép upload thưởng/lịch nghỉ/phụ cấp. */
    UPLOADED,

    /** Đã tính lương; nội bộ xem được bảng lương; nhân viên chưa thấy. */
    CALCULATED,

    /** Đã Public; nhân viên vào Phiếu lương của mình xem được. */
    PUBLISHED;

    /** Có thể bấm "Tính lương" không. */
    public boolean canCalculate() {
        return this == UPLOADED;
    }

    /** Có thể bấm "Public" không. */
    public boolean canPublish() {
        return this == CALCULATED;
    }

    /** Có thể bấm "Unpublic" không. */
    public boolean canUnpublish() {
        return this == PUBLISHED;
    }

    /** Có thể bấm "Mở lại" không (chỉ cho phép từ CALCULATED). */
    public boolean canReopen() {
        return this == CALCULATED;
    }

    /** Có cho phép upload thưởng/lịch nghỉ/phụ cấp ở trạng thái này không. */
    public boolean canUploadAdjustments() {
        return this == NONE || this == UPLOADED;
    }

    /** Trạng thái có cho nhân viên xem phiếu lương không. */
    public boolean isPublishedToEmployees() {
        return this == PUBLISHED;
    }
}
