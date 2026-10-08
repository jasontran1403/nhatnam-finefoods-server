package com.nhatnam.server.entity.tools;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity @Table(name = "tool_invoice_detail")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ToolInvoiceDetail {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Integer stt;
    @Column(name = "order_number", length = 50)
    private String orderNumber;
    @Column(precision = 15, scale = 2)
    private BigDecimal amount;          // số tiền user nhập (chỉ groupLeader có giá trị)
    @Column(name = "invoice_date", length = 30)
    private String invoiceDate;
    @Column(name = "error_note", length = 500)
    private String errorNote;
    // ── Kết quả lookup ──────────────────────────────────────────────────
    @Column(name = "f_inv", length = 50)
    private String fInv;
    @Column(name = "f_inv7", length = 20)
    private String fInv7;
    @Column(name = "customer_name", length = 300)
    private String customerName;
    @Column(name = "ma_khach_hang", length = 100)
    private String maKhachHang;
    @Column(name = "ten_khach_hang_full", length = 500)
    private String tenKhachHangFull;
    @Column(name = "value_from_tracking", length = 100)
    private String value;               // Value từ Theo dõi Invoice
    // ── Nhóm đơn (cùng 1 lần nhập) ────────────────────────────────────
    @Column(name = "group_id", length = 50)
    private String groupId;             // UUID nhóm
    @Column(name = "group_leader")
    @Builder.Default
    private boolean groupLeader = true; // true = đơn đầu nhóm, mang tổng tiền
    @Column(name = "created_at")
    private Long createdAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}