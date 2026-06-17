package com.nhatnam.server.dto;

import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tất cả Request/Response DTO cho module Warehouse gộp trong 1 file.
 * Item request được flatten ra top-level static để wildcard import hoạt động đúng.
 */
public class WarehouseDTO {

    @Data public static class CreateWarehouseRequest {
        private String name;
        private String address;
        private Warehouse.WarehouseType type;
    }

    // ── Nhập kho ────────────────────────────────────────────────────────────
    @Data public static class ImportRequest {
        private Long warehouseId;
        private String referenceCode;
        private String note;
        private List<String> imageUrls;
        private List<ImportItemRequest> items;
    }

    @Data public static class ImportItemRequest {
        private Long ingredientId;
        private BigDecimal quantity;
        private LocalDate expiryDate;
        /** Giá vốn đơn vị khi nhập (optional) */
        private BigDecimal costPrice;
    }

    // ── Xuất kho ────────────────────────────────────────────────────────────
    @Data public static class ExportRequest {
        private Long warehouseId;
        private String reason;
        private String note;
        private List<String> imageUrls;
        private List<ExportItemRequest> items;
    }

    @Data public static class ExportItemRequest {
        private Long ingredientId;
        private BigDecimal quantity;
    }

    // ── Chuyển kho ──────────────────────────────────────────────────────────
    @Data public static class TransferRequest {
        private Long fromWarehouseId;
        private Long toWarehouseId;
        private String note;
        private List<String> imageUrls;
        private List<TransferItemRequest> items;
    }

    @Data public static class TransferItemRequest {
        private Long ingredientId;
        private BigDecimal quantity;
    }

    // ── Điều chỉnh ──────────────────────────────────────────────────────────
    @Data public static class AdjustRequest {
        private Long warehouseId;
        private String reason;
        private String note;
        private List<String> imageUrls;
        private List<AdjustItemRequest> items;
    }

    @Data public static class AdjustItemRequest {
        private Long ingredientId;
        private BigDecimal physicalQty;
    }

    // ── Response ────────────────────────────────────────────────────────────
    @Data public static class WarehouseResponse {
        private Long id;
        private String name;
        private String address;
        private Warehouse.WarehouseType type;
        private boolean active;
    }

    @Data public static class StockResponse {
        private Long ingredientId;
        private String ingredientName;
        private String unit;
        private String imageUrl;
        private BigDecimal stockQuantity;
        private List<ExpiryInfo> expiryList;
    }

    @Data public static class ExpiryInfo {
        private Long id;
        private LocalDate expiryDate;
        private BigDecimal quantity;
    }

    @Data public static class ReceiptResponse {
        private Long id;
        private String receiptCode;
        private ReceiptType receiptType;
        private String warehouseName;
        private Long warehouseId;
        private String partnerWarehouseName;
        private Long partnerWarehouseId;
        private String referenceCode;
        private String reason;
        private String note;
        private List<String> imageUrls;
        private String createdByName;
        private Long createdAt;
        private List<ReceiptItemResponse> items;
    }

    @Data public static class ReceiptItemResponse {
        private Long ingredientId;
        private String ingredientName;
        private String unit;
        private String imageUrl;
        private BigDecimal quantity;
        private BigDecimal quantityBefore;
        private BigDecimal quantityAfter;
        private BigDecimal difference;
        private BigDecimal physicalQty;
        private String adjustResult;
        private LocalDate expiryDate;
    }

    @Data public static class TransferResponse {
        private ReceiptResponse outReceipt;
        private ReceiptResponse inReceipt;
    }
}