package com.nhatnam.server.dto.warehouse;

import com.nhatnam.server.entity.Warehouse;
import lombok.Data;

@Data
public class UpdateWarehouseRequest {
    private String name;
    private String address;
    private Warehouse.WarehouseType type;
    private Boolean active;
}
