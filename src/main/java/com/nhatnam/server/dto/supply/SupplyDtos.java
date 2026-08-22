package com.nhatnam.server.dto.supply;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO cho luồng "Phiếu đặt hàng Văn phòng phẩm / Đồ dùng".
 *
 * <p>Gom vào 1 file theo đúng quy ước sẵn có của project
 * (xem {@code MaterialRequestDtos}, {@code SupplierMgmtDtos}).
 */
public class SupplyDtos {

    // ══════════════════════════════════════════════════════════════════════════
    //  1) DANH MỤC VẬT DỤNG (SupplyItem)
    // ══════════════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyItemDto {
        private Long id;
        private String name;
        private String specification;
        private String unit;
        /** Tổng tồn trên TẤT CẢ các kho — chỉ để Owner nhìn nhanh khi merge. */
        private BigDecimal totalQuantity;
    }

    /** Kết quả autocomplete `GET /supply-items/suggest?q=` */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyItemSuggestDto {
        private Long id;
        private String name;
        private String specification;
        private String unit;
        /** Nhãn hiển thị gộp: "Nước rửa chén — 4L/chai (Chai)" */
        private String label;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  2) KHO VPP
    // ══════════════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyWarehouseDto {
        private Long id;
        private String name;
        private String address;
        private boolean active;
        /** true nếu user đang gọi được gán vào kho này (Owner luôn false + readOnly). */
        private boolean assigned;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyStockRowDto {
        private Long supplyItemId;
        private String name;
        private String specification;
        private String unit;
        private BigDecimal quantity;
        private Long updatedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyTransactionDto {
        private Long id;
        private Long warehouseId;
        private String warehouseName;
        private Long supplyItemId;
        private String name;
        private String unit;
        private String specification;
        private String type;            // IN | OUT
        private BigDecimal quantity;
        private BigDecimal balanceAfter;
        private String refType;
        private Long refId;
        private String note;
        private String performedByName;
        private Long createdAt;
    }

    /** Rút sử dụng — người được gán kho thực hiện. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class WithdrawRequest {
        private Long warehouseId;
        private List<WithdrawLine> lines;
        /** Lý do rút chung (nếu từng dòng không có note riêng). */
        private String note;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class WithdrawLine {
        private Long supplyItemId;
        private BigDecimal quantity;
        private String note;
    }

    /** Owner gán kho cho user. */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class AssignWarehouseRequest {
        private Long userId;
        private List<Long> warehouseIds;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UserWarehouseAssignmentDto {
        private Long userId;
        private String username;
        private String fullName;
        private String role;
        /**
         * Phòng ban / chức vụ — hiển thị dưới tên thay cho username.
         *
         * <p>Người quản lý nhận ra nhân viên qua bộ phận họ làm, không qua tên
         * đăng nhập; username chỉ có ý nghĩa với người cấp tài khoản.
         */
        private String department;
        private String position;
        private List<Long> warehouseIds;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  3) PHIẾU ĐẶT HÀNG VPP
    // ══════════════════════════════════════════════════════════════════════════

    // ── Bước 1: người tạo lập phiếu ──────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateSupplyOrderRequest {
        /** Kho nhận — bắt buộc, phải nằm trong danh sách kho được gán cho user. */
        private Long supplyWarehouseId;
        /** Deadline mong muốn (epoch ms) — tuỳ chọn. */
        private Long requiredBy;
        /** true = LƯU NHÁP (giữ NEW nhưng không bắn WS), false = TẠO PHIẾU (gửi kế toán). */
        private boolean draft;
        private List<SupplyOrderItemRequest> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SupplyOrderItemRequest {
        /** Có khi sửa phiếu nháp; null khi thêm dòng mới. */
        private Long id;
        /** NCC do người tạo chọn (được chọn TẤT CẢ NCC, không giới hạn NCC nguyên liệu). */
        private Long supplierId;
        /** Danh mục khoản chi của NCC đó — bắt buộc. */
        private Long expenseCategoryId;
        private BigDecimal quantity;
        private String note;
        private Integer sortOrder;
    }

    // ── Bước 2: SUPER_ACCOUNTANT xác nhận đặt hàng ───────────────────────────

    /**
     * CHỈ DUYỆT HẾT HOẶC TỪ CHỐI HẾT — không có duyệt một phần.
     * Mỗi phần tử {@code groups} là 1 NCC xuất hiện trong phiếu.
     */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ConfirmSupplyOrderRequest {
        private List<GroupConfirmRequest> groups;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GroupConfirmRequest {
        private Long supplierId;
        private Long expectedDeliveryAt;
        private String contactName;
        private String contactPhone;
        /**
         * ID các {@code MaterialRequestItem} thuộc NCC này. Cho phép kế toán
         * ĐỔI NCC của từng dòng so với lựa chọn ban đầu của người tạo.
         */
        private List<Long> itemIds;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RejectSupplyOrderRequest {
        /** Bắt buộc — REJECTED là terminal, người tạo cần biết lý do để lập phiếu mới. */
        private String reason;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExtendDeliveryRequest {
        private Long groupId;
        private Long newExpectedDeliveryAt;
        private String reason;
    }

    // ── Bước 3: người tạo nhận hàng ──────────────────────────────────────────

    /** Một ĐỢT nhận. Chỉ gửi các dòng thực giao trong đợt này (đừng gửi qty = 0). */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveSupplyReceiptRequest {
        private String notes;
        /** true = chỉ LƯU NHÁP đợt (chưa cộng kho); false = XÁC NHẬN NHẬN HÀNG. */
        private boolean draft;
        private List<SupplyReceiptLine> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SupplyReceiptLine {
        private Long itemId;
        private BigDecimal qty;
        /** true = chốt dòng này, không nhận thêm nữa (dù còn thiếu). */
        private boolean closeLine;
        private String note;
    }

    // ── Bước 4: SUPER_ACCOUNTANT tất toán ────────────────────────────────────

    /**
     * Tất toán theo TỪNG NHÓM NCC. Điều kiện mở khoá: TOÀN BỘ mặt hàng trong
     * phiếu đã được nhận (dù thiếu) — nghĩa là mọi dòng {@code receiveClosed}.
     */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SettleSupplyOrderRequest {
        private List<GroupSettleRequest> groups;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GroupSettleRequest {
        private Long groupId;
        private List<ItemPriceRequest> items;
        /** Thuế/phí nhiều dòng, label tự nhập. Phân bổ theo TỶ TRỌNG GIÁ TRỊ. */
        private List<FeeRequest> fees;
        /** PAY_NOW → tạo phiếu chi · DEBT → ghi công nợ NCC. */
        private String paymentMode;
        /** Chỉ dùng khi PAY_NOW: CASH | BANK_TRANSFER. */
        private String paymentType;
        private String bankName;
        private String bankRef;
        private List<String> imageUrls;
        private String reason;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ItemPriceRequest {
        private Long itemId;
        /** UNIT_PRICE (mặc định) | TOTAL */
        private String priceInputMode;
        /** Dùng khi mode = UNIT_PRICE. Tối đa 3 số thập phân. */
        private BigDecimal unitPrice;
        /** Dùng khi mode = TOTAL. Tối đa 3 số thập phân. */
        private BigDecimal totalAmount;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class FeeRequest {
        private String label;
        private BigDecimal amount;
    }

    // ── Response ─────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyOrderDto {
        private Long id;
        private String requestCode;
        private String status;             // NEW|ORDERED|PARTIALLY_RECEIVED|RECEIVED|COMPLETED|REJECTED
        private Long createdById;
        private String createdByName;
        private Long supplyWarehouseId;
        private String supplyWarehouseName;
        private Long requiredBy;
        private Long orderedAt;
        private Long receivedAt;
        private Long completedAt;
        private String handledByName;
        private String rejectReason;
        private BigDecimal grandTotal;
        private List<SupplyOrderItemDto> items;
        private List<SupplyOrderGroupDto> groups;
        private List<SupplyReceiptDto> receipts;
        private Long createdAt;
        private Long updatedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyOrderItemDto {
        private Long id;
        private Long supplierId;
        private String supplierName;
        private Long expenseCategoryId;
        private String categoryKind;       // SERVICE | CONSUMABLE
        private Long supplyItemId;
        private String itemName;
        private String unit;
        private String specification;
        private BigDecimal orderedQuantity;
        private BigDecimal receivedQuantity;
        private boolean receiveClosed;
        private String receiveStatus;      // PENDING|PARTIAL|FULFILLED|CLOSED_SHORT
        private String priceInputMode;
        private BigDecimal unitPrice;
        private BigDecimal totalAmount;
        private String note;
        private Long supplyGroupId;
        private Integer sortOrder;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyOrderGroupDto {
        private Long id;
        private String code;
        private Long supplierId;
        private String supplierName;
        private String status;             // ORDERED | RECEIVED | SETTLED
        private Long expectedDeliveryAt;
        private String contactName;
        private String contactPhone;
        private String paymentMode;        // PAY_NOW | DEBT
        private Long paymentVoucherId;
        private String paymentVoucherCode;
        private String paymentVoucherStatus;   // PENDING | APPROVED | REJECTED
        private Long supplierDebtId;
        private BigDecimal goodsAmount;
        private BigDecimal feeAmount;
        private BigDecimal totalAmount;
        private List<FeeDto> fees;
        private Long settledAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FeeDto {
        private Long id;
        private String label;
        private BigDecimal amount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyReceiptDto {
        private Long id;
        private Integer sequenceNo;
        private Long receivedAt;
        private String receivedByName;
        private String notes;
        private List<SupplyReceiptLineDto> items;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplyReceiptLineDto {
        private Long itemId;
        private String itemName;
        private String unit;
        private BigDecimal qty;
    }

    // ── Option cho form tạo phiếu ────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SupplierOptionDto {
        private Long id;
        private String name;
        private String contactPerson;
        private String contactPhone;
        private String vendorType;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExpenseCategoryOptionDto {
        private Long id;
        private String name;
        private String categoryKind;   // SERVICE | CONSUMABLE
        private String unit;
        private String specification;
        private Long supplyItemId;
    }
}
