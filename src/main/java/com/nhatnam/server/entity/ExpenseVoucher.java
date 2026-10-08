package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Phiếu chi phí — do SUPER_ACCOUNTANT hoặc SUPER_WAREHOUSE tạo.
 * Cần ADMIN hoặc OWNER duyệt trước khi tính vào chi phí.
 */
@Entity
@Table(name = "expense_voucher")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpenseVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String voucherCode;   // EV-YYYYMMDD-XXXX

    /**
     * Số phiếu chi — do người dùng nhập (hoặc dùng số gợi ý = số gần nhất + 1).
     * KHÔNG unique vì số phiếu chạy tới 15000 sẽ quay vòng về 1 nên có thể trùng.
     * Có thể null với các phiếu cũ tạo trước khi có tính năng này (khi đó hiển thị
     * fallback về {@link #voucherCode}).
     */
    @Column(name = "payment_number", length = 100)
    private String paymentNumber;

    /** Tên đơn vị thi công / nhà cung cấp — có thể rỗng */
    @Column(length = 300)
    private String vendorName;

    /**
     * FK tới {@link MaterialVendor} — NCC được chọn khi lập phiếu. Dùng để biết
     * danh mục khoản chi ({@link VendorExpenseCategory}) áp dụng. Null với phiếu cũ.
     */
    @Column(name = "vendor_id")
    private Long vendorId;

    /** Lý do chi — bắt buộc */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    /**
     * Kỳ chi phí — tháng mà khoản chi này được TÍNH VÀO (không nhất thiết là
     * tháng tạo phiếu). Định dạng "YYYY-MM". VD: tiền điện tạo phiếu ngày
     * 07/06/2026 nhưng là chi phí của tháng 5 → expensePeriod = "2026-05".
     * Nếu không nhập, mặc định = tháng trước tháng tạo phiếu (xem service).
     */
    @Column(name = "expense_period", length = 7)
    private String expensePeriod;

    /**
     * Ngày chi cụ thể (epoch ms, mốc 00:00 giờ VN) khi người dùng chọn chế độ
     * "Ngày" thay vì "Kỳ". Dùng để tạo lại các phiếu chi cũ đúng ngày phát sinh.
     * <p>Khi có giá trị này, {@link #expensePeriod} được backend suy ra = THÁNG của
     * ngày này (để báo cáo chi phí vẫn quy theo tháng như cũ). Khi tạo theo "Kỳ"
     * (chọn tháng) thì trường này = null.
     */
    @Column(name = "expense_date")
    private Long expenseDate;

    /**
     * Mốc thời gian dùng để TỔNG HỢP chi phí trên dashboard/phân tích/KPI
     * (epoch ms). Được tính lúc tạo phiếu:
     * <ul>
     *   <li>Chế độ "Ngày" → = {@link #expenseDate} (ngày phiếu chi được set).</li>
     *   <li>Chế độ "Kỳ"  → = 00:00 ngày 1 của tháng {@link #expensePeriod} (giờ VN).</li>
     * </ul>
     * Nhờ vậy phiếu chi tính theo NGÀY ĐÃ SET (hoặc kỳ) thay vì ngày tạo phiếu.
     * Các phiếu cũ (trước khi có cột này) sẽ null — báo cáo tự fallback về
     * {@link #createdAt} nên không đổi số liệu lịch sử.
     */
    @Column(name = "effective_at")
    private Long effectiveAt;

    /** Người lập phiếu (snapshot tên) */
    @Column(nullable = false, length = 200)
    private String createdByName;

    /** Người yêu cầu (snapshot tên) — có thể là người khác */
    @Column(length = 200)
    private String requestedByName;

    /** Người yêu cầu (user entity) — nullable */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_id")
    private User requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VoucherStatus status = VoucherStatus.PENDING;

    /** Người duyệt (ADMIN / OWNER) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @Column(length = 200)
    private String approvedByName;

    @Column(name = "approved_at")
    private Long approvedAt;

    /** Lý do từ chối */
    @Column(columnDefinition = "TEXT")
    private String rejectReason;

    // ── Danh mục & loại thanh toán (Mục 1 & 4) ──────────────────────────────
    /**
     * Danh mục chi = KEY của VENDOR_TYPE_LABEL (VD: "MATERIAL", "ELECTRICITY"...).
     * Dùng cho luật duyệt: SUPER_ACCOUNTANT chỉ được duyệt khi danh mục nằm trong
     * tập OWNER cấu hình VÀ tổng tiền < ngưỡng. Có thể null với phiếu cũ.
     */
    @Column(name = "vendor_type", length = 50)
    private String vendorType;

    /** Loại thanh toán: CASH | BANK_TRANSFER (mặc định CASH cho phiếu cũ). */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20)
    @Builder.Default
    private PaymentType paymentType = PaymentType.CASH;

    /** Tên ngân hàng — bắt buộc khi paymentType = BANK_TRANSFER */
    @Column(name = "bank_name", length = 100)
    private String bankName;

    /** Mã tham chiếu giao dịch ngân hàng — bắt buộc khi paymentType = BANK_TRANSFER */
    @Column(name = "bank_ref", length = 200)
    private String bankRef;

    // ── Thông tin tài khoản KHÁCH HÀNG nhận tiền hoàn dư ─────────────────────
    /** Tên ngân hàng của khách hàng — dùng khi hoàn phần dư qua chuyển khoản */
    @Column(name = "customer_bank_name", length = 100)
    private String customerBankName;

    /** Số tài khoản ngân hàng của khách hàng */
    @Column(name = "customer_bank_account", length = 50)
    private String customerBankAccount;

    /** Tên chủ tài khoản ngân hàng của khách hàng */
    @Column(name = "customer_bank_holder", length = 200)
    private String customerBankHolder;

    /**
     * Cấp duyệt được xác định & "đóng băng" tại lúc tạo phiếu:
     * <ul>
     *   <li>SUPER_ACCOUNTANT — SA đủ điều kiện duyệt (tiền < ngưỡng & danh mục cho phép).</li>
     *   <li>OWNER — phải OWNER/ADMIN duyệt.</li>
     * </ul>
     * OWNER/ADMIN luôn có thể duyệt mọi phiếu PENDING (duyệt giùm SA khi SA nghỉ).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approver_scope", length = 20)
    @Builder.Default
    private ApproverScope approverScope = ApproverScope.OWNER;

    /** Role của người tạo (snapshot) — dùng lọc theo phòng ban. Null = coi như ACCOUNTANT. */
    @Column(name = "created_by_role", length = 40)
    private String createdByRole;

    // ══════════════════════════════════════════════════════════════════════════
    // ỨNG LƯƠNG — chỉ dùng khi vendorType = SALARY_ADVANCE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ID nhân viên được ứng lương.
     * Chỉ set khi {@code vendorType = "SALARY_ADVANCE"}.
     * Dùng để tổng hợp tổng tiền đã ứng trong tháng của nhân viên đó.
     */
    @Column(name = "salary_advance_user_id")
    private Long salaryAdvanceUserId;

    /**
     * Tháng ứng lương — định dạng "YYYY-MM" (VD: "2026-09").
     * Dùng để giới hạn và cộng dồn ứng lương trong đúng tháng.
     */
    @Column(name = "salary_advance_month", length = 7)
    private String salaryAdvanceMonth;

    /** Danh sách khoản chi */
    @Builder.Default
    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExpenseItem> items = new ArrayList<>();

    /** Ảnh chứng từ — JSON array ["url1","url2",...] */
    @Column(name = "image_urls", columnDefinition = "TEXT")
    private String imageUrls;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum VoucherStatus {
        PENDING,   // chờ duyệt
        APPROVED,  // đã duyệt
        REJECTED   // từ chối
    }

    public enum PaymentType {
        CASH,          // Tiền mặt
        BANK_TRANSFER  // Chuyển khoản
    }

    /** Cấp được kỳ vọng duyệt phiếu khi đang PENDING. */
    public enum ApproverScope {
        SUPER_ACCOUNTANT,
        OWNER
    }
}