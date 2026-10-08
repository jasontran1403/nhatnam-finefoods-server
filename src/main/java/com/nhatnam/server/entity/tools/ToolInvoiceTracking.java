package com.nhatnam.server.entity.tools;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "tool_invoice_tracking")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ToolInvoiceTracking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "invoice_date", length = 30) private String invoiceDate;
    @Column(length = 50) private String invoice;
    @Column(length = 300) private String customer;
    @Column(length = 50) private String value;
    @Column(name = "f_inv", length = 50) private String fInv;
    @Column(length = 20) private String cod;
}
