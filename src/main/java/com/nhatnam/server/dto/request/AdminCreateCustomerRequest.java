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

    /**
     * Tên trên hợp đồng — tuỳ chọn.
     * Để trống/null → hệ thống dùng tên công ty (COMPANY) hoặc tên khách (RETAIL).
     */
    private String contractName;

    // ── Ngày kỷ niệm ─────────────────────────────────────────────────────
    /**
     * NGÀY SINH NHẬT (epoch millis) — BẮT BUỘC khi {@code customerType = RETAIL}.
     *
     * <p>Ràng buộc kiểm ở service chứ không dùng {@code @NotNull}: cùng một DTO này
     * phục vụ cả khách công ty (không có sinh nhật) lẫn khách lẻ, nên annotation
     * tĩnh không diễn đạt được điều kiện "bắt buộc TÙY loại khách".
     */
    private Long birthday;

    /** NGÀY KHAI TRƯƠNG CỬA HÀNG MỚI (epoch millis) — chỉ COMPANY, KHÔNG bắt buộc. */
    private Long storeOpeningDate;

    /** Phân loại khách hàng. Gửi 0 hoặc null để bỏ phân loại. */
    private Long categoryId;
}
