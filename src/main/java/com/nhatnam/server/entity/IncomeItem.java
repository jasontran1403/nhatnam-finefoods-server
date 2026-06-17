package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "income_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IncomeItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id", nullable = false)
    private IncomeVoucher voucher;

    @Column(name = "item_name", nullable = false, length = 300)
    private String itemName;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(columnDefinition = "TEXT")
    private String note;
}
