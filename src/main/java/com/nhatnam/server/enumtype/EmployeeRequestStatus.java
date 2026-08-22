package com.nhatnam.server.enumtype;

/**
 * TRẠNG THÁI DUYỆT ĐƠN của nhân viên.
 *
 * <p>Ứng đúng 3 thao tác OWNER có trên panel "Phiếu nghỉ":
 * <pre>
 *   Thao tác 1 — Duyệt         → APPROVED_PAID   (có phép, hưởng đủ công)
 *                              → APPROVED_UNPAID (không phép, công ngày đó = 0)
 *   Thao tác 2 — Duyệt & trừ công → APPROVED_DEDUCTED (kèm số công bị trừ 0.01–1)
 *   Thao tác 3 — Từ chối       → REJECTED (kèm lý do bắt buộc)
 * </pre>
 *
 * <p>Đơn {@code PENDING} KHÔNG có hiệu lực gì khi tính công — xử lý y hệt đơn bị
 * từ chối. Nếu không như vậy thì nhân viên chỉ cần tạo đơn là đã tự cho mình
 * nghỉ, OWNER duyệt hay không cũng thế.
 */
public enum EmployeeRequestStatus {

    /** Chờ OWNER duyệt — chưa tác động tới bảng chấm công. */
    PENDING("Chờ duyệt"),

    /** Duyệt CÓ PHÉP — ngày đó hưởng đủ công và đủ phụ cấp cơm. */
    APPROVED_PAID("Đã duyệt - có lương"),

    /** Duyệt KHÔNG PHÉP — công ngày đó = 0, không có phụ cấp cơm. */
    APPROVED_UNPAID("Đã duyệt - không lương"),

    /** Duyệt nhưng TRỪ BỚT công — số công trừ nằm ở {@code deductedDays}. */
    APPROVED_DEDUCTED("Đã duyệt - trừ công"),

    /** Từ chối — tính công theo dữ liệu máy chấm công như bình thường. */
    REJECTED("Từ chối");

    private final String label;

    EmployeeRequestStatus(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Đơn đã được OWNER xử lý (không còn nằm trong hàng chờ). */
    public boolean isDecided() { return this != PENDING; }

    /**
     * Đơn CÓ HIỆU LỰC khi tính công.
     * Chỉ 3 nhánh duyệt; {@code PENDING} và {@code REJECTED} bị bỏ qua hoàn toàn.
     */
    public boolean isEffective() {
        return this == APPROVED_PAID || this == APPROVED_UNPAID || this == APPROVED_DEDUCTED;
    }

    /** Đơn được hưởng lương / phụ cấp cơm cho ngày nghỉ. */
    public boolean isPaid() {
        return this == APPROVED_PAID || this == APPROVED_DEDUCTED;
    }
}