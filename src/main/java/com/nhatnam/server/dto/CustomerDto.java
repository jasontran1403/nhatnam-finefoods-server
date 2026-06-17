package com.nhatnam.server.dto;

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
    private Integer discountRate;
    private Boolean isActive;
    private Integer debtDays;

    // Company
    private String companyName;
    private String taxCode;
    private String companyPhone;
    private String companyAddress;
    private String contactName;

    // Seller gắn kèm (Change 9)
    private Long sellerId;
    private String sellerName;
    private String sellerUsername;

    private Long createdAt;
    private Long updatedAt;

    // Loại giá áp dụng: RETAIL_PRICE | WHOLESALE_PRICE
    private String pricingType;

    // Khách do admin/owner tạo (createdBySeller = null → KPI chung)
    private Boolean createdByAdmin;
}
