package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class CustomerResponse {
    private Long id;
    private String phone;
    private String name;
    private String email;
    private Integer discountRate;
    private Boolean isActive;
    private Long createdAt;
    private Long updatedAt;
    private List<AddressResponse> addresses;

    @Data
    @Builder
    public static class AddressResponse {
        private Long id;
        private String address;
        private Boolean isDefault;
    }
}
