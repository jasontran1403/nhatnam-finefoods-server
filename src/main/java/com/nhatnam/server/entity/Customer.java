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

    @Column(name = "deleted_at")
    private Long deletedAt;

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
    void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }

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