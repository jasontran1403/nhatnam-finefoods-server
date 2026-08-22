package com.nhatnam.server.dto.vendordebt;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

public class VendorDebtDtos {

    // ─── Danh sách công nợ theo NCC ───────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VendorDebtSummaryDto {
        private Long vendorId;
        private String vendorName;
        private String vendorType;          // MATERIAL/MACHINE/.../OTHER — chỉ để hiển thị
        private String contactPerson;
        private String contactPhone;
        private BigDecimal totalDebt;        // tổng công nợ còn lại (chưa trả hết)
        private int unsettledRequestCount;   // số phiếu đặt hàng còn công nợ
        private Long oldestDebtSince;        // thời điểm công nợ lâu nhất (để filter/sort)
    }

    // ─── Lịch sử công nợ chi tiết của 1 NCC (theo từng phiếu đặt hàng) ───────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VendorDebtDetailDto {
        private Long requestVendorId;        // id của MaterialRequestVendor
        private Long materialRequestId;
        private String requestCode;
        private BigDecimal totalAmount;
        private BigDecimal paidAmount;
        private BigDecimal remaining;
        private String debtSettlementStatus; // NONE/PARTIAL/SETTLED
        private Long debtSince;
        private Long completedAt;
    }

    // ─── Phiếu chi trả công nợ NCC ────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateVendorExpenseRequest {
        private Long vendorId;
        /** true = thanh toán hết công nợ hiện tại (server tự tính số tiền) */
        private boolean fullSettlement;
        /** Bắt buộc nếu fullSettlement = false */
        private BigDecimal amount;
        private String note;
        /** Ảnh chứng từ thanh toán — bắt buộc, ít nhất 1 ảnh */
        private List<String> proofImages;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VendorExpenseVoucherDto {
        private Long id;
        private String voucherCode;
        private Long vendorId;
        private String vendorName;
        private BigDecimal totalAmount;
        private boolean fullSettlement;
        private String note;
        private List<String> proofImages;
        private String createdByName;
        private Long createdAt;
        private List<AllocationDto> allocations;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AllocationDto {
        private Long materialRequestVendorId;
        private String requestCode;
        private BigDecimal amount;
        private BigDecimal remainingAfter;
    }
}
