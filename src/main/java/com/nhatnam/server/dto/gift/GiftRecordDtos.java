package com.nhatnam.server.dto.gift;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO cho trang QUẢN LÝ QUÀ TẶNG (OWNER / ADMIN / SUPER_ACCOUNTANT).
 *
 * <p>Trang này gộp HAI nguồn quà tặng trong hệ thống:
 * <ol>
 *   <li>ĐƠN HÀNG có sản phẩm khuyến mãi — {@code OrderItem} có {@code notes}
 *       bắt đầu bằng {@code "[KM]"} (được set khi seller đánh dấu isPromo lúc tạo đơn).</li>
 *   <li>PHIẾU TẶNG QUÀ (voucher quà tặng SẢN PHẨM) — {@code GiftOrder} đã được duyệt,
 *       tức status ∈ {@code {APPROVED, DELIVERING, COMPLETED}}.</li>
 * </ol>
 *
 * <p>Mỗi bản ghi {@link GiftRecordDto} là MỘT DÒNG hiển thị trên UI: 1 sản phẩm quà
 * tặng của 1 khách. Nếu một đơn/phiếu có nhiều sản phẩm quà tặng thì BE trả nhiều
 * dòng — flatten giúp search theo tên sản phẩm ra đúng dòng.
 */
public final class GiftRecordDtos {

    private GiftRecordDtos() {}

    /** Nguồn của bản ghi — để FE render badge / icon khác nhau. */
    public enum Source {
        /** Đơn hàng có sản phẩm khuyến mãi ([KM]). */
        ORDER,
        /** Phiếu tặng quà (GiftOrder) đã được duyệt. */
        GIFT_ORDER
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GiftRecordDto {

        /** Nguồn — để FE hiện icon 🛒 / 🎁 và điều hướng khi bấm xem chi tiết. */
        private Source source;

        /** Id của bản ghi gốc (Order.id hoặc GiftOrder.id) — FE dùng để deep link. */
        private Long sourceId;

        /** Mã đơn / mã phiếu (Order.orderCode hoặc GiftOrder.code). */
        private String code;

        /**
         * Thời gian tạo (epoch ms).
         * - ORDER: {@code Order.createdAt}
         * - GIFT_ORDER: {@code GiftOrder.createdAt}
         */
        private Long createdAt;

        // ── Khách ────────────────────────────────────────────────────────────
        private Long customerId;
        private String customerName;

        // ── Người xử lý (tạo đơn / phiếu) ────────────────────────────────────
        /**
         * Tên NGƯỜI TẠO đơn hàng / phiếu voucher quà tặng — tức là user đăng nhập
         * và bấm tạo. LƯU Ý: KHÔNG dùng {@code Order.orderedByName} vì trường đó là
         * "tên người đặt" do seller ghi trên form (có thể là tên KH, người đại diện,
         * hoặc "Tam" như trong ví dụ NĐ-05364) — không phản ánh ai thực sự thao tác
         * trên hệ thống.
         * <ul>
         *   <li>ORDER: {@code Order.user.fullName}</li>
         *   <li>GIFT_ORDER: {@code GiftOrder.createdBy.fullName}</li>
         * </ul>
         */
        private String handlerName;

        /** Id user tạo — dùng cho dropdown filter theo người tạo (không hiển thị). */
        private Long handlerId;

        // ── Danh sách sản phẩm quà tặng trong bản ghi ────────────────────────
        // 1 đơn/phiếu có N sản phẩm KM → items chứa N phần tử. FE hiển thị số lượng
        // sản phẩm trên card, click card mở modal hiện chi tiết items.
        private List<GiftRecordItemDto> items;

        /** Tổng số sản phẩm quà tặng — trùng {@code items.size()}, tách ra để FE
         *  không phải tính lại nhiều nơi và tránh nhầm khi items có thể null. */
        private int itemCount;

        // ── Ngữ cảnh phụ, hiển thị trong modal chi tiết ─────────────────────
        private String warehouseName;

        /**
         * Ghi chú CHUNG cho toàn phiếu — chỉ có ý nghĩa với GIFT_ORDER (voucher tặng
         * quà có 1 note cho cả phiếu). Với ORDER, mỗi item có note riêng nằm ở
         * {@link GiftRecordItemDto#note}. Trường này khi đó = null.
         */
        private String note;

        /** Trạng thái GiftOrder — chỉ có nghĩa khi source = GIFT_ORDER. */
        private String giftOrderStatus;
    }

    /**
     * Một sản phẩm quà tặng bên trong một {@link GiftRecordDto}. Đơn ORDER dùng
     * nhiều dòng này (mỗi item KM một dòng); GIFT_ORDER cũng vậy (mỗi GiftOrderItem
     * một dòng).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GiftRecordItemDto {
        private String productName;
        private String unit;
        private BigDecimal quantity;
        /**
         * Ghi chú KM cho item — với ORDER là phần sau tiền tố "[KM]" của
         * {@code OrderItem.notes}. Với GIFT_ORDER hiện KHÔNG có (item không có
         * note riêng — note nằm ở phiếu).
         */
        private String note;
    }

    /**
     * Người tạo — cho dropdown filter. Chỉ liệt kê những user thực sự đã tạo đơn có
     * sản phẩm KM hoặc đã tạo phiếu quà tặng đã duyệt (rút từ dữ liệu, không phải toàn
     * bộ user role SELLER) — filter sẽ không có option chết.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HandlerOption {
        private Long id;
        private String name;
    }

    /**
     * Kết quả trang có phân trang cho endpoint {@code /api/admin/gift-management}.
     * Không dùng {@code Page<T>} của Spring vì list được gộp ở tầng application
     * (từ 2 truy vấn khác nhau) — không phải một truy vấn JPA duy nhất.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GiftRecordPage {
        private List<GiftRecordDto> content;
        private long totalElements;
        private int page;
        private int size;
    }
}
