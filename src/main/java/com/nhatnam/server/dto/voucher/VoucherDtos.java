package com.nhatnam.server.dto.voucher;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/** Gom toàn bộ DTO của module voucher vào một file cho dễ tra. */
public final class VoucherDtos {

    private VoucherDtos() {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VoucherDto {
        private Long id;
        private String code;
        private String title;

        private Long customerId;
        private String customerName;      // tên hiển thị (công ty hoặc cá nhân)
        private String customerPhone;
        private String customerType;      // COMPANY | RETAIL

        private Long amount;
        private Long usedAmount;
        private Long remaining;

        private Long validFrom;
        private Long validTo;

        /** Trạng thái lưu ở DB. */
        private String status;
        /**
         * Trạng thái HIỆU LỰC tại thời điểm gọi API (ACTIVE/USED/EXPIRED/CANCELLED).
         * FE nên hiển thị field này, còn {@link #status} chỉ dùng khi cần biết
         * voucher có bị thu hồi thủ công hay không.
         */
        private String effectiveStatus;

        /** Số ngày còn lại tới hạn; âm = đã quá hạn. */
        private Long daysLeft;

        private String reason;            // BIRTHDAY | STORE_OPENING | PROMOTION | OTHER
        private String applyScope;        // ALL | CATEGORY | PRODUCT

        private Set<Long> categoryIds;
        private Set<Long> productIds;
        /** Tên danh mục / sản phẩm đã resolve — FE khỏi phải gọi thêm API để hiển thị. */
        private List<NamedRef> categories;
        private List<NamedRef> products;

        private String note;
        private String createdByName;
        private Long createdAt;
        private Long updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NamedRef {
        private Long id;
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateVoucherRequest {

        @NotNull(message = "Vui lòng chọn khách hàng")
        private Long customerId;

        private String title;

        @NotNull(message = "Vui lòng nhập hạn mức")
        @Min(value = 1, message = "Hạn mức phải lớn hơn 0")
        private Long amount;

        @NotNull(message = "Vui lòng chọn ngày bắt đầu")
        private Long validFrom;

        @NotNull(message = "Vui lòng chọn ngày hết hạn")
        private Long validTo;

        /** BIRTHDAY | STORE_OPENING | PROMOTION | OTHER — mặc định OTHER. */
        private String reason;

        /** ALL | CATEGORY | PRODUCT — mặc định ALL. */
        private String applyScope;

        private Set<Long> categoryIds;
        private Set<Long> productIds;

        private String note;
    }

    /**
     * Cập nhật voucher. Mọi field đều nullable = "giữ nguyên", trừ hai tập
     * {@code categoryIds}/{@code productIds}: gửi mảng RỖNG nghĩa là xoá hết điều kiện.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateVoucherRequest {
        private Long customerId;
        private String title;
        private Long amount;
        private Long validFrom;
        private Long validTo;
        private String reason;
        private String applyScope;
        private Set<Long> categoryIds;
        private Set<Long> productIds;
        private String note;
        /** ACTIVE | CANCELLED — dùng để thu hồi / khôi phục voucher. */
        private String status;
    }
}
