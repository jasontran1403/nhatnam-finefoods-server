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

    /** Bắt buộc thanh toán trước khi giao hàng (chỉ OWNER/ADMIN chỉnh được) */
    private Boolean requirePrepayment;

    /** Tổng công nợ chưa thanh toán (đồng, làm tròn lên từng đơn rồi cộng dồn). */
    private Long unpaidDebt;

    // Company
    private String companyName;
    private String taxCode;
    private String companyPhone;
    private String companyAddress;
    private String contactName;

    /** Tên trên hợp đồng do người dùng nhập (null = chưa đặt riêng) */
    private String contractName;

    /** Tên hợp đồng SAU KHI fallback — luôn có giá trị để hiển thị/in ấn */
    private String contractNameResolved;

    /** Tên mặc định nếu bỏ trống — FE dùng làm placeholder */
    private String contractNameDefault;

    /**
     * Khách đã có ảnh hợp đồng chưa.
     *
     * <p>Là điều kiện để được mua CÔNG NỢ — FE dựa vào cờ này để bật/tắt lựa
     * chọn thanh toán công nợ và để đổi tên hợp đồng thành badge xanh.
     */
    private Boolean hasContract;

    /**
     * Khách này CÓ ĐƯỢC thanh toán công nợ không.
     *
     * <p>KHÁC {@link #hasContract}: khách cũ (tạo trước khi có tính năng hợp đồng)
     * vẫn được bán chịu dù chưa có hợp đồng. FE phải dựa vào cờ NÀY để ẩn/hiện
     * lựa chọn Công nợ, còn {@code hasContract} chỉ để hiển thị badge.
     */
    private Boolean debtAllowed;

    /** true = khách mới, bắt buộc có hợp đồng mới được công nợ. */
    private Boolean contractRequired;

    // Seller gắn kèm
    private Long sellerId;
    private String sellerName;
    private String sellerUsername;

    /** true nếu khách do admin/owner tạo (createdBySeller = null) */
    private Boolean createdByAdmin;

    // ── Ngày kỷ niệm (chăm sóc khách hàng) ───────────────────────────────
    /** Sinh nhật — chỉ khách RETAIL. Epoch millis. */
    private Long birthday;

    /** Ngày khai trương cửa hàng mới — chỉ khách COMPANY. Epoch millis. */
    private Long storeOpeningDate;

    /**
     * Số ngày tới dịp kỷ niệm kế tiếp (sinh nhật với khách lẻ, khai trương với khách
     * công ty). {@code 0} = đúng hôm nay. {@code null} = chưa khai báo ngày.
     */
    private Integer daysUntilAnniversary;

    /**
     * Dịp kỷ niệm rơi vào THÁNG NÀY và CHƯA QUA — FE dùng cờ này để tô màu hàng,
     * thay vì tự tính lại ở client (sai lệch múi giờ trình duyệt).
     */
    private Boolean anniversaryUpcoming;

    // ── Phân loại khách hàng ─────────────────────────────────────────────
    private Long categoryId;
    private String categoryName;
    private String categoryColor;
    private Integer categorySortOrder;

    private Long createdAt;
    private Long updatedAt;
}