package com.nhatnam.server.dto.tools;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

public class ToolDtos {

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class InvoiceDetailReq {
        private String orderNumber;
        private BigDecimal amount;
        private String invoiceDate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LookupResultDto {
        private String orderNumber;
        private String value;         // value từ tracking
        private String customerName;  // Customer từ tracking (raw)
        private String tenKhachHangFull; // Tên đầy đủ từ bảng KH
        private String finv;          // F.Inv raw
        private String finv7;         // padded
        private String errorNote;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InvoiceDetailDto {
        private Long id;
        private Integer stt;
        private String orderNumber;
        private BigDecimal amount;          // số tiền user nhập (tổng nhóm)
        private String invoiceDate;
        private String errorNote;
        // ── Kết quả lookup từ Theo dõi Invoice ──
        private String fInv;               // F.Inv gốc
        private String fInv7;              // F.Inv đã format 7 chữ số
        private String customerName;       // tên KH từ tracking/sales
        private String maKhachHang;        // mã KH từ bảng Khách hàng
        private String tenKhachHangFull;   // tên KH đầy đủ từ bảng Khách hàng
        private String value;              // Value từ Theo dõi Invoice (số tiền đơn)
        private String groupId;            // ID nhóm — đơn cùng lần nhập có cùng groupId
        private boolean groupLeader;       // true nếu là đơn đầu của nhóm (mang tổng tiền)
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ReceiptOutputDto {
        private Long id;
        private Integer rowIndex;
        private String ngayHachToan;
        private String ngayChungTu;
        private String soChungTu;
        private String maDoiTuong;
        private String tenDoiTuong;
        private String diaChi;
        /** Cột H — Lý do nộp (text cố định: "Phiếu thu tiền mặt khách hàng"). */
        private String lyDoNop;
        private String dienGiaiLyDoNop;
        private String loaiTien;
        private String dienGiai;
        private String tkNo;
        private String tkCo;
        private String soTien;
        private String doiTuong;
        private String srcOrder;
        private String srcFInv;
        private String errorNote;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RenumberReq {
        private String oldPrefix;
        private String newPrefix;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ConfigDto {
        private String key;
        private String value;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ImportResult {
        private int imported;
        private int skipped;
        private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AllDataDto {
        private List<ReceiptOutputDto> receipts;
        private List<InvoiceDetailDto> invoiceDetails;
        private int trackingCount;
        private int salesCount;
        private int customerCount;
        private String currentDocNumber;
        private String doneUpToRow;
    }
}