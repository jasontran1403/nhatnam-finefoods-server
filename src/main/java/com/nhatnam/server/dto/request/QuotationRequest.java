package com.nhatnam.server.dto.request;

import lombok.Data;
import java.util.List;

/**
 * Request body cho endpoint POST /api/seller/quotations/export-pdf
 */
@Data
public class QuotationRequest {

    /**
     * Tên khách hàng (Kính gửi).
     * null hoặc blank → hiển thị "QUÝ KHÁCH HÀNG"
     */
    private String customerName;

    /**
     * Nội dung báo giá — VD: "SẢN PHẨM ICEHOT & RICH'S"
     * Hiển thị ở dòng "Nội dung: ..."
     */
    private String quotationContent;

    /** Danh sách sản phẩm cần đưa vào báo giá */
    private List<QuotationItem> items;

    @Data
    public static class QuotationItem {
        /** ID sản phẩm */
        private Long productId;

        /**
         * ID tier giá sỉ.
         * null = dùng basePrice (giá lẻ)
         */
        private Long tierId;

        /**
         * Tỷ suất VAT: 0 | 5 | 8 | 10 | 12
         */
        private int vatRate;

        /**
         * Loại VAT: "INCLUSIVE" (trong giá) | "EXCLUSIVE" (ngoài giá)
         */
        private String vatMode;
    }
}