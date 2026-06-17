package com.nhatnam.server.dto.request;

import lombok.Data;

/**
 * Admin/Owner tạo hoặc cập nhật khách hàng.
 * Email nullable (đặc biệt khi customerType = COMPANY).
 */
@Data
public class AdminCreateCustomerRequest {

    private String phone;
    private String name;
    private String email;          // nullable
    private String customerCode;

    /**
     * "RETAIL" | "COMPANY"
     * RETAIL = cá nhân, COMPANY = doanh nghiệp
     */
    private String customerType;

    /**
     * "RETAIL_PRICE" | "WHOLESALE_PRICE"
     * RETAIL_PRICE  → bán theo giá gốc (bỏ qua khung giá)
     * WHOLESALE_PRICE → bán theo khung giá (tier)
     */
    private String pricingType;

    private Integer discountRate;
    private Integer debtDays;

    // Thông tin công ty (chỉ khi customerType = COMPANY)
    private String companyName;
    private String taxCode;
    private String companyPhone;
    private String companyAddress;
    private String contactName;
}
