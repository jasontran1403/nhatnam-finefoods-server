package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Dòng chi tiết của {@link OfficeSupplyRequest}.
 */
@Entity
@Table(name = "office_supply_request_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OfficeSupplyRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private OfficeSupplyRequest request;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supply_item_id", nullable = false)
    private SupplyItem supplyItem;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(length = 500)
    private String note;
}
