package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Đơn nháp — lưu tạm trạng thái giỏ hàng của seller.
 * Khi lưu: tồn kho được RELEASE (không hold).
 * Khi chuyển sang POS: kiểm tra lại tồn kho, nếu đủ → hold + tiến hành bình thường.
 *
 * type = "DRAFT"     → đơn nháp thường
 * type = "SCHEDULED" → đơn hẹn giờ xuất
 */
@Entity
@Table(name = "draft_order")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_code", unique = true, nullable = false, length = 50)
    private String draftCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "payment_method", length = 30)
    private String paymentMethod;

    @Column(name = "discount_rate", nullable = false)
    @Builder.Default
    private Integer discountRate = 0;

    @Column(name = "discount_amount", precision = 15, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "surcharge", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal surcharge = BigDecimal.ZERO;

    /** JSON: [{"name":"Thùng xốp","amount":50000},...] */
    @Column(name = "surcharge_detail", columnDefinition = "TEXT")
    private String surchargeDetail;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "warehouse_name", length = 100)
    private String warehouseName;

    @Column(name = "delivery_datetime")
    private Long deliveryDatetime;

    @Column(name = "ordered_by_name", length = 100)
    private String orderedByName;

    @Builder.Default
    @Column(name = "show_prices")
    private Boolean showPrices = Boolean.TRUE;

    @Builder.Default
    @Column(name = "hide_all_prices")
    private Boolean hideAllPrices = Boolean.FALSE;

    @Column(name = "receiver_name", length = 100)
    private String receiverName;

    @Column(name = "receiver_phone", length = 20)
    private String receiverPhone;

    @Column(name = "receiver_address", columnDefinition = "TEXT")
    private String receiverAddress;

    @Column(name = "receiver_info_id")
    private Long receiverInfoId;

    /**
     * "DRAFT"     — đơn nháp thường (mặc định)
     * "SCHEDULED" — đơn hẹn giờ xuất
     */
    @Builder.Default
    @Column(name = "type", length = 30)
    private String type = "DRAFT";

    /**
     * Thời điểm cần xuất đơn (chỉ dùng khi type = SCHEDULED).
     * Lưu dạng timestamp milliseconds.
     */
    @Column(name = "scheduled_at")
    private Long scheduledAt;

    // ── Thông tin công ty / hóa đơn (giống Order) ─────────────────────────
    @Column(name = "customer_type", length = 20)
    private String customerType;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "tax_code", length = 20)
    private String taxCode;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(name = "company_phone", length = 20)
    private String companyPhone;

    @Column(name = "company_address", length = 300)
    private String companyAddress;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @OneToMany(mappedBy = "draftOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<DraftOrderItem> draftItems = new ArrayList<>();

    @PrePersist
    void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }

    @Override
    public String toString() {
        return """
        DraftOrder{
            id=%s,
            draftCode='%s',
            userId=%s,
            customerId=%s,
            customerName='%s',
            customerPhone='%s',
            customerEmail='%s',
            shippingAddress='%s',
            notes='%s',
            paymentMethod='%s',
            discountRate=%s,
            discountAmount=%s,
            surcharge=%s,
            surchargeDetail='%s',
            warehouseId=%s,
            warehouseName='%s',
            deliveryDatetime=%s,
            orderedByName='%s',
            showPrices=%s,
            hideAllPrices=%s,
            receiverName='%s',
            receiverPhone='%s',
            receiverAddress='%s',
            receiverInfoId=%s,
            type='%s',
            scheduledAt=%s,
            customerType='%s',
            companyName='%s',
            taxCode='%s',
            contactName='%s',
            companyPhone='%s',
            companyAddress='%s',
            createdAt=%s,
            updatedAt=%s,
            draftItemsCount=%s
        }
        """.formatted(
                id,
                draftCode,
                user != null ? user.getId() : null,
                customer != null ? customer.getId() : null,
                customerName,
                customerPhone,
                customerEmail,
                shippingAddress,
                notes,
                paymentMethod,
                discountRate,
                discountAmount,
                surcharge,
                surchargeDetail,
                warehouseId,
                warehouseName,
                deliveryDatetime,
                orderedByName,
                showPrices,
                hideAllPrices,
                receiverName,
                receiverPhone,
                receiverAddress,
                receiverInfoId,
                type,
                scheduledAt,
                customerType,
                companyName,
                taxCode,
                contactName,
                companyPhone,
                companyAddress,
                createdAt,
                updatedAt,
                draftItems != null ? draftItems.size() : 0
        );
    }
}