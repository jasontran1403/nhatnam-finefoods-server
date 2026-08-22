package com.nhatnam.server.dto.expense;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Kết quả duyệt / từ chối hàng loạt — báo rõ phiếu nào thành công, phiếu nào lỗi. */
@Data
@Builder
public class BulkExpenseActionResultDto {

    /** Tổng số phiếu được gửi lên. */
    private int total;

    /** Số phiếu xử lý thành công. */
    private int succeeded;

    /** Số phiếu bị bỏ qua do lỗi. */
    private int failed;

    /** Chi tiết từng phiếu, giữ đúng thứ tự client gửi lên. */
    private List<ItemResult> results;

    @Data
    @Builder
    public static class ItemResult {
        private Long id;
        /** Mã phiếu — null nếu không tìm thấy phiếu. */
        private String voucherCode;
        private boolean success;
        /** Mô tả lỗi khi {@code success = false}. */
        private String message;
    }
}