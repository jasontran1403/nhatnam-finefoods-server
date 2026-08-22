package com.nhatnam.server.dto.expense;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Duyệt / từ chối NHIỀU phiếu chi trong một lần bấm (OWNER, ADMIN).
 *
 * <p>Danh sách {@code ids} do client gom lại — người dùng có thể tick phiếu ở nhiều
 * trang khác nhau rồi thao tác một lượt, nên backend chỉ nhận id thuần, không phụ
 * thuộc phân trang.
 *
 * <p>Mỗi phiếu được xử lý ĐỘC LẬP: một phiếu lỗi (đã duyệt rồi, không đủ quyền…)
 * không làm hỏng các phiếu còn lại — xem {@link BulkExpenseActionResultDto}.
 */
@Data
public class BulkExpenseActionRequest {

    @NotEmpty(message = "Chưa chọn phiếu chi nào")
    private List<Long> ids;

    /** Ghi chú khi duyệt — tuỳ chọn. */
    private String note;

    /** Lý do từ chối — BẮT BUỘC khi gọi bulk-reject. */
    private String reason;
}