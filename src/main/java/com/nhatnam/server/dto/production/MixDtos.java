package com.nhatnam.server.dto.production;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTOs cho MIX GIA VỊ (Mục 4).
 *
 * <p>Đầu vào: nhiều nguyên liệu của kho xưởng (kể cả loại mixable, nhưng KHÁC sản
 * phẩm đầu ra). Đầu ra: 1 nguyên liệu isMixable=true. Trộn xong tạo 2 phiếu:
 *   - phiếu NHẬP kho: sản phẩm đầu ra (1 lô mới, HSD + số lượng do người nhập).
 *   - phiếu XUẤT kho sản xuất: các nguyên liệu đầu vào (trừ FIFO nhiều lô).
 */
public class MixDtos {

    // ─── Kiểm tra tồn trước khi trộn ─────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MixCheckRequest {
        private Long factoryId;
        private List<InputLine> inputs;

        @Data @NoArgsConstructor @AllArgsConstructor
        public static class InputLine {
            private String materialName;
            private String unit;
            private BigDecimal quantity;   // số lượng cần dùng
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MixCheckResult {
        private boolean sufficient;          // đủ tồn cho tất cả đầu vào?
        private List<InputStatus> inputs;
        private BigDecimal totalInputCost;   // tổng giá vốn đầu vào (nếu đủ)

        @Data @Builder @NoArgsConstructor @AllArgsConstructor
        public static class InputStatus {
            private String materialName;
            private String unit;
            private BigDecimal required;
            private BigDecimal available;
            private boolean enough;
        }
    }

    // ─── Thực hiện trộn ──────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MixExecuteRequest {
        private Long factoryId;
        /** Sản phẩm đầu ra (phải là nguyên liệu isMixable=true của xưởng) */
        private String outputMaterialName;
        private String outputUnit;
        /** Số lượng mix được (có thể lẻ tới 3 số) */
        private BigDecimal outputQuantity;
        /** HSD sản phẩm đầu ra (ms) — người dùng chọn datepicker */
        private Long outputExpiryDate;
        private List<MixCheckRequest.InputLine> inputs;
    }
}
