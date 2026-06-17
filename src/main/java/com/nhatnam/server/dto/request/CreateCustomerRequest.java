package com.nhatnam.server.dto.request;

import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
public class CreateCustomerRequest {

    @NotBlank(message = "Số điện thoại không được để trống")
    @Pattern(regexp = "^[0-9]{10,15}$", message = "Số điện thoại không hợp lệ")
    private String phone;

    @NotBlank(message = "Tên khách hàng không được để trống")
    private String name;

    @Email(message = "Email không hợp lệ")
    private String email;

    @Min(value = 0, message = "Chiết khấu tối thiểu là 0%")
    @Max(value = 100, message = "Chiết khấu tối đa là 100%")
    private Integer discountRate = 0;

    @Min(value = 0, message = "Công nợ tối thiểu là 0 ngày")
    @Max(value = 30, message = "Công nợ tối đa là 30 ngày")
    private Integer debtDays = 0;

    private List<AddressRequest> addresses;

    private Boolean assignPrivate = false;

    @Data
    public static class AddressRequest {
        @NotBlank(message = "Địa chỉ không được để trống")
        private String address;

        private boolean isDefault = false;
    }
}