package com.nhatnam.server.dto.expense;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * Cấu hình duyệt phiếu chi (OWNER quản lý). Dùng chung cho GET (trả về) và PUT
 * (cập nhật). Với PUT chỉ cần thresholdAmount + allowedCategories.
 */
@Data
public class ExpenseApprovalConfigDto {
    /** Ngưỡng số tiền — phiếu < ngưỡng thì SUPER_ACCOUNTANT mới đủ điều kiện duyệt. */
    private BigDecimal thresholdAmount;

    /** Danh sách key danh mục (VENDOR_TYPE_LABEL) SUPER_ACCOUNTANT được duyệt. */
    private List<String> allowedCategories;

    private Long updatedAt;
    private String updatedByName;

    // ── Chỉ có giá trị trong RESPONSE của PUT (cập nhật cấu hình) ────────────
    // Cho biết cấu hình mới đã kéo bao nhiêu phiếu ĐANG CHỜ DUYỆT sang cấp duyệt khác,
    // để giao diện báo lại cho người dùng thay vì im lặng đổi dữ liệu.

    /** Số phiếu chờ duyệt chuyển sang "Kế toán trưởng duyệt được". */
    private Integer rescopedToSuperAccountant;

    /** Số phiếu chờ duyệt chuyển về "cần Chủ/Quản trị duyệt". */
    private Integer rescopedToOwner;
}