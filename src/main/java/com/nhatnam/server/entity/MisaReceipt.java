package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "misa_receipt")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MisaReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** UUID gửi lên MISA (org_refid) */
    @Column(name = "misa_ref_id", length = 200)
    private String misaRefId;

    /** Mã phiếu thu hiển thị */
    @Column(name = "misa_receipt_code", length = 100)
    private String misaReceiptCode;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** CASH hoặc BANK_TRANSFER */
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "bank_transaction_ref", length = 200)
    private String bankTransactionRef;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** JSON payload đã gửi lên MISA (để review/debug) */
    @Column(name = "request_payload", columnDefinition = "LONGTEXT")
    private String requestPayload;

    /** JSON response từ MISA */
    @Column(name = "response_payload", columnDefinition = "LONGTEXT")
    private String responsePayload;

    /** true = đã gửi thành công lên MISA */
    @Builder.Default
    @Column(name = "synced")
    private Boolean synced = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}