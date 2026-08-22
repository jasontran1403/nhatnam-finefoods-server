package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Thông tin cơ bản ─────────────────────────────────────────

    @Column(nullable = true, length = 70, unique = true)
    private String phone;

    @Column(length = 100)
    private String name;

    @Column(length = 150)
    private String email;

    @Column(name = "customer_code", length = 70, unique = true)
    private String customerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", length = 20)
    @Builder.Default
    private CustomerType customerType = CustomerType.RETAIL;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_type", length = 20)
    @Builder.Default
    private PricingType pricingType = PricingType.RETAIL_PRICE;

    @Column(name = "discount_rate", nullable = false)
    @Builder.Default
    private Integer discountRate = 0;

    @Column(name = "invoice_days", nullable = false)
    @Builder.Default
    private Integer invoiceDays = -1;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "debt_days", nullable = false)
    @Builder.Default
    private Integer debtDays = 0;

    /**
     * YÊU CẦU THANH TOÁN TRƯỚC KHI GIAO HÀNG.
     *
     * <p>Chỉ OWNER/ADMIN được bật/tắt. Khi = true:
     * <ul>
     *   <li>Đơn của khách này KHÔNG được chuyển sang "Đang giao" nếu chưa thanh toán đủ.</li>
     *   <li>Kế toán có thể tạo phiếu thu cho đơn khi đơn còn ở trạng thái "Đang chuẩn bị" —
     *       phiếu thu chỉ ghi nhận ĐÃ THU TIỀN, KHÔNG chuyển đơn sang "Hoàn thành".</li>
     * </ul>
     */
    @Column(name = "require_prepayment", nullable = false)
    @Builder.Default
    private Boolean requirePrepayment = false;

    @Column(name = "deleted_at")
    private Long deletedAt;

    /**
     * KHÁCH NÀY CÓ BẮT BUỘC PHẢI CÓ HỢP ĐỒNG MỚI ĐƯỢC MUA CÔNG NỢ KHÔNG.
     *
     * <p>Quy tắc "phải có hợp đồng mới được bán chịu" chỉ áp dụng cho khách tạo
     * MỚI kể từ khi tính năng hợp đồng ra đời. Khách cũ đã mua công nợ từ trước
     * vẫn giữ nguyên cách làm việc — bắt họ dừng bán chịu cho tới khi ai đó kịp
     * scan hợp đồng lên sẽ làm đứt luồng bán hàng đang chạy.
     *
     * <p><b>NULL = khách cũ, được miễn.</b> Đây là cách tự phân biệt mà không cần
     * script migration: các dòng có sẵn trong bảng đều mang giá trị NULL, còn
     * mọi bản ghi tạo từ nay về sau được {@code onCreate()} đặt thành TRUE.
     *
     * <p>Dùng {@link #isContractRequiredForDebt()} thay vì đọc thẳng trường này,
     * để chỗ nào cũng hiểu NULL theo cùng một nghĩa.
     */
    @Column(name = "contract_required")
    private Boolean contractRequired;

    /** TRUE nghĩa là khách này phải có hợp đồng mới được thanh toán công nợ. */
    public boolean isContractRequiredForDebt() {
        return Boolean.TRUE.equals(contractRequired);
    }

    // ── Ngày kỷ niệm (chăm sóc khách hàng) ───────────────────────
    /**
     * NGÀY SINH NHẬT — chỉ dùng cho khách RETAIL (khách lẻ). Epoch millis, mốc 00:00 giờ VN.
     *
     * <p>BẮT BUỘC khi tạo/đổi khách sang loại RETAIL (xem {@code CustomerAnniversaryUtil}).
     * Dùng để: tô màu ở màn hình KH của SELLER, nút sort "sắp tới sinh nhật",
     * và làm căn cứ tạo voucher quà tặng sinh nhật.
     *
     * <p>Cố ý để nullable trong DB: khách CŨ tạo trước khi có tính năng này chưa có
     * dữ liệu — bắt buộc ở tầng validate khi ghi, không phải ở tầng schema, để không
     * phải viết migration đoán bừa ngày sinh cho hàng nghìn bản ghi cũ.
     */
    @Column(name = "birthday")
    private Long birthday;

    /**
     * NGÀY KHAI TRƯƠNG CỬA HÀNG MỚI — chỉ dùng cho khách COMPANY. Epoch millis.
     *
     * <p>KHÔNG bắt buộc (khách công ty có thể không mở cửa hàng mới). Seller nhập tay
     * khi biết tin; hệ thống dùng để nhắc và cho phép tạo đơn tặng / voucher khai trương.
     */
    @Column(name = "store_opening_date")
    private Long storeOpeningDate;

    // ── Phân loại khách hàng (tuỳ chọn) ─────────────────────────
    // Ví dụ: Nhà hàng, Quán cà phê, Siêu thị, Đại lý...
    // null = chưa phân loại (nhóm "Chưa phân loại" trên UI)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_category_id")
    private CustomerCategory customerCategory;

    // ── Thông tin công ty (chỉ dùng khi COMPANY) ─────────────────

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "tax_code", length = 60, unique = true)
    private String taxCode;

    @Column(name = "company_phone", length = 20)
    private String companyPhone;

    @Column(name = "company_address", length = 300)
    private String companyAddress;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    // ── Tên trên hợp đồng ────────────────────────────────────────
    /**
     * TÊN DÙNG TRÊN HỢP ĐỒNG.
     *
     * <p>Có thể khác tên khách hàng / tên công ty (VD: khách đặt hàng dưới tên chi nhánh
     * nhưng hợp đồng ký với pháp nhân mẹ).
     *
     * <p>Để TRỐNG (null) = mặc định dùng tên công ty (khách COMPANY) hoặc tên khách (RETAIL).
     * Dùng {@link #resolvedContractName()} để lấy tên hiển thị cuối cùng.
     */
    @Column(name = "contract_name", length = 250)
    private String contractName;

    /** Tên hợp đồng thực tế: tự nhập nếu có, không thì fallback về tên công ty / tên khách. */
    public String resolvedContractName() {
        if (contractName != null && !contractName.isBlank()) return contractName;
        return defaultContractName();
    }

    /** Tên mặc định (dùng làm placeholder trên UI + fallback khi contractName trống). */
    public String defaultContractName() {
        if (customerType == CustomerType.COMPANY
                && companyName != null && !companyName.isBlank()) {
            return companyName;
        }
        return name != null ? name : "";
    }

    // ── Địa chỉ / người nhận hàng ────────────────────────────────

    @Builder.Default
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CustomerReceiverInfo> receiverInfos = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_seller_id")
    private User createdBySeller;

    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    // ── Timestamps ───────────────────────────────────────────────

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = System.currentTimeMillis();
        // Khách tạo mới luôn thuộc diện bắt buộc có hợp đồng. Đặt ở đây thay vì
        // @Builder.Default để chắc chắn áp dụng cho mọi đường tạo khách, kể cả
        // các chỗ dựng entity bằng constructor hoặc setter.
        if (contractRequired == null) contractRequired = true;
    }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum CustomerType {
        COMPANY,
        RETAIL
    }

    public enum PricingType {
        RETAIL_PRICE,
        WHOLESALE_PRICE
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_seller_id")
    private User assignedSeller;

    @Column(name = "assigned_seller_name", length = 100)
    private String assignedSellerName; // snapshot tên, dùng để hiển thị
}