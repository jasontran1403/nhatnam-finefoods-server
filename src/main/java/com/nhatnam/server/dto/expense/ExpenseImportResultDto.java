package com.nhatnam.server.dto.expense;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Kết quả nhập phiếu chi hàng loạt từ file Excel.
 *
 * <p>Mỗi <b>nhóm</b> dòng (cùng "Mã phiếu (nhóm)") tạo thành MỘT phiếu chi. Backend
 * gọi lại đúng luồng {@code create()} cho từng nhóm nên quy tắc duyệt giữ nguyên.
 */
@Data
@Builder
public class ExpenseImportResultDto {

    /** Tổng số phiếu (nhóm) tìm thấy trong file. */
    private int totalVouchers;

    /** Số phiếu tạo thành công. */
    private int created;

    /** Số phiếu bị bỏ qua do lỗi. */
    private int failed;

    /** Mã phiếu chi đã tạo (voucherCode) — để client hiển thị/đối chiếu. */
    private List<String> createdCodes;

    /** Danh sách lỗi theo từng nhóm phiếu (đọc được cho người dùng). */
    private List<VoucherError> errors;

    @Data
    @Builder
    public static class VoucherError {
        /** Giá trị cột "Mã phiếu (nhóm)" của nhóm bị lỗi. */
        private String groupKey;
        /** Số dòng Excel (1-based) của dòng đầu nhóm — giúp người dùng dò nhanh. */
        private int excelRow;
        /** Mô tả lỗi. */
        private String message;
    }
}
