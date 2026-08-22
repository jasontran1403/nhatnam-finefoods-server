package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * NHÓM MẶT HÀNG THEO NHÀ CUNG CẤP trong một phiếu đặt VPP.
 *
 * <p>Sinh ra tại bước SUPER_ACCOUNTANT xác nhận đặt hàng; khoá logic là
 * {@code (material_request_id, supplier_id)} — mỗi NCC đúng 1 nhóm trong 1 phiếu.
 *
 * <p><b>Mỗi group → đúng 1 phiếu chi HOẶC 1 bút toán công nợ.</b>
 *
 * <p>Group được LIÊN KẾT với một {@link MaterialRequestVendor} của cùng phiếu
 * ({@link #requestVendor}) để tái sử dụng nguyên vẹn hệ thống CÔNG NỢ NCC đã có
 * (VendorDebtService đọc công nợ từ material_request_vendor). Group chỉ giữ thêm
 * các thông tin riêng của luồng VPP: mã nhóm, ETA, liên hệ, thuế/phí, phiếu chi.
 */
@Entity
@Table(
        name = "supply_order_group",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_supply_group_order_supplier",
                columnNames = {"material_request_id", "supplier_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplyOrderGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_id", nullable = false)
    private MaterialRequest materialRequest;

    /** FK tới {@link MaterialVendor} — danh mục NCC dùng chung toàn hệ thống. */
    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "supplier_name", nullable = false, length = 300)
    private String supplierName;

    /**
     * Bản ghi NCC-trong-phiếu tương ứng. Là cầu nối sang hệ thống công nợ có sẵn:
     * khi tất toán chọn DEBT, chính bản ghi này được set paymentStatus = DEBT.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_vendor_id")
    private MaterialRequestVendor requestVendor;

    /** Mã nhóm: SG-{requestCode}-{n} — hiển thị cho kế toán đối chiếu. */
    @Column(nullable = false, length = 80)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private GroupStatus status = GroupStatus.ORDERED;

    /** Thời gian giao dự kiến (có thể gia hạn nhiều lần). */
    @Column(name = "expected_delivery_at")
    private Long expectedDeliveryAt;

    @Column(name = "contact_name", length = 200)
    private String contactName;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    /** PAY_NOW → tạo phiếu chi · DEBT → ghi công nợ. Null cho tới bước tất toán. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", length = 20)
    private PaymentMode paymentMode;

    /** ID {@link ExpenseVoucher} sinh ra khi PAY_NOW. Null khi DEBT. */
    @Column(name = "payment_voucher_id")
    private Long paymentVoucherId;

    /** ID bút toán công nợ (= requestVendor.id) khi DEBT. Null khi PAY_NOW. */
    @Column(name = "supplier_debt_id")
    private Long supplierDebtId;

    /** Tổng tiền hàng của nhóm (chưa gồm thuế/phí). */
    @Column(name = "goods_amount", precision = 18, scale = 3)
    private BigDecimal goodsAmount;

    /** Tổng thuế/phí của nhóm. */
    @Column(name = "fee_amount", precision = 18, scale = 3)
    private BigDecimal feeAmount;

    /** Tổng phải trả = goodsAmount + feeAmount, LÀM TRÒN LÊN hàng đơn vị đồng. */
    @Column(name = "total_amount", precision = 18, scale = 0)
    private BigDecimal totalAmount;

    /** Thuế/phí nhiều dòng, label tự nhập — cấu trúc tái dùng từ phiếu nguyên liệu. */
    @Builder.Default
    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<SupplyOrderGroupFee> taxFees = new ArrayList<>();

    @Column(name = "settled_at")
    private Long settledAt;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum GroupStatus { ORDERED, RECEIVED, SETTLED }

    public enum PaymentMode { PAY_NOW, DEBT }
}
