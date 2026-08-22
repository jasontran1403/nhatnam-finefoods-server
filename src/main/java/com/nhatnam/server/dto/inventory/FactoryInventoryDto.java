package com.nhatnam.server.dto.inventory;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tồn kho theo XƯỞNG cho trang Kho hàng của Owner (bảng riêng, tách khỏi báo cáo
 * dòng chảy theo nguyên liệu). Mỗi xưởng có kho nguyên liệu + kho thành phẩm.
 */
public class FactoryInventoryDto {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryBlock {
        private Long factoryId;
        private String factoryName;
        /** Tồn kho nguyên liệu xưởng (gộp theo tên||đơn vị) */
        private List<StockRow> materials;
        /** Tồn kho thành phẩm xưởng (gộp theo tên||đơn vị) */
        private List<StockRow> finishedGoods;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockRow {
        private String name;
        private String unit;
        private BigDecimal quantity;
        private Integer lotCount;
    }
}
