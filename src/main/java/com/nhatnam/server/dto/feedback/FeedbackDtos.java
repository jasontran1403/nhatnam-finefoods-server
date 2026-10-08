package com.nhatnam.server.dto.feedback;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO cho tính năng FEEDBACK — trang tạo (SUPER_SELLER/SELLER/WAREHOUSE) và
 * trang xem list (OWNER/ADMIN).
 */
public final class FeedbackDtos {

    private FeedbackDtos() {}

    // ── Yêu cầu từ FE khi tạo feedback ───────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateFeedbackRequest {
        /** Mã đơn (không phải id) — user gõ tay vào ô input. */
        @NotBlank(message = "Vui lòng nhập mã đơn hàng")
        private String orderCode;

        /** Id sản phẩm trong đơn — có thể null nếu SP không có productId (SP cũ). */
        private Long productId;

        /** Tên SP — bắt buộc, dùng cho snapshot hiển thị. */
        @NotBlank(message = "Vui lòng chọn sản phẩm")
        private String productName;

        /**
         * Tên liên hệ — nếu để trống sẽ tự lấy từ customer của đơn ở BE.
         * FE nên gửi giá trị user thấy trong ô input (mặc định là customer, người
         * dùng có thể sửa).
         */
        @Size(max = 255)
        private String contactName;

        @Size(max = 32)
        private String contactPhone;

        @NotBlank(message = "Vui lòng nhập nội dung feedback")
        @Size(max = 5000, message = "Nội dung feedback quá dài")
        private String content;

        /**
         * Danh sách URL ảnh — do FE upload trước qua {@code POST /api/feedback/images},
         * lấy URL trả về rồi truyền lên đây khi submit form. Cách 2 bước tách để một
         * upload chậm không làm submit fail lại toàn bộ.
         */
        private List<String> imageUrls;
    }

    // ── Thông tin đơn — trả về khi FE tra mã đơn để dựng form ───────────────

    /**
     * Snapshot đơn hàng ĐỦ để dựng form feedback: tên KH, SĐT (placeholder cho
     * contact), và danh sách sản phẩm để chọn. Không bao gồm dữ liệu tài chính
     * (giá, VAT) vì feedback không liên quan tới đó.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderLookupDto {
        private Long orderId;
        private String orderCode;
        private String customerName;
        private String customerPhone;
        private List<OrderProductDto> products;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderProductDto {
        private Long productId;
        private String productName;
        private String unit;
        private BigDecimal quantity;
    }

    // ── Feedback đọc lại (cho list ở owner/admin) ────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedbackDto {
        private Long id;
        private Long createdAt;

        private String orderCode;
        private Long orderId;         // dùng cho deep-link mở đơn khi cần
        private String customerName;
        private String productName;
        private String content;

        // Contact snapshot lúc tạo — có thể khác customer của đơn (người sửa)
        private String contactName;
        private String contactPhone;

        // Người tạo feedback (seller/warehouse)
        private String createdByName;

        /** Danh sách URL ảnh (đã split từ cột TEXT trong DB). Không null — rỗng khi ko có. */
        private List<String> imageUrls;
    }

    /** Kết quả trang có phân trang cho endpoint list. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedbackPage {
        private List<FeedbackDto> content;
        private long totalElements;
        private int page;
        private int size;
    }
}
