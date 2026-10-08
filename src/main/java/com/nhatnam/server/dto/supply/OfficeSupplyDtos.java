package com.nhatnam.server.dto.supply;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO cho luồng Đăng ký / Đặt hàng Văn phòng phẩm (VPP) của nhân viên.
 */
public class OfficeSupplyDtos {

    // ──────────────────────────────────────────────────────────────────────────
    // VẬT DỤNG (SupplyItem) — dùng cho dropdown chọn khi đăng ký
    // ──────────────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OfficeItemDto {
        private Long id;
        private String name;
        private String specification;
        private String unit;
        /** Số lần mua (tính trên kho đang chọn). */
        private Long orderCount;
        /** Ngày mua gần nhất (ms). */
        private Long lastOrderedAt;
        /** Tổng số lượng đã mua. */
        private BigDecimal totalQuantity;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PHIẾU ĐĂNG KÝ CỦA NHÂN VIÊN
    // ──────────────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MyRequestDto {
        private Long warehouseId;
        private String warehouseName;
        private List<RequestItemDto> items;
        private Long updatedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RequestItemDto {
        private Long supplyItemId;
        private String name;
        private String specification;
        private String unit;
        private BigDecimal quantity;
        private String note;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SAVE / UPDATE PHIẾU ĐĂNG KÝ (request body từ FE)
    // ──────────────────────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveRequestBody {
        private Long warehouseId;
        private List<RequestItemLine> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RequestItemLine {
        private Long supplyItemId;
        private BigDecimal quantity;
        private String note;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TỔNG HỢP ĐẶT HÀNG (OWNER xem trước khi bấm "Đặt hàng")
    // ──────────────────────────────────────────────────────────────────────────

    /** Tổng hợp trước khi đặt: sheet 1 (tổng) + sheet 2 (chi tiết). */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderSummaryDto {
        private Long warehouseId;
        private String warehouseName;
        /** Sheet 1: mỗi vật dụng 1 dòng, tổng số lượng. */
        private List<SummaryRowDto> summary;
        /** Sheet 2: mỗi nhân viên × mỗi vật dụng 1 dòng. */
        private List<DetailRowDto> detail;
        /** Số nhân viên đã đăng ký. */
        private int employeeCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SummaryRowDto {
        private int stt;
        /** Id vật dụng — FE cần để gửi lại khi nhập giá. */
        private Long supplyItemId;
        private String name;
        private String specification;
        private String unit;
        private BigDecimal totalQuantity;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DetailRowDto {
        private int stt;
        private Long userId;
        private String userFullName;
        private String userPosition;
        /** Bộ phận nhân viên — thêm để hiển thị trên phiếu in đặt hàng. */
        private String userDepartment;
        private String itemName;
        private String unit;
        private BigDecimal quantity;
        private String note;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ĐƠN HÀNG ĐÃ ĐẶT (lịch sử)
    // ──────────────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderHistoryDto {
        private Long id;
        private Long warehouseId;
        private String warehouseName;
        private Long placedAt;
        private String placedByName;
        /** Số vật dụng khác nhau trong đơn. */
        private int itemTypeCount;
        /** Tổng số lượng (tất cả vật dụng). */
        private BigDecimal totalQuantity;
        /** Tổng tiền hàng (chưa cộng phí). */
        private BigDecimal subtotalAmount;
        /** Tổng phí phân bổ cho đơn. */
        private BigDecimal feesAmount;
        /** subtotal + fees. */
        private BigDecimal totalAmount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderDetailDto {
        private Long id;
        private Long warehouseId;
        private String warehouseName;
        private Long placedAt;
        private String placedByName;
        /** Danh sách nhân viên và từng vật dụng của lần đặt này. */
        private List<OrderDetailItemDto> items;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderDetailItemDto {
        private Long userId;
        private String userFullName;
        private String userPosition;
        private Long supplyItemId;
        private String itemName;
        private String unit;
        private String specification;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private String note;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BÁO CÁO VẬT DỤNG (trang báo cáo chính)
    // ──────────────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ItemReportRowDto {
        private Long supplyItemId;
        private String name;
        private String specification;
        private String unit;
        /** Tổng số lần đặt mua (số đơn hàng có vật dụng này). */
        private Long orderCount;
        /** Ngày mua gần nhất (ms). */
        private Long lastOrderedAt;
        /** Tổng số lượng đã mua. */
        private BigDecimal totalQuantity;
        /**
         * Khoảng thời gian TRUNG BÌNH giữa 2 lần đặt liên tiếp, đơn vị NGÀY.
         *
         * <p>Tính = (khoảng cách từ lần đặt sớm nhất → lần đặt gần nhất, tính bằng ngày)
         *          / (orderCount − 1). Null khi orderCount ≤ 1 (không đủ 2 mốc).
         */
        private Double avgIntervalDays;
        /**
         * Số lượng TRUNG BÌNH mỗi lần đặt = totalQuantity / orderCount.
         * Null khi orderCount = 0.
         */
        private BigDecimal avgQuantityPerOrder;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ADMIN — DANH MỤC VẬT DỤNG (create/update, xem tồn tổng)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Body cho POST/PUT create/update vật dụng. Owner chỉ nhập tên + ĐVT (spec optional);
     * BE tự chuẩn hoá & tra bộ ba (tên, quy cách, ĐVT) chống trùng.
     */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveItemBody {
        private String name;
        private String unit;
        private String specification; // optional
    }

    /**
     * Item trong trang "Danh sách văn phòng phẩm" — bảng để Owner add/edit.
     * KHÔNG lấy tồn hiện tại từ bảng {@code supply_stock} vì trong luồng đăng ký
     * VPP không có kho vật lý (đặt xong reset về 0); chỉ trả tổng đã đặt qua lịch sử.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AdminItemDto {
        private Long id;
        private String name;
        private String specification;
        private String unit;
        private Long createdAt;
        private Long updatedAt;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ĐẶT HÀNG — PAYLOAD GIÁ (Owner nhập trước khi hoàn tất)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Body cho POST /admin/place-order:
     * <ul>
     *   <li>{@code items}: thành tiền (lineAmount) từng vật dụng — là TỔNG TIỀN HÀNG của
     *       dòng đó, chưa cộng phí. BE sẽ chia cho tổng số lượng để ra đơn giá.</li>
     *   <li>{@code fees}: các khoản phí [{name, amount}] — phân bổ theo tỉ trọng
     *       {@code lineAmount}.</li>
     * </ul>
     */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PlaceOrderBody {
        private List<ItemPriceLine> items;
        private List<FeeLine> fees;
        /**
         * Giảm giá cho CẢ đơn, không âm. BE phân bổ theo tỉ trọng
         * {@code lineAmount} rồi TRỪ vào đơn giá — ngược dấu với phí.
         */
        private BigDecimal discount;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ItemPriceLine {
        private Long supplyItemId;
        /** Tổng tiền của vật dụng này — ví dụ 100 cuốn tổng 1.000.000. */
        private BigDecimal lineAmount;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class FeeLine {
        private String name;
        private BigDecimal amount;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // LỊCH SỬ GIÁ — biểu đồ biến động + thống kê
    // ──────────────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PriceHistoryDto {
        private Long supplyItemId;
        private String name;
        private String specification;
        private String unit;
        /** Các lần đặt hàng CÓ GIÁ — mới nhất ở cuối (dễ vẽ chart timeline). */
        private List<PriceHistoryPoint> points;
        /** Thống kê — null nếu không có lần đặt nào có đơn giá. */
        private PriceStats stats;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PriceHistoryPoint {
        private Long orderId;
        /** ms */
        private Long placedAt;
        private BigDecimal quantity;
        /** Đơn giá đã gồm phí phân bổ, theo đơn vị tính nhỏ nhất. */
        private BigDecimal unitPrice;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PriceStats {
        private BigDecimal minPrice;
        private Long minAt;
        private BigDecimal minQty;

        private BigDecimal maxPrice;
        private Long maxAt;
        private BigDecimal maxQty;

        private BigDecimal avgPrice;
        private BigDecimal medianPrice;

        private BigDecimal lastPrice;
        private Long lastAt;
        private BigDecimal lastQty;

        /** Tổng số lần đặt có đơn giá. */
        private int sampleCount;
    }
}