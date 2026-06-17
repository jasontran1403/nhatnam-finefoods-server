package com.nhatnam.server.dto.warehouse;

import com.nhatnam.server.entity.Warehouse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateWarehouseRequest {

    @NotBlank(message = "Tên kho bắt buộc")
    private String name;

    private String address;

    @NotNull(message = "Loại kho bắt buộc")
    private Warehouse.WarehouseType type;

    private Boolean active = true;
}
