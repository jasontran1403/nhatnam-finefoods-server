package com.nhatnam.server.dto.vendordebt;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO cho trang "Quản lý nhà cung cấp" (Owner/Admin):
 *  1) Danh sách NCC kèm công nợ + badge số ngày công nợ lâu nhất.
 *  2) Chi tiết 1 NCC: thông tin + lịch sử đặt hàng + phân tích giá 1 sản phẩm.
 */
public class SupplierMgmtDtos {

    // ─── 1a. Dòng NCC trong danh sách ─────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplierListItemDto {
        private Long vendorId;
        private String vendorName;
        private String vendorType;
        private String contactPerson;
        private String contactPhone;
        private BigDecimal totalDebt;        // tổng công nợ còn lại
        private Integer oldestDebtDays;      // số ngày công nợ lâu nhất (null nếu không nợ)
        private int unsettledLotCount;       // số lô còn công nợ
        private int orderCount;              // tổng số lần đặt hàng (mọi trạng thái)
    }

    // ─── 1b. Lô công nợ còn lại (badge click) ────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DebtLotDto {
        private Long requestVendorId;
        private String requestCode;
        private BigDecimal totalAmount;      // tổng của lô
        private BigDecimal paidAmount;       // đã thanh toán (0 nếu chưa trả)
        private BigDecimal remaining;        // còn lại
        private String settlementStatus;     // NONE (chưa trả) | PARTIAL | SETTLED
        private Long debtSince;
        private int debtDays;                // số ngày kể từ debtSince
    }

    // ─── 2a. Thông tin NCC (đầu trang chi tiết) ──────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplierInfoDto {
        private Long vendorId;
        private String vendorName;
        private String vendorType;
        private String contactPerson;
        private String contactPhone;
        private String address;
        private String taxCode;
        // Tổng hợp nhanh
        private BigDecimal totalDebt;
        private Integer oldestDebtDays;
        private int orderCount;
        private int distinctProductCount;
        private BigDecimal totalPurchased;   // tổng tiền đã mua (mọi lô đã có giá)
    }

    // ─── 2b. Lịch sử 1 lần đặt hàng ──────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderHistoryDto {
        private Long requestVendorId;
        private Long materialRequestId;
        private String requestCode;
        private Long orderedAt;
        private Long completedAt;
        private BigDecimal totalAmount;      // tổng đơn đặt hàng (phần của NCC này)
        private String paymentStatus;        // UNSET | PAID | DEBT
        private String settlementStatus;     // NONE | PARTIAL | SETTLED
        private List<OrderItemLineDto> items;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItemLineDto {
        private Long itemId;
        private String materialName;
        private String unit;
        private BigDecimal quantity;         // qtyReceived (rơi về qtyRequested nếu null)
        private BigDecimal unitPrice;
        private BigDecimal lineAmount;
    }

    // ─── 2c. Phân tích giá 1 sản phẩm (modal) ────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProductPriceStatsDto {
        private String productName;
        private String unit;
        private int purchaseCount;           // số lần đã mua (số điểm dữ liệu)

        // ── Các chỉ số giá ──
        private BigDecimal avgPrice;         // giá trung bình (mean) — bình quân gia quyền theo số lượng
        private BigDecimal simpleAvgPrice;   // trung bình cộng đơn thuần (mỗi lần 1 giá trị)
        private BigDecimal medianPrice;      // trung vị (p50)
        private BigDecimal minPrice;         // giá thấp nhất
        private Long minAt;                  // thời điểm giá thấp nhất
        private BigDecimal maxPrice;         // giá cao nhất
        private Long maxAt;                  // thời điểm giá cao nhất
        private BigDecimal latestPrice;      // giá gần nhất
        private Long latestAt;               // thời điểm gần nhất
        private BigDecimal firstPrice;       // giá lần đầu
        private Long firstAt;                // thời điểm lần đầu

        // ── Bổ trợ ──
        private BigDecimal totalQuantity;    // tổng số lượng đã mua
        private BigDecimal totalSpent;       // tổng tiền đã chi cho sản phẩm này
        private BigDecimal priceTrendPct;    // % thay đổi giá gần nhất so với lần đầu (âm = giảm)
        private BigDecimal volatilityPct;    // biên độ dao động = (max-min)/min * 100

        private List<PricePointDto> points;  // toàn bộ điểm giá theo thời gian (để vẽ mini chart)
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PricePointDto {
        private BigDecimal unitPrice;
        private BigDecimal quantity;
        private String unit;
        private Long at;
        private String requestCode;
    }

    // ─── 3. Danh mục khoản chi theo NCC (Owner quản lý) ───────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VendorExpenseCategoryDto {
        private Long id;
        private Long vendorId;
        private String name;
        private String description;
        private boolean active;
        private String createdByName;
        private Long createdAt;

        // ── Phân loại Dịch vụ / Đồ dùng tiêu hao (phiếu đặt Văn phòng phẩm) ──
        /** SERVICE | CONSUMABLE */
        private String categoryKind;
        /** Đơn vị tính — VD "Chai". Chỉ có ý nghĩa với CONSUMABLE. */
        private String unit;
        /** Quy cách — VD "4L/chai". Chỉ có ý nghĩa với CONSUMABLE. */
        private String specification;
        /** Vật dụng tồn kho được BE tự getOrCreate theo (tên, quy cách, ĐVT). */
        private Long supplyItemId;
    }

    // ── 4b. Cập nhật thông tin NCC ─────────────────────────────────────────────
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateVendorRequest {
        private String name;
        private String vendorType;      // MATERIAL, SERVICE, REPAIR, ...
        private String contactPerson;
        private String contactPhone;
        private String address;
        private String taxCode;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CategoryUpsertRequest {
        private String name;
        private String description;
        private Boolean active;

        /**
         * SERVICE (mặc định, giữ nguyên hành vi cũ) | CONSUMABLE.
         *
         * <p>Với CONSUMABLE, BE BẮT BUỘC có {@link #unit} + {@link #specification}
         * rồi tự chạy {@code SupplyItemService.getOrCreate(name, spec, unit)} để
         * gán {@code supplyItemId} — client KHÔNG cần (và không nên) tự gửi id đó,
         * trừ khi người dùng đã chọn từ autocomplete.
         */
        private String categoryKind;
        private String unit;
        private String specification;

        /**
         * Chỉ gửi khi người dùng CHỌN TỪ AUTOCOMPLETE — khi đó FE khoá 3 ô
         * tên/quy cách/ĐVT và gửi kèm id này để chắc chắn không tạo bản ghi mới.
         */
        private Long supplyItemId;
    }

    // ─── 5. Phân tích giá nguyên liệu — GỘP ĐA-NHÀ-CUNG-CẤP theo tên NL ────────
    /**
     * Phân tích giá của 1 nguyên liệu (theo TÊN), gộp dữ liệu từ TẤT CẢ nhà cung
     * cấp đã từng cung cấp nguyên liệu đó. Card giá thấp/cao nhất kèm tên NCC +
     * thời điểm mua; bảng lịch sử giá có cột NCC (sort ở FE, không phân trang).
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialPriceAnalysisDto {
        /** MATERIAL (mặc định) | EXPENSE — để FE đổi nhãn "giá mua" ↔ "số tiền chi" */
        private String kind;
        private String materialName;
        private String unit;
        private int purchaseCount;           // số lần đã mua (điểm dữ liệu)
        private int vendorCount;             // số NCC khác nhau đã cung cấp

        // ── Các chỉ số giá ──
        private BigDecimal avgPrice;         // bình quân gia quyền theo số lượng
        private BigDecimal simpleAvgPrice;   // trung bình cộng đơn thuần
        private BigDecimal medianPrice;      // trung vị (p50)

        private BigDecimal minPrice;         // giá thấp nhất
        private Long minAt;                  // thời điểm mua giá thấp nhất
        private String minVendorName;        // NCC bán giá thấp nhất

        private BigDecimal maxPrice;         // giá cao nhất
        private Long maxAt;                  // thời điểm mua giá cao nhất
        private String maxVendorName;        // NCC bán giá cao nhất

        private BigDecimal latestPrice;      // giá gần nhất
        private Long latestAt;
        private String latestVendorName;

        private BigDecimal firstPrice;       // giá lần đầu
        private Long firstAt;
        private String firstVendorName;

        /**
         * Giá của lần mua/chi LIỀN TRƯỚC lần gần nhất — mốc so sánh của
         * {@link #priceTrendPct}. Null khi mới chỉ có đúng 1 lần mua/chi.
         */
        private BigDecimal prevPrice;
        private Long prevAt;
        private String prevVendorName;

        // ── Bổ trợ ──
        private BigDecimal totalQuantity;
        private BigDecimal totalSpent;
        /** % thay đổi của giá GẦN NHẤT so với lần LIỀN TRƯỚC (âm = giảm). Null nếu chỉ có 1 lần. */
        private BigDecimal priceTrendPct;
        private BigDecimal volatilityPct;    // (max-min)/min * 100

        private List<MaterialPricePointDto> points; // toàn bộ điểm giá theo thời gian (tăng dần)
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialPricePointDto {
        private BigDecimal unitPrice;
        private BigDecimal quantity;
        private String unit;
        private Long at;
        private String requestCode;
        private String vendorName;           // tên NCC của lần mua này
        private Long vendorId;
    }

    // ─── 6. Phân tích DANH MỤC CHI (nguyên liệu + danh mục khoản chi NCC) ──────
    /**
     * Một dòng trong trang "Phân tích danh mục chi".
     *
     * Gộp 2 nguồn chi tiêu của nhà máy:
     *  • kind = MATERIAL — nguyên liệu đã mua qua phiếu đặt hàng
     *    ({@code MaterialRequestItem} đã có đơn giá). Giá = đơn giá / đvt.
     *  • kind = EXPENSE  — danh mục khoản chi của NCC
     *    ({@code VendorExpenseCategory} ↔ {@code ExpenseItem} trong phiếu chi ĐÃ DUYỆT).
     *    Giá = số tiền của 1 lần chi.
     *
     * Gộp theo TÊN (không phân biệt hoa/thường) trên MỌI nhà cung cấp.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExpenseCategorySummaryDto {
        /** MATERIAL | EXPENSE */
        private String kind;
        /** Tên nguyên liệu hoặc tên danh mục chi */
        private String name;
        /** Đvt của nguyên liệu (Kg, Lít...) — với EXPENSE là "lần" */
        private String unit;
        /** Số lần đã mua / đã chi */
        private int purchaseCount;
        /** Số NCC khác nhau */
        private int vendorCount;

        private BigDecimal minPrice;
        private Long minAt;
        private String minVendorName;

        private BigDecimal maxPrice;
        private Long maxAt;
        private String maxVendorName;

        private BigDecimal latestPrice;
        private Long latestAt;
        private String latestVendorName;

        /** Tổng tiền đã mua nguyên liệu / đã chi cho danh mục này */
        private BigDecimal totalSpent;
        /** Tổng số lượng đã mua (chỉ có ý nghĩa với MATERIAL) */
        private BigDecimal totalQuantity;
    }
}