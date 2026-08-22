package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.enumtype.VatRate;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "`order`")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "order_code", unique = true, nullable = false)
    private String orderCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "customer_name")
    private String customerName;

    @Builder.Default
    @Column(name = "surcharge", precision = 15, scale = 2)
    private BigDecimal surcharge = BigDecimal.ZERO;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;

    /**
     * TỈNH/THÀNH PHỐ của địa chỉ giao hàng — chọn từ dropdown, luôn khớp {@code data.json}.
     *
     * <p>Tách khỏi chuỗi địa chỉ tự do vì quy tắc COD tra theo cặp (tỉnh, phường). Khớp
     * tên trong một chuỗi tự do từng gây hàng loạt lỗi: "Q1" không khớp, "Nguyễn Huệ"
     * khớp nhầm thành tỉnh Huế, "Phú Thọ" khớp nhầm phường của Bình Dương.
     */
    @Column(name = "province_name", length = 120)
    private String provinceName;

    /** PHƯỜNG/XÃ/ĐẶC KHU — chọn từ dropdown, đã lọc theo tỉnh đang chọn. */
    @Column(name = "ward_name", length = 150)
    private String wardName;


    @Column(name = "ordered_by_name")
    private String orderedByName;

    @Column(name = "discount_rate", nullable = false)
    private Integer discountRate = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "vat_rate", nullable = false)
    private VatRate vatRate = VatRate.ZERO;

    private String type;

    @Column(name = "warehouse_name", nullable = false)
    private String warehouseName;

    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Builder.Default
    @Column(name = "actual_amount_paid", precision = 15, scale = 2)
    private BigDecimal actualAmountPaid = BigDecimal.ZERO;

    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "final_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal finalAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "customer_type", length = 20)
    private String customerType;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "short_name", length = 100)
    private String shortName;

    @Column(name = "tax_code", length = 20)
    private String taxCode;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(name = "company_phone", length = 20)
    private String companyPhone;

    @Column(name = "debt_days")
    private int debtDays;

    @Builder.Default
    @Column(name = "paid_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /**
     * SNAPSHOT của {@code Customer.requirePrepayment} tại thời điểm TẠO ĐƠN.
     *
     * <p>Dùng snapshot (thay vì đọc live từ Customer) để owner đổi cấu hình khách hàng
     * KHÔNG làm thay đổi các đơn đã tạo trước đó.
     *
     * <p>NULL = đơn cũ (tạo trước tính năng này) → fallback đọc từ Customer.
     */
    @Column(name = "require_prepayment")
    private Boolean requirePrepayment;

    @Column(name = "company_address", length = 300)
    private String companyAddress;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Column(name = "pending_payment_at", nullable = false)
    private Long pendingPaymentAt;

    /** ==================== FIELD MỚI ==================== */
    @Column(name = "delivery_datetime")
    private Long deliveryDatetime;     // Lưu timestamp (milliseconds)

    @Column(name = "visible_to_seller_id")
    private Long visibleToSellerId;

    @Builder.Default
    @Column(name = "show_prices")
    private Boolean showPrices = Boolean.TRUE;  // Hiển thị giá trên phiếu đặt hàng

    @Builder.Default
    @Column(name = "hide_all_prices")
    private Boolean hideAllPrices = Boolean.FALSE;

    @Column(name = "receiver_name", length = 100)
    private String receiverName;

    /** Path file chứng từ nhận hàng */
    @Column(name = "receipt_file_url", columnDefinition = "TEXT")
    private String receiptFileUrl;

    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<OrderItem> orderItems;

    @Column(name = "delivery_info_json", columnDefinition = "TEXT")
    private String deliveryInfoJson;

    @Builder.Default
    @Column(name = "kpi_user_id", nullable = false)
    private Long kpiUserId = 0L;

    @Column(name = "surcharge_detail", columnDefinition = "TEXT")
    private String surchargeDetail; // JSON: [{"name":"Thùng xốp","amount":50000},...]

    // ══════════════════════════════════════════════════════════════════════════
    // KHÁCH THANH TOÁN DƯ (overpayment)
    // ══════════════════════════════════════════════════════════════════════════
    /**
     * SỐ TIỀN KHÁCH TRẢ DƯ cho đơn này — phần vượt quá số CẦN THU (đã làm tròn).
     *
     * <p>Không cộng vào {@code paidAmount} (đơn vẫn chỉ được coi là thu đủ đúng
     * {@code finalAmount}); đây là một khoản NỢ PHẢI TRẢ LẠI KHÁCH, sinh ra khi
     * phiếu thu được lập với số tiền lớn hơn số cần thu. Trong phiếu thu nhiều
     * đơn, phần dư luôn được gán cho ĐƠN CUỐI trong danh sách.
     *
     * <p>Kế toán/owner bấm "Tạo phiếu chi hoàn phần dư" để lập phiếu chi hoàn lại
     * số tiền này cho khách.
     */
    @Builder.Default
    @Column(name = "overpaid_amount", precision = 15, scale = 2)
    private BigDecimal overpaidAmount = BigDecimal.ZERO;

    /**
     * Mã phiếu chi đã lập để hoàn phần dư ({@code null} = chưa lập).
     * Set giá trị này để chặn lập trùng phiếu hoàn cho cùng một đơn.
     */
    @Column(name = "overpaid_refund_voucher_code", length = 100)
    private String overpaidRefundVoucherCode;
}