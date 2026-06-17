package com.nhatnam.server.dto.production;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

public class MaterialRequestDtos {

    // ─── Request DTOs ─────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateMaterialRequestRequest {
        private Long requiredBy;                    // nullable — deadline xử lý
        private List<ItemRequest> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ItemRequest {
        private String materialName;
        private String unit;                        // Kg/Gr/Lít/Túi/Hộp/Bịch/Thùng/Chai/Lon/Can
        private BigDecimal qtyRequested;
        private int sortOrder;
    }

    /** Kế toán xác nhận đặt hàng */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ConfirmOrderRequest {
        private Long estimatedDelivery;             // thời gian giao dự kiến
        private List<VendorRequest> vendors;        // danh sách NCC (optional)
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class VendorRequest {
        private Long vendorId;                      // nullable nếu tạo NCC mới
        private String vendorName;
        private String contactPerson;
        private String contactPhone;
        private int sortOrder;
    }

    /** Nhân viên nhập thực nhận */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ReceiveRequest {
        private String notes;
        private List<ReceiveItemRequest> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ReceiveItemRequest {
        private Long itemId;
        private BigDecimal qtyReceived;
        private Long expiryDate;                    // nullable
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
        private Long orderedAt;
        private Long estimatedDelivery;
        private String handledByName;
        private Long receivedAt;
        private String receiveNotes;
        private Long completedAt;
        private int itemCount;
        private List<MaterialRequestItemDto> items;
        private List<MaterialRequestVendorDto> vendors;
        private Long createdAt;
        private Long updatedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialRequestItemDto {
        private Long id;
        private String materialName;
        private String unit;
        private BigDecimal qtyRequested;
        private BigDecimal qtyReceived;
        private Long expiryDate;
        private int sortOrder;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MaterialRequestVendorDto {
        private Long id;
        private Long vendorId;
        private String vendorName;
        private String contactPerson;
        private String contactPhone;
        private int sortOrder;
    }

    // ─── Kho nguyên liệu ─────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FactoryStockSummaryDto {
        private String materialName;
        private String unit;
        private BigDecimal totalQty;
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
    }
}
