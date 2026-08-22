package com.nhatnam.server.dto.forecast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** DTO cho màn hình "Dự báo đặt hàng" của SELLER / SUPER_SELLER. */
public final class OrderForecastDtos {

    private OrderForecastDtos() {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForecastRow {
        private Long customerId;
        private String customerCode;
        private String customerType;      // COMPANY | RETAIL
        private String displayName;       // tên công ty hoặc tên cá nhân
        private String contactName;
        private String phone;

        /** Số lần đặt hàng đã dùng để tính chu kỳ. */
        private int orderCount;

        /**
         * CHU KỲ ĐẶT HÀNG TRUNG BÌNH (ngày, đã LÀM TRÒN XUỐNG).
         *
         * <p>Ví dụ khách đặt 15/7, 21/7, 22/7, 2/8, 2/8 ⇒ các khoảng 6, 1, 11, 0 ngày.
         * Hai đơn cùng ngày được tính là chu kỳ TỐI THIỂU 1 ngày theo yêu cầu nghiệp vụ,
         * nên chuỗi trở thành 6, 1, 11, 1 ⇒ trung bình 19/4 = 4,75 ⇒ 4 ngày.
         */
        private int avgCycleDays;

        /** Ngày đặt gần nhất (epoch millis). */
        private Long lastOrderAt;

        /** Ngày dự kiến khách cần đặt lại = lastOrderAt + avgCycleDays. */
        private Long predictedNextOrderAt;

        /**
         * SỐ NGÀY QUÁ HẠN so với ngày dự kiến.
         * {@code 0} = đúng hôm nay tới hạn, {@code > 0} = đã trễ, {@code < 0} = chưa tới.
         */
        private int overdueDays;

        /** true = tới/quá hạn ⇒ hiển thị là "cần liên hệ chào hàng". */
        private boolean dueNow;

        // ── Trạng thái liên hệ trong NGÀY HÔM NAY ────────────────────────────
        /** Seller đã bấm "đã gọi" cho khách này hôm nay chưa. */
        private boolean contactedToday;
        /** Thời điểm bấm (epoch millis) — FE hiển thị "đã liên hệ 08/08 lúc 14:32". */
        private Long contactedAt;
        private String contactedBy;
        private String contactNote;

        // ── Thông tin chăm sóc kèm theo ─────────────────────────────────────
        private Long birthday;
        private Long storeOpeningDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForecastResponse {
        /** Ngày tính toán (yyyy-MM-dd, giờ VN) — FE hiển thị để biết dữ liệu của ngày nào. */
        private String asOfDate;
        /** Số khách đang tới hạn cần gọi. */
        private int dueCount;
        /** Trong số đó, đã liên hệ hôm nay bao nhiêu. */
        private int contactedCount;
        private List<ForecastRow> rows;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarkContactedRequest {
        private String note;
    }
}
