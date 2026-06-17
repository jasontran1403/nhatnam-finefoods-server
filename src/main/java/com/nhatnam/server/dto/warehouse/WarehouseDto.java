package com.nhatnam.server.dto.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseDto {
    private Long id;
    private String name;
    private String address;
    private String type;       // TRANSIT | SALE
    private boolean active;
    private Long createdAt;
    private Long updatedAt;
    private Integer ingredientCount;  // optional — số nguyên liệu trong kho
}
