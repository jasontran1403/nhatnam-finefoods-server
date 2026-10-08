package com.nhatnam.server.entity.tools;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "tool_receipt_output")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ToolReceiptOutput {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "row_index") private Integer rowIndex;
    private String ngayHachToan;
    private String ngayChungTu;
    @Column(length = 30) private String soChungTu;
    @Column(length = 100) private String maDoiTuong;
    @Column(length = 300) private String tenDoiTuong;
    @Column(length = 500) private String diaChi;
    /** Cột H — Lý do nộp (text cố định: "Phiếu thu tiền mặt khách hàng"). */
    @Column(name = "ly_do_nop", length = 300) private String lyDoNop;
    @Column(length = 500) private String dienGiaiLyDoNop;
    @Column(length = 10) private String loaiTien;
    @Column(length = 500) private String dienGiai;
    @Column(length = 10) private String tkNo;
    @Column(length = 10) private String tkCo;
    @Column(length = 30) private String soTien;
    @Column(length = 100) private String doiTuong;
    @Column(length = 30) private String srcOrder;
    @Column(length = 30) private String srcFInv;
    @Column(length = 500) private String errorNote;
    @Column(name = "created_at") private Long createdAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
