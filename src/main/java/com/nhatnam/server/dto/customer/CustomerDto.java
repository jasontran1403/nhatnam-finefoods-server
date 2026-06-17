package com.nhatnam.server.dto.customer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDto {
    private Long id;
    private String phone;
    private String name;
    private String email;
    private String customerCode;
    private String customerType;

    /** RETAIL_PRICE | WHOLESALE_PRICE */
    private String pricingType;

    private Integer discountRate;
    private Boolean isActive;
    private Integer debtDays;

    // Company
    private String companyName;
    private String taxCode;
    private String companyPhone;
    private String companyAddress;
    private String contactName;

    // Seller gắn kèm
    private Long sellerId;
    private String sellerName;
    private String sellerUsername;

    /** true nếu khách do admin/owner tạo (createdBySeller = null) */
    private Boolean createdByAdmin;

    private Long createdAt;
    private Long updatedAt;
}