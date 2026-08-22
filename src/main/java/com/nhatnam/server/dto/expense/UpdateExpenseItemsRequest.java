package com.nhatnam.server.dto.expense;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sửa DANH SÁCH khoản chi của một phiếu chi đã tồn tại.
 *
 * <p><b>Ngữ nghĩa THAY THẾ TOÀN BỘ:</b> {@code items} là danh sách khoản chi SAU KHI SỬA.
 * <ul>
 *   <li>Phần tử có {@code id} khớp khoản chi hiện có → CẬP NHẬT khoản đó.</li>
 *   <li>Phần tử {@code id = null} → THÊM khoản chi mới.</li>
 *   <li>Khoản chi hiện có nhưng KHÔNG xuất hiện trong {@code items} → XOÁ khỏi phiếu.</li>
 * </ul>
 *
 * <p>Quy tắc quyền (xem {@code ExpenseVoucherServiceImpl#updateItems}):
 * <ul>
 *   <li>Phiếu ĐANG CHỜ DUYỆT — ACCOUNTANT / SUPER_ACCOUNTANT / OWNER / ADMIN đều được
 *       sửa nhãn, số tiền, thêm và xoá khoản chi.</li>
 *   <li>Phiếu ĐÃ DUYỆT — chỉ OWNER / ADMIN. Kế toán chỉ được sửa lý do chi
 *       ({@code PATCH /{id}/reason}).</li>
 *   <li>Phiếu ĐÃ TỪ CHỐI — không ai được sửa.</li>
 * </ul>
 *
 * <p>Sau khi sửa, backend TÍNH LẠI cấp duyệt ({@code approverScope}) theo tổng tiền mới:
 * nếu tổng tiền tụt xuống dưới ngưỡng và danh mục nằm trong tập Owner cho phép thì
 * SUPER_ACCOUNTANT được duyệt phiếu; ngược lại phải OWNER/ADMIN duyệt.
 */
@Data
public class UpdateExpenseItemsRequest {

    @NotEmpty(message = "Phiếu chi phải còn ít nhất 1 khoản chi")
    private List<ItemPayload> items;

    @Data
    public static class ItemPayload {
        /** ID khoản chi hiện có cần cập nhật. Null = thêm khoản chi mới. */
        private Long id;

        /**
         * Nhãn khoản chi — BẮT BUỘC, phải chọn từ danh mục đang bật
         * ({@link com.nhatnam.server.entity.VendorExpenseCategory}). Không cho gõ tự do
         * khi sửa; {@code itemName} được server snapshot lại theo tên của nhãn.
         */
        private Long categoryId;

        /** Số tiền — BẮT BUỘC, phải lớn hơn 0. */
        private BigDecimal amount;

        /** Ghi chú — tuỳ chọn. */
        private String note;
    }
}