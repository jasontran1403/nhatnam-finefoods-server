package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "warehouse")
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;          // Tên kho hiển thị

    @Column
    private String address;       // Địa chỉ kho

    /**
     * TRANSIT  = Kho trung chuyển (chỉ nhập & chuyển, không xuất bán)
     * SALE     = Kho bán hàng (nhập, xuất bán, chuyển, điều chỉnh)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WarehouseType type;

    @Column(nullable = false)
    private boolean active = true;

    private Long createdAt;
    private Long updatedAt;

    public enum WarehouseType {
        TRANSIT, SALE
    }
}