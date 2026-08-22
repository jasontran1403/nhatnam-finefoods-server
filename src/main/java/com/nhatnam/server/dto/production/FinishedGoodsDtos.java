package com.nhatnam.server.dto.production;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTOs cho Kho thành phẩm (Issue #1 + #2).
 */
public class FinishedGoodsDtos {

    // ─── Hoàn thành mẻ — bổ sung ngày SX + HSD ─────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteBatchWithExpiryRequest {
        private BigDecimal actualOutputQty;
        private String notes;
        /** Ngày sản xuất của lô thành phẩm — bắt buộc */
        private Long manufactureDate;
        /** Hạn sử dụng của lô thành phẩm — bắt buộc */
        private Long expiryDate;
    }

    // ─── Lô thành phẩm (1 dòng trong kho) ───────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FinishedGoodsLotDto {
        private Long id;
        private String productName;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal initialQuantity;
        private Long manufactureDate;
        private Long expiryDate;
        private String batchCode;
        private Long factoryId;
        private String factoryName;

        // ── Giá vốn (chốt khi kho thành phẩm nhận hàng) ──
        /** Tổng giá vốn nguyên liệu của lô (đồng) */
        private BigDecimal totalCost;
        /** Giá vốn 1 đơn vị đóng gói (đ/túi, đã làm tròn lên) */
        private BigDecimal unitCost;
        /** Giá vốn 1 kg (đ/kg, đã làm tròn lên) */
        private BigDecimal unitCostPerKg;
        /** Trọng lượng thực cân khi nhận (kg) — mẫu số của giá vốn/kg */
        private BigDecimal netWeightKg;

        private Long createdAt;
    }

    // ─── Dòng tổng hợp theo Tên thành phẩm (UI chính) ──────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FinishedGoodsSummaryDto {
        private String productName;
        private String unit;
        private BigDecimal totalQuantity;
        /** Lô cận date gần nhất (nhỏ nhất expiryDate, còn hàng) */
        private Long nearestExpiryDate;
        /** Lô cận date xa nhất (lớn nhất expiryDate, còn hàng) */
        private Long farthestExpiryDate;
        private int lotCount;
        private List<FinishedGoodsLotDto> lots;
    }

    // ─── Xuất kho ────────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ExportFinishedGoodsRequest {
        private String productName;
        private BigDecimal quantity;
        private String reason; // bắt buộc
        /** Xưởng nguồn — bắt buộc (trừ tồn đúng kho thành phẩm của xưởng này) */
        private Long factoryId;
        /** Chứng từ đính kèm — danh sách URL ảnh, OPTIONAL */
        private List<String> documentImages;
    }

    // ─── Chuyển kho ──────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TransferFinishedGoodsRequest {
        private String productName;
        private BigDecimal quantity;
        /** ID kho đích (Warehouse — kho bán hàng hoặc trung chuyển) — bắt buộc */
        private Long targetWarehouseId;
        /** Xưởng nguồn — bắt buộc */
        private Long factoryId;
    }

    // ─── Kho đích (dropdown "Tên kho — Loại kho") ─────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TransferTargetDto {
        private Long id;
        private String name;
        /** SALE | TRANSIT */
        private String type;
        /** Nhãn tiếng Việt: "Kho bán" | "Trung chuyển" */
        private String typeLabel;
        private String address;
    }

    // ─── Thành phẩm chuyển được sang 1 kho đích (giao của 2 kho theo TÊN) ─────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TransferableProductDto {
        private String productName;
        private String unit;
        /** Tồn khả dụng ở kho thành phẩm của xưởng đang chọn */
        private BigDecimal availableQuantity;
        /** Ingredient tương ứng ở kho đích (để FE hiển thị/đối chiếu) */
        private Long targetIngredientId;
    }

    // ─── Lịch sử giao dịch ───────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FinishedGoodsTransactionDto {
        private Long id;
        private String type; // EXPORT | TRANSFER
        private String productName;
        private String unit;
        private BigDecimal quantity;
        private String reason;
        private List<String> documentImages;
        private Long factoryId;
        private String factoryName;
        private Long targetWarehouseId;
        private String targetWarehouseName;
        private String performedByName;
        private Long createdAt;
    }
}