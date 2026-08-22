package com.nhatnam.server.dto.production;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

public class MaterialRequestDtos {

    // ─── Request DTOs ─────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateMaterialRequestRequest {
        private Long requiredBy;                    // nullable — deadline xử lý
        private String type;                        // "SELLER" cho phiếu SUPER_SELLER; null/"FACTORY" = luồng gốc
        private Long productionFactoryId;           // xưởng của phiếu (bắt buộc với phiếu FACTORY)
        private List<ItemRequest> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ItemRequest {
        private String materialName;
        private String unit;                        // Kg/Gr/Lít/Túi/Hộp/Bịch/Thùng/Chai/Lon/Can
        private BigDecimal qtyRequested;
        private int sortOrder;
        private Long ingredientId;                  // bắt buộc với phiếu SELLER (liên kết Ingredient)
        private Long factoryMaterialId;             // liên kết FactoryMaterial (phiếu FACTORY)
        private String orderUnitType;               // "STORAGE" hoặc "ORDER" — đvt khi đặt (default: STORAGE)
    }

    /** Kế toán xác nhận đặt hàng */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ConfirmOrderRequest {
        private Long estimatedDelivery;             // thời gian giao dự kiến
        private List<VendorRequest> vendors;        // danh sách NCC (bắt buộc >= 1)
        /**
         * Gán NCC cho từng dòng nguyên liệu — bắt buộc, mỗi nguyên liệu trong phiếu
         * phải được gán cho đúng 1 NCC trong {@link #vendors}.
         */
        private List<ItemVendorAssignment> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ItemVendorAssignment {
        /** id của MaterialRequestItem trong phiếu */
        private Long itemId;
        /**
         * Vị trí (index, bắt đầu từ 0) của NCC trong danh sách {@link ConfirmOrderRequest#vendors}
         * được gửi cùng request này — dùng vì lúc gửi, các NCC trong phiếu chưa được lưu nên
         * chưa có id thật.
         */
        private Integer vendorIndex;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class VendorRequest {
        /** Bắt buộc — chọn từ danh mục NCC có sẵn (MaterialVendor). Không cho nhập tự do. */
        private Long vendorId;
        /** Các field dưới đây không còn được dùng để lưu (server tự lấy snapshot từ vendorId) — giữ lại cho tương thích payload cũ. */
        private String vendorName;
        private String contactPerson;
        private String contactPhone;
        private int sortOrder;
    }

    /**
     * Nhân viên xưởng LƯU MỘT ĐỢT NHẬN (nhận lẻ / giao bù).
     *
     * <p>Chỉ gửi các dòng THỰC GIAO trong đợt này. Dòng không giao đợt này thì
     * KHÔNG gửi lên (đừng gửi qty = 0) — gửi lên sẽ bị bỏ qua.
     */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveReceiptRequest {
        private String notes;                       // ghi chú riêng của đợt
        private List<ReceiptItemRequest> items;     // các dòng thực giao đợt này
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ReceiptItemRequest {
        private Long itemId;
        /** SL nhận TRONG ĐỢT NÀY (không phải tổng cộng dồn). Phải > 0. */
        private BigDecimal qty;
        private Long expiryDate;                    // HSD riêng của đợt (nullable → suy từ shelfLifeDays)
        private Long warehouseId;                   // SELLER: kho nhận (bắt buộc với phiếu SELLER)
        /**
         * "STORAGE" | "ORDER". Đợt ĐẦU TIÊN của nguyên liệu sẽ khoá giá trị này;
         * các đợt bù sau gửi khác → server báo lỗi (tránh cộng dồn Kg + Thùng).
         */
        private String receivedUnitType;
        /** Các lần cân của đợt này. Nullable. */
        private List<BigDecimal> weighingLogs;
    }

    /**
     * Nhân viên xưởng CHỐT "đã giao xong" — bước cuối, khoá phiếu, chuyển sang
     * trạng thái RECEIVED để kế toán hoàn thành.
     */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class FinishReceivingRequest {
        /** Bắt buộc nếu còn dòng nhận THIẾU so với số đặt. */
        private String shortageReason;
    }

    /** [LEGACY] Nhận hàng 1 lần duy nhất — giữ cho tương thích payload cũ. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ReceiveRequest {
        private String notes;
        private List<ReceiveItemRequest> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ReceiveItemRequest {
        private Long itemId;
        private BigDecimal qtyReceived;
        private Long expiryDate;                    // nullable (SELLER: null → mặc định +5 năm từ ngày nhận)
        private Long warehouseId;                   // SELLER: kho nhận lô hàng (bắt buộc với phiếu SELLER)
        private String receivedUnitType;            // "STORAGE" hoặc "ORDER" — đvt khi nhận (default: STORAGE)
        /** Danh sách các lần cân (nếu nhập theo nhiều lần cộng dồn), JSON array số. Nullable. */
        private List<BigDecimal> weighingLogs;
    }

    /** SUPER_ACCOUNTANT gia hạn ngày giao hàng */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DeliveryExtendRequest {
        private Long newDeliveryDate;               // ngày giao hàng mới (epoch ms)
        private String reason;                      // lý do gia hạn
    }

    /**
     * Kế toán hoàn thành phiếu: nhập breakdown giá/phí (xem {@link CostEntryRequest}),
     * hệ thống tự tính ngược ra đơn giá/đơn vị cho từng nguyên liệu dựa trên
     * qtyReceived thực tế. Sau đó với mỗi NCC trong phiếu, chọn Thanh toán ngay
     * (kèm chứng từ) hoặc Công nợ.
     */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteRequest {
        /** Gán NCC cho từng dòng nguyên liệu (bắt buộc) */
        private List<CompleteItemVendorAssignment> itemVendors;
        /** Breakdown giá nguyên liệu + phí/thuế tuỳ chỉnh — xem {@link CostEntryRequest} */
        private List<CostEntryRequest> costEntries;
        /** Quyết định thanh toán/công nợ cho từng NCC trong phiếu */
        private List<VendorPaymentRequest> vendorPayments;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteItemVendorAssignment {
        /** id của MaterialRequestItem trong phiếu */
        private Long itemId;
        /** id của MaterialRequestVendor (NCC đã tồn tại trong phiếu này, gán lúc Xác nhận đặt hàng hoặc đổi lại ở đây) */
        private Long requestVendorId;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CostEntryRequest {
        /** "MATERIAL" (giá nguyên liệu riêng — luôn áp dụng cho đúng 1 item) hoặc "CUSTOM" (phí/thuế tuỳ chỉnh) */
        private String type;
        /** Tên hiển thị — bắt buộc cho CUSTOM (VD: "Thuế hải quan"). MATERIAL có thể để trống, server tự đặt "Giá nguyên liệu" */
        private String label;
        /** Tổng số tiền của dòng giá/phí này (tối đa 3 số thập phân) */
        private BigDecimal amount;
        /**
         * CHỈ dùng cho MATERIAL: đơn giá 1 đơn vị (tối đa 3 số thập phân).
         *
         * <p>UI có 2 cách nhập giá:
         * <ul>
         *   <li>ĐƠN GIÁ (mặc định) → FE gửi {@code unitPrice}, server tự tính
         *       {@code amount = unitPrice × qtyReceived}.</li>
         *   <li>TỔNG TIỀN → FE gửi {@code amount}, server tự tính
         *       {@code unitPrice = amount / qtyReceived} (làm tròn 3 số thập phân).</li>
         * </ul>
         * Nếu gửi cả hai thì {@code unitPrice} được ưu tiên.
         */
        private BigDecimal unitPrice;
        /** Danh sách MaterialRequestItem.id mà dòng này áp dụng. MATERIAL luôn có đúng 1 phần tử. */
        private List<Long> itemIds;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class VendorPaymentRequest {
        /** id của MaterialRequestVendor (dòng NCC trong phiếu này) */
        private Long requestVendorId;
        /** "PAID" hoặc "DEBT" */
        private String action;
        /** Bắt buộc nếu action = PAID: "BANK" hoặc "CASH" */
        private String paymentMethod;
        /** Thông tin thanh toán (số TK/nội dung CK, hoặc ghi chú tiền mặt) */
        private String paymentInfo;
        /** Ảnh chứng từ — bắt buộc ít nhất 1 ảnh nếu action = PAID */
        private List<String> proofImages;
        /** Mục 7 — Thông tin phiếu nhập từ NCC (bắt buộc) */
        private String importReceiptInfo;
        /** Mục 7 — Serial / IMEI (bắt buộc) */
        private String serialImei;
    }

    // ─── Response DTOs ────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialRequestDto {
        private Long id;
        private String requestCode;
        private Long createdById;
        private String createdByName;
        private Long requiredBy;
        private String status;                      // NEW/ORDERED/RECEIVED/COMPLETED
        private String type;                        // FACTORY | SELLER
        private Long productionFactoryId;
        private String productionFactoryName;
        private Long orderedAt;
        private Long estimatedDelivery;
        private Long deliveryExtendedTo;            // ngày giao hàng mới (sau gia hạn)
        private String deliveryExtendReason;        // lý do gia hạn
        private String handledByName;
        private Long receivedAt;
        private String receiveNotes;
        private String shortageReason;              // lý do chốt khi còn thiếu
        private Long completedAt;
        private int itemCount;
        private BigDecimal totalAmount;             // tổng tiền cả phiếu (chỉ có sau khi hoàn thành)
        private List<MaterialRequestItemDto> items;
        private List<MaterialRequestVendorDto> vendors;
        private List<MaterialRequestReceiptDto> receipts;   // lịch sử các đợt nhận hàng
        private List<MaterialRequestCostEntryDto> costEntries;  // breakdown giá/phí đã nhập lúc hoàn thành (nếu có)
        private Long createdAt;
        private Long updatedAt;
    }

    /** Một đợt nhận hàng (NCC giao lẻ / giao bù). */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialRequestReceiptDto {
        private Long id;
        private Integer sequenceNo;                 // "Đợt 1", "Đợt 2"…
        private Long receivedAt;
        private String receivedByName;
        private String notes;
        private List<MaterialRequestReceiptItemDto> items;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialRequestReceiptItemDto {
        private Long id;
        private Long itemId;
        private String materialName;                // snapshot cho FE khỏi phải join
        private BigDecimal qty;                     // SL nhận trong ĐỢT NÀY
        private String receivedUnitType;
        private String receivedUnit;
        private BigDecimal stockQty;                // SL đã cộng vào kho (đvt lưu kho)
        private Long expiryDate;
        private List<BigDecimal> weighingLogs;
        /** NCC cung cấp dòng này — để FE nhóm hiển thị đợt theo NCC. */
        private Long requestVendorId;
        private String vendorName;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialRequestCostEntryDto {
        private Long id;
        private String type;          // MATERIAL | CUSTOM
        private String label;
        private BigDecimal amount;
        private List<Long> itemIds;   // các MaterialRequestItem.id mà dòng này áp dụng
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialRequestItemDto {
        private Long id;
        private String materialName;
        private String unit;
        private BigDecimal qtyRequested;
        private BigDecimal qtyReceived;      // TỔNG cộng dồn qua các đợt
        private BigDecimal qtyOutstanding;   // còn lại = max(0, qtyRequested - qtyReceived)
        private String receiveStatus;        // PENDING | PARTIAL | FULFILLED | CLOSED_SHORT
        private Long expiryDate;
        private int sortOrder;
        private Long ingredientId;    // SELLER: nguyên liệu liên kết
        private Long warehouseId;     // SELLER: kho nhận
        private Long factoryMaterialId;      // FACTORY: liên kết FactoryMaterial
        private String orderUnitType;        // "STORAGE" | "ORDER"
        private String receivedUnitType;     // "STORAGE" | "ORDER"
        private String receivedUnit;         // đvt thực nhận (chuỗi, VD: "Can")
        private BigDecimal conversionRatio;  // tỷ lệ quy đổi snapshot
        /** Danh sách các lần cân đã dùng để cộng ra qtyReceived (nếu có). Null nếu nhập trực tiếp. */
        private List<BigDecimal> weighingLogs;
        /** id của MaterialRequestVendor (NCC trong phiếu) cung cấp dòng này — null nếu chưa nhập giá */
        private Long requestVendorId;
        private String suppliedByVendorName;
        private BigDecimal unitPrice;
        private BigDecimal lineAmount;
        /** Breakdown các khoản cấu thành lineAmount của dòng này (giá nguyên liệu + phần phí/thuế được phân bổ vào) — chỉ có sau khi hoàn thành */
        private List<ItemCostBreakdownEntryDto> costBreakdown;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ItemCostBreakdownEntryDto {
        private String label;
        private BigDecimal amount;   // phần của khoản này được phân bổ vào dòng nguyên liệu này
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialRequestVendorDto {
        private Long id;
        private Long vendorId;
        private String vendorName;
        private String contactPerson;
        private String contactPhone;
        private int sortOrder;
        private String paymentStatus;          // UNSET/PAID/DEBT
        private BigDecimal totalAmount;
        private BigDecimal paidAmount;
        private BigDecimal debtRemaining;       // totalAmount - paidAmount (chỉ có ý nghĩa khi DEBT)
        private String debtSettlementStatus;    // NONE/PARTIAL/SETTLED
        private Long debtSince;
        private String paymentMethod;
        private String paymentInfo;
        private List<String> paymentProofImages;
        private String importReceiptInfo;       // Mục 7 — thông tin phiếu nhập từ NCC
        private String serialImei;              // Mục 7 — serial/imei
    }

    // ─── Kho nguyên liệu ─────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryStockSummaryDto {
        private String materialName;
        private String unit;
        private BigDecimal totalQty;
        private String categoryName;                // danh mục chung
        private String subCategoryName;             // danh mục riêng
        private Long categoryId;
        private Long subCategoryId;
        private List<FactoryStockLotDto> lots;      // chi tiết từng lô FIFO
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryStockLotDto {
        private Long id;
        private BigDecimal quantity;
        private BigDecimal initialQuantity;
        private Long expiryDate;
        private Boolean nearExpiry;                 // hết hạn trong 30 ngày
        private Long createdAt;
        private String materialRequestCode;         // mã phiếu đặt hàng nguồn (snapshot)
        private Long orderedAt;                      // ngày đặt hàng (snapshot orderedAt của phiếu)
        private String importReceiptInfo;           // Mục 7 — snapshot thông tin phiếu nhập NCC
        private String serialImei;                  // Mục 7 — snapshot serial/imei
    }

    // ── Tùy chọn cho phiếu SELLER (SUPER_SELLER) ──────────────────────────────
    /** Nguyên liệu đặt được (đã lọc theo category cho phép). */
    public record SellerIngredientOption(Long id, String name, String unit, String itemCode) {}
    /** Kho để chọn nơi nhận lô hàng. */
    public record WarehouseOption(Long id, String name) {}
}