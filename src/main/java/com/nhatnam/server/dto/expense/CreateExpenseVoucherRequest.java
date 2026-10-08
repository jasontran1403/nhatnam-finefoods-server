package com.nhatnam.server.dto.expense;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateExpenseVoucherRequest {

    /** Tên đơn vị thi công / nhà cung cấp — có thể rỗng */
    private String vendorName;

    /**
     * ID nhà cung cấp (MaterialVendor) được chọn. Khi có giá trị, mỗi khoản chi
     * BẮT BUỘC chọn nhãn từ danh mục của NCC này (categoryId) — không gõ tự do.
     */
    private Long vendorId;

    @NotBlank(message = "Lý do chi là bắt buộc")
    private String reason;

    /**
     * Số phiếu chi — người dùng nhập. Nếu để trống, backend tự lấy số gợi ý =
     * số phiếu gần nhất + 1 (quay vòng về 1 khi đạt 15000). Cho phép trùng.
     */
    private String paymentNumber;

    /**
     * Kỳ chi phí — tháng mà khoản chi này được tính vào, định dạng "YYYY-MM".
     * Không bắt buộc — nếu để trống, backend tự tính mặc định = tháng trước
     * tháng hiện tại (VD: tạo phiếu tháng 6 → mặc định kỳ chi phí tháng 5).
     */
    private String expensePeriod;

    /**
     * Ngày chi cụ thể (epoch ms) khi người dùng chọn chế độ "Ngày".
     * <ul>
     *   <li>Nếu có giá trị → backend tự suy ra {@link #expensePeriod} = tháng của
     *       ngày này (bỏ qua expensePeriod client gửi lên, nếu có).</li>
     *   <li>Nếu null và expensePeriod cũng trống → mặc định = HÔM NAY.</li>
     *   <li>Nếu null nhưng có expensePeriod → tạo theo "Kỳ" (chỉ tính theo tháng).</li>
     * </ul>
     */
    private Long expenseDate;

    /**
     * Danh mục chi = KEY của VENDOR_TYPE_LABEL (VD "MATERIAL","ELECTRICITY").
     * Dùng cho luật duyệt. Nếu client không gửi, backend suy ra từ nhà cung cấp
     * (nếu có) hoặc để null (khi null coi như KHÔNG thuộc danh mục cho phép).
     */
    private String vendorType;

    /** Loại thanh toán: "CASH" | "BANK_TRANSFER". Mặc định CASH. */
    private String paymentType;

    /** Tên ngân hàng — bắt buộc khi paymentType = BANK_TRANSFER. */
    private String bankName;

    /** Mã tham chiếu giao dịch — bắt buộc khi paymentType = BANK_TRANSFER. */
    private String bankRef;

    /** Người yêu cầu — nếu null, mặc định là người tạo */
    private String requestedByName;
    private Long requestedById;

    @NotEmpty(message = "Phải có ít nhất 1 khoản chi")
    private List<ExpenseItemRequest> items;

    /** URLs ảnh chứng từ (đã upload trước) */
    private List<String> imageUrls;

    // ── ỨNG LƯƠNG — chỉ dùng khi vendorType = SALARY_ADVANCE ──────────────────

    /**
     * ID nhân viên được ứng lương.
     * Bắt buộc khi {@code vendorType = "SALARY_ADVANCE"}.
     */
    private Long salaryAdvanceUserId;

    /**
     * Tháng ứng lương — "YYYY-MM". Nếu null, server tự set = tháng hiện tại.
     */
    private String salaryAdvanceMonth;

    @Data
    public static class ExpenseItemRequest {
        /**
         * Tên khoản chi. Khi lập phiếu có chọn NCC (vendorId), server sẽ tự điền
         * tên này từ nhãn danh mục ({@link #categoryId}) — client không cần gửi.
         * Vẫn giữ để tương thích các luồng cũ (gõ tự do khi không có vendorId).
         */
        private String itemName;

        /** Nhãn khoản chi được chọn từ danh mục của NCC — bắt buộc khi có vendorId */
        private Long categoryId;

        @Positive(message = "Số tiền phải lớn hơn 0")
        private BigDecimal amount;

        private String note;
    }
}